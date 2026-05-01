package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TieredLLMTest {

  private static AnthropicClient.Result fakeResult() {
    return new AnthropicClient.Result("ok", 100, 50, 0, 0, BigDecimal.ZERO);
  }

  @Test
  void haikuOnlyPath_doesNotCallSonnetWhenConfident() {
    AtomicInteger sonnetCalls = new AtomicInteger();
    var out =
        TieredLLM.run(
            () -> new TieredLLM.StepResult<>(0.95, fakeResult()),
            () -> {
              sonnetCalls.incrementAndGet();
              return new TieredLLM.StepResult<>(0.99, fakeResult());
            },
            confidence -> confidence < 0.85,
            "test");

    assertThat(out.tier()).isEqualTo(TieredLLM.Tier.HAIKU);
    assertThat(out.value()).isEqualTo(0.95);
    assertThat(sonnetCalls.get()).isZero();
  }

  @Test
  void escalates_whenHaikuLowConfidence() {
    AtomicInteger sonnetCalls = new AtomicInteger();
    var out =
        TieredLLM.run(
            () -> new TieredLLM.StepResult<>(0.50, fakeResult()),
            () -> {
              sonnetCalls.incrementAndGet();
              return new TieredLLM.StepResult<>(0.95, fakeResult());
            },
            confidence -> confidence < 0.85,
            "test");

    assertThat(out.tier()).isEqualTo(TieredLLM.Tier.SONNET);
    assertThat(out.value()).isEqualTo(0.95);
    assertThat(sonnetCalls.get()).isOne();
  }
}
