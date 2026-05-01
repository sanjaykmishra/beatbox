package app.beat.llm;

import app.beat.clientcontext.ClientContext;
import app.beat.extraction.UrlPrefilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cost-engineered extraction path per docs/18-cost-engineering.md and prompts/extraction-v1-2.md:
 *
 * <ol>
 *   <li>URL pre-filter — pattern-match obvious non-articles before any LLM call.
 *   <li>Cross-customer cache lookup keyed by (content_hash, prompt_version=extraction_v1.2).
 *   <li>Haiku first-pass with prompt-cached extraction instructions.
 *   <li>Confidence/schema gate — escalate to Sonnet only when needed.
 *   <li>Cache write-through; return result + which tier produced it.
 * </ol>
 *
 * <p>Workspace data never enters the cache key — the extracted JSON is a function of article
 * content + prompt version, so cross-workspace dedup is sound.
 */
@Service
public class TwoTierExtractionService {

  private static final Logger log = LoggerFactory.getLogger(TwoTierExtractionService.class);
  private static final int MAX_INPUT_TOKENS = 8000;

  /** Default Sonnet model for escalation when frontmatter doesn't drive it. */
  private static final String DEFAULT_ESCALATION_MODEL = "claude-sonnet-4-6";

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final ExtractionCacheRepository cache;
  private final UrlPrefilter urlPrefilter;
  private final ObjectMapper json = new ObjectMapper(); // for serializing results into the cache
  private final String haikuModelOverride;
  private final String sonnetModelOverride;

  public TwoTierExtractionService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      ExtractionCacheRepository cache,
      UrlPrefilter urlPrefilter,
      @Value("${ANTHROPIC_MODEL_EXTRACTION_HAIKU:}") String haikuModelOverride,
      @Value("${ANTHROPIC_MODEL_EXTRACTION_SONNET:}") String sonnetModelOverride) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.cache = cache;
    this.urlPrefilter = urlPrefilter;
    this.haikuModelOverride = haikuModelOverride;
    this.sonnetModelOverride = sonnetModelOverride;
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  /**
   * Result of a two-tier extraction. {@code tier} reports which model actually produced the
   * accepted result; {@code prefilterReason} non-null means we never called the LLM.
   */
  public record V12Outcome(
      ExtractionResult result, String promptVersion, TieredLLM.Tier tier, String prefilterReason) {}

  /**
   * Run the v1.2 pipeline against an article. Returns {@link Optional#empty()} when LLM is disabled
   * or the URL pre-filter rejects the URL. Caller is responsible for persisting any
   * prefilter-reject as a failed coverage_item.
   */
  public Optional<V12Outcome> extract(
      String url,
      String outletName,
      String subjectName,
      String articleText,
      ClientContext context) {
    if (!isEnabled()) return Optional.empty();

    var rejected = urlPrefilter.reject(url);
    if (rejected.isPresent()) {
      log.info("extraction_v12: prefilter rejected url={} reason={}", url, rejected.get());
      return Optional.of(new V12Outcome(null, "extraction_v1.2", null, rejected.get()));
    }

    PromptTemplate t = prompts.get("extraction-v1-2");
    String version = t.version();

    // Cache hit — same content + same prompt version → reuse the cached extraction.
    String contentHash = ExtractionCacheRepository.hashContent(articleText);
    var hit = cache.find(contentHash, version);
    if (hit.isPresent()) {
      try {
        ExtractionResult r = ExtractionSchema.validate(hit.get().jsonResult());
        log.info(
            "extraction_v12: cache hit hash={} version={}", contentHash.substring(0, 8), version);
        return Optional.of(new V12Outcome(r, version, TieredLLM.Tier.HAIKU, null));
      } catch (Exception e) {
        log.warn("extraction_v12: cached entry failed validation, re-running: {}", e.toString());
      }
    }

    String truncated = AnthropicClient.truncateForTokenBudget(articleText, MAX_INPUT_TOKENS);
    boolean useContext = context != null && !context.isEmpty();
    Map<String, String> vars =
        Map.of(
            "url", safe(url),
            "outlet_name", safe(outletName),
            "subject_name", safe(subjectName),
            "article_text", truncated,
            "client_context",
                useContext ? ExtractionService.renderClientContext(context, subjectName) : "");
    String renderedHaiku = t.render(vars);

    String haikuModel = haikuModelOverride.isBlank() ? t.model() : haikuModelOverride;
    AnthropicClient.Result haikuRaw;
    try {
      haikuRaw = anthropic.call(haikuModel, t.temperature(), t.maxTokens(), renderedHaiku);
    } catch (RuntimeException e) {
      log.warn("extraction_v12: haiku call failed: {}", e.toString());
      throw e;
    }

    // Haiku-tier acceptance: schema valid AND confidence ∈ {high, medium}. Otherwise escalate.
    String escalateReason;
    try {
      var tiered = ExtractionV12Schema.parseStrict(haikuRaw.text());
      if ("high".equals(tiered.confidence()) || "medium".equals(tiered.confidence())) {
        saveToCache(contentHash, version, haikuModel, tiered.result(), haikuRaw);
        log.info("extraction_v12: accepted at haiku confidence={}", tiered.confidence());
        return Optional.of(new V12Outcome(tiered.result(), version, TieredLLM.Tier.HAIKU, null));
      }
      escalateReason = "haiku_confidence_low";
    } catch (ExtractionSchema.ValidationException e) {
      escalateReason = "haiku_schema_invalid: " + e.getMessage();
    }

    // Sonnet escalation tier. PromptLoader exposes only the first fenced block, so we reuse the
    // rendered Haiku body and prepend the escalation context (matches the spec in
    // prompts/extraction-v1-2.md "Prompt — Sonnet escalation tier" without requiring loader
    // changes).
    String renderedSonnet =
        "The first-pass extraction returned low confidence with this rationale:\n"
            + escalateReason
            + "\n\nRe-extract with full attention.\n\n"
            + renderedHaiku;
    String sonnetModel =
        sonnetModelOverride.isBlank() ? DEFAULT_ESCALATION_MODEL : sonnetModelOverride;
    AnthropicClient.Result sonnetRaw =
        anthropic.call(sonnetModel, t.temperature(), t.maxTokens(), renderedSonnet);
    var tiered = ExtractionV12Schema.parseStrict(sonnetRaw.text());
    saveToCache(contentHash, version, sonnetModel, tiered.result(), sonnetRaw);
    log.info("extraction_v12: escalated to sonnet reason={}", escalateReason);
    return Optional.of(new V12Outcome(tiered.result(), version, TieredLLM.Tier.SONNET, null));
  }

  private void saveToCache(
      String contentHash,
      String version,
      String model,
      ExtractionResult r,
      AnthropicClient.Result raw) {
    try {
      String jsonText = json.writeValueAsString(r);
      cache.save(
          contentHash,
          version,
          model,
          jsonText,
          raw.inputTokens(),
          raw.outputTokens(),
          raw.costUsd());
    } catch (Exception e) {
      log.warn("extraction_v12: failed to cache result: {}", e.toString());
    }
  }

  private static String safe(String s) {
    return s == null ? "unknown" : s;
  }
}
