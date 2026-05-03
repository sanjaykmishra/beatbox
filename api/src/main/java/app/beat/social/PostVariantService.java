package app.beat.social;

import app.beat.client.Client;
import app.beat.clientcontext.ClientContext;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.llm.AnthropicClient;
import app.beat.llm.PromptLoader;
import app.beat.llm.PromptTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates per-platform variants of an owned post using prompts/post-variant-v1.md. Disabled when
 * ANTHROPIC_API_KEY isn't set, in which case callers fall back to manual entry.
 */
@Service
public class PostVariantService {

  private static final Logger log = LoggerFactory.getLogger(PostVariantService.class);

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final ClientContextRepository contexts;
  private final ObjectMapper json;
  private final String modelOverride;
  private final String promptStem;

  public PostVariantService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      ClientContextRepository contexts,
      ObjectMapper json,
      @Value("${ANTHROPIC_MODEL_VARIANT:}") String modelOverride,
      @Value("${beat.prompts.post-variant.version:v1_1}") String version) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.contexts = contexts;
    this.json = json;
    this.modelOverride = modelOverride;
    this.promptStem = resolvePromptStem(version);
    log.info("PostVariantService prompt = {}", this.promptStem);
  }

  /**
   * {@code v1_0} → legacy single-pass prompt without caching markers; {@code v1_1} →
   * cost-engineered variant with brand-voice + instructions blocks marked for prompt caching (per
   * docs/18-cost-engineering.md §"Social post-variant generation"). Default v1_1.
   */
  private static String resolvePromptStem(String configured) {
    if (configured == null) return "post-variant-v1-1";
    return switch (configured.trim().toLowerCase()) {
      case "v1_0", "post-variant-v1", "post_variant_v1.0" -> "post-variant-v1";
      case "v1_1", "post-variant-v1-1", "post_variant_v1.1" -> "post-variant-v1-1";
      default -> "post-variant-v1-1";
    };
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  public record VariantOutcome(
      Map<String, OwnedPost.PlatformVariant> variants,
      Map<String, List<String>> warnings,
      String promptVersion) {}

  /**
   * Generate variants for the requested platforms. {@code targetPlatforms} should match the
   * platform identifiers used in {@link OwnedPost#platformVariants()}.
   */
  public VariantOutcome generate(
      Client client, String masterContent, List<String> targetPlatforms, String seriesTag) {
    if (!isEnabled()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
    if (targetPlatforms == null || targetPlatforms.isEmpty()) {
      throw new IllegalArgumentException("target_platforms is empty");
    }

    PromptTemplate t = prompts.get(promptStem);
    String styleNotes =
        contexts
            .findByClient(client.id())
            .map(ClientContext::styleNotes)
            .filter(s -> s != null && !s.isBlank())
            .orElse("");

    var vars = new LinkedHashMap<String, String>();
    vars.put("master_content", masterContent == null ? "" : masterContent);
    vars.put("target_platforms", String.join(", ", targetPlatforms));
    vars.put("client_name", client.name());
    vars.put("client_style_notes", styleNotes);
    vars.put("series_tag", seriesTag == null ? "" : seriesTag);
    vars.put("has_media", "false"); // wired up when assets land in Wk4

    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r =
        anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), rendered);

    JsonNode parsed;
    try {
      parsed = json.readTree(extractJsonBlock(r.text()));
    } catch (Exception e) {
      log.warn("post-variant: model returned non-JSON output; falling back to empty variants", e);
      return new VariantOutcome(Map.of(), Map.of(), t.version());
    }
    JsonNode arr = parsed.get("variants");
    if (arr == null || !arr.isArray()) {
      log.warn("post-variant: response missing 'variants' array");
      return new VariantOutcome(Map.of(), Map.of(), t.version());
    }
    var out = new LinkedHashMap<String, OwnedPost.PlatformVariant>();
    var warnings = new HashMap<String, List<String>>();
    for (JsonNode v : arr) {
      String platform = textOrNull(v, "platform");
      String content = textOrNull(v, "content");
      if (platform == null || content == null) continue;
      Integer charCount =
          v.has("char_count") && v.get("char_count").isNumber()
              ? v.get("char_count").asInt()
              : content.length();
      out.put(platform, new OwnedPost.PlatformVariant(content, charCount, Instant.now()));
      JsonNode wArr = v.get("warnings");
      if (wArr != null && wArr.isArray() && wArr.size() > 0) {
        var ws = new ArrayList<String>(wArr.size());
        for (JsonNode w : wArr) ws.add(w.asText());
        warnings.put(platform, ws);
      }
    }
    return new VariantOutcome(out, warnings, t.version());
  }

  private static String textOrNull(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  /**
   * Tolerate models that wrap their JSON in prose or a fenced ```json``` block. Pulls the first
   * top-level {...} substring.
   */
  static String extractJsonBlock(String raw) {
    if (raw == null) return "{}";
    String s = raw.trim();
    if (s.startsWith("```")) {
      int firstNewline = s.indexOf('\n');
      if (firstNewline > 0) s = s.substring(firstNewline + 1);
      int closing = s.lastIndexOf("```");
      if (closing >= 0) s = s.substring(0, closing);
      s = s.trim();
    }
    int open = s.indexOf('{');
    int close = s.lastIndexOf('}');
    if (open < 0 || close <= open) return "{}";
    return s.substring(open, close + 1);
  }
}
