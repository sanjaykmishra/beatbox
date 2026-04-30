package app.beat.llm;

import app.beat.clientcontext.ClientContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps the LLM extraction call: render prompt → Anthropic → strict schema validation, with a
 * (content_hash, prompt_version) idempotency cache and a single re-prompt on JSON failure.
 *
 * <p>Returns the parsed result plus the prompt version used so the worker can stamp it onto the
 * coverage_item row (audit trail across re-runs).
 */
@Service
public class ExtractionService {

  private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
  private static final int MAX_INPUT_TOKENS = 8000;

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final ExtractionCacheRepository cache;
  private final ObjectMapper json = new ObjectMapper();
  private final String modelOverride;

  public ExtractionService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      ExtractionCacheRepository cache,
      @Value("${ANTHROPIC_MODEL_EXTRACTION:}") String modelOverride) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.cache = cache;
    this.modelOverride = modelOverride;
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  public record Outcome(ExtractionResult result, String promptVersion) {}

  public Optional<Outcome> extract(
      String url,
      String outletName,
      String subjectName,
      String articleText,
      ClientContext context) {
    if (!isEnabled()) return Optional.empty();
    // Use v1.1 when context is available, v1 otherwise. The cache is keyed on prompt version,
    // so a context add doesn't pollute the no-context cache.
    boolean useContext = context != null && !context.isEmpty();
    PromptTemplate t = prompts.get(useContext ? "extraction-v1-1" : "extraction-v1");
    String version = t.version();

    String contentHash = ExtractionCacheRepository.hashContent(articleText);
    var hit = cache.find(contentHash, version);
    if (hit.isPresent()) {
      try {
        ExtractionResult r = ExtractionSchema.validate(hit.get().jsonResult());
        log.info("extraction: cache hit for {} (version {})", contentHash.substring(0, 8), version);
        return Optional.of(new Outcome(r, version));
      } catch (Exception e) {
        log.warn("extraction: cached entry failed validation, re-running: {}", e.toString());
      }
    }

    String truncated = AnthropicClient.truncateForTokenBudget(articleText, MAX_INPUT_TOKENS);
    Map<String, String> vars =
        new java.util.HashMap<>(
            Map.of(
                "url", safe(url),
                "outlet_name", safe(outletName),
                "subject_name", safe(subjectName),
                "article_text", truncated));
    if (useContext) {
      vars.put("client_context", renderClientContext(context, subjectName));
    }
    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result first;
    try {
      first = anthropic.call(model, t.temperature(), t.maxTokens(), rendered);
    } catch (RuntimeException e) {
      log.warn("extraction: anthropic call failed: {}", e.toString());
      throw e;
    }
    try {
      ExtractionResult r = ExtractionSchema.parseStrict(first.text());
      saveToCache(contentHash, version, model, r, first);
      return Optional.of(new Outcome(r, version));
    } catch (ExtractionSchema.ValidationException badJson) {
      log.warn("extraction: invalid JSON, re-prompting once: {}", badJson.getMessage());
    }

    // Single retry per docs/05: ask the model to return ONLY the JSON object.
    String reprompt =
        rendered + "\n\nYour previous response was not valid JSON. Return ONLY the JSON object.";
    AnthropicClient.Result second = anthropic.call(model, t.temperature(), t.maxTokens(), reprompt);
    ExtractionResult r2 = ExtractionSchema.parseStrict(second.text());
    saveToCache(contentHash, version, model, r2, second);
    return Optional.of(new Outcome(r2, version));
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
      log.warn("extraction: failed to cache result: {}", e.toString());
    }
  }

  private static String safe(String s) {
    return s == null ? "unknown" : s;
  }

  /**
   * Per docs/15-additions.md §15.1, deliberately excludes do_not_pitch and important_dates from the
   * extraction prompt — those are agency-internal and would bias sentiment.
   */
  static String renderClientContext(ClientContext c, String subjectName) {
    StringBuilder b = new StringBuilder();
    b.append("Relevant context about ").append(subjectName).append(":\n");
    if (c.keyMessages() != null && !c.keyMessages().isBlank()) {
      b.append("- Key messages: ").append(c.keyMessages().trim()).append('\n');
    }
    if (c.styleNotes() != null && !c.styleNotes().isBlank()) {
      b.append("- Style notes (preferred names/spellings): ")
          .append(c.styleNotes().trim())
          .append('\n');
    }
    if (c.competitiveSet() != null && !c.competitiveSet().isBlank()) {
      b.append("- Competitive set: ").append(c.competitiveSet().trim()).append('\n');
    }
    if (c.notesMarkdown() != null && !c.notesMarkdown().isBlank()) {
      String excerpt = c.notesMarkdown().trim();
      if (excerpt.length() > 300) excerpt = excerpt.substring(0, 300) + "…";
      b.append("- Recent context: ").append(excerpt).append('\n');
    }
    return b.toString();
  }
}
