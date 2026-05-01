package app.beat.llm;

import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Classifies an outlet's tier (1, 2, or 3) when our curated table doesn't already know it. Cached
 * forever on the {@code outlets} row by setting {@code tier_source = 'llm'}.
 */
@Component
public class OutletTierClassifier {

  private static final Logger log = LoggerFactory.getLogger(OutletTierClassifier.class);
  private static final Set<Integer> ALLOWED_TIERS = Set.of(1, 2, 3);

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final OutletRepository outlets;
  private final ObjectMapper json = new ObjectMapper();
  private final String modelOverride;

  public OutletTierClassifier(
      PromptLoader prompts,
      AnthropicClient anthropic,
      OutletRepository outlets,
      @Value("${ANTHROPIC_MODEL_EXTRACTION:}") String modelOverride) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.outlets = outlets;
    this.modelOverride = modelOverride;
  }

  /**
   * If the outlet already has a curated/LLM-classified/manual tier, returns it unchanged. Otherwise
   * calls Sonnet, persists the result, and returns the new tier. Falls back to the existing default
   * tier if Anthropic isn't configured or fails.
   */
  public int classifyIfNeeded(Outlet outlet) {
    if (outlet == null) return 3;
    if (!"default".equals(outlet.tierSource())) return outlet.tier();
    if (!anthropic.isConfigured()) return outlet.tier();
    try {
      PromptTemplate t = prompts.get("outlet-tier-v1");
      String rendered = t.render(Map.of("outlet_name", outlet.name(), "domain", outlet.domain()));
      String model = modelOverride.isBlank() ? t.model() : modelOverride;
      AnthropicClient.Result r =
          anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), rendered);
      String text = r.text().trim();
      int s = text.indexOf('{');
      int e = text.lastIndexOf('}');
      if (s < 0 || e <= s) {
        log.warn("outlet-tier: no JSON in response for {}", outlet.domain());
        return outlet.tier();
      }
      var node = json.readTree(text.substring(s, e + 1));
      int tier = node.path("tier").asInt(0);
      if (!ALLOWED_TIERS.contains(tier)) {
        log.warn("outlet-tier: unexpected tier {} for {}", tier, outlet.domain());
        return outlet.tier();
      }
      outlets.setLlmTier(outlet.id(), tier);
      log.info("outlet-tier: {} classified as tier {}", outlet.domain(), tier);
      return tier;
    } catch (Exception ex) {
      log.warn("outlet-tier: fallback for {}: {}", outlet.domain(), ex.toString());
      return outlet.tier();
    }
  }
}
