package app.beat.llm;

import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-tier orchestrator per docs/18-cost-engineering.md §"Tier models by signal value": run a
 * cheap, high-volume model first (typically Haiku), inspect its result, escalate to a more capable
 * model (Sonnet) only when the first-tier output isn't trustworthy enough.
 *
 * <p>Used by extraction-v1-2, reply-classification-v1-1, pitch-attribution-v1-1, and journalist
 * ranking. The shape of the escalate predicate is feature-specific — e.g. extraction escalates when
 * confidence &lt; 0.85, reply classification escalates when {@code is_auto_reply} is ambiguous —
 * but the orchestration is identical.
 *
 * <p>The class is intentionally generic over the parsed result type so each caller works with its
 * own typed result rather than raw JSON.
 */
public final class TieredLLM {

  private static final Logger log = LoggerFactory.getLogger(TieredLLM.class);

  private TieredLLM() {}

  /** Tier the result was actually produced by — telemetry / billing breakdown. */
  public enum Tier {
    HAIKU,
    SONNET
  }

  public record Tiered<T>(T value, Tier tier, AnthropicClient.Result raw) {}

  /**
   * Run the {@code haiku} step; if the parsed result triggers {@code escalate}, run the {@code
   * sonnet} step and return that instead. Either step is permitted to throw — exceptions propagate
   * and short-circuit the tier flow.
   *
   * @param haiku step that calls the cheap-tier prompt and returns a parsed result
   * @param sonnet step that calls the capable-tier prompt and returns a parsed result
   * @param escalate predicate over the haiku result; {@code true} to escalate
   */
  public static <T> Tiered<T> run(
      TierStep<T> haiku, TierStep<T> sonnet, Predicate<T> escalate, String featureLabel) {
    var first = haiku.run();
    if (!escalate.test(first.value())) {
      log.debug("tiered_llm feature={} tier=haiku escalated=false", featureLabel);
      return new Tiered<>(first.value(), Tier.HAIKU, first.raw());
    }
    log.info("tiered_llm feature={} tier=sonnet escalated=true", featureLabel);
    var second = sonnet.run();
    return new Tiered<>(second.value(), Tier.SONNET, second.raw());
  }

  /**
   * A single tier's call: returns the parsed value plus the raw billable {@link
   * AnthropicClient.Result}.
   */
  @FunctionalInterface
  public interface TierStep<T> {
    StepResult<T> run();
  }

  public record StepResult<T>(T value, AnthropicClient.Result raw) {}
}
