package app.beat.social;

import app.beat.clientcontext.ClientContext;
import app.beat.llm.AnthropicClient;
import app.beat.llm.PromptLoader;
import app.beat.llm.PromptTemplate;
import app.beat.social.fetchers.FetchedSocialPost;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps the social-mention LLM extraction call: render prompt → Anthropic → strict schema
 * validation. Returns the parsed result + the prompt version stamped on the social_mentions row
 * (audit trail across re-runs, mirrors {@code app.beat.llm.ExtractionService}).
 */
@Service
public class SocialExtractionService {

  private static final Logger log = LoggerFactory.getLogger(SocialExtractionService.class);

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final ObjectMapper json = new ObjectMapper();
  private final String modelOverride;
  private final String promptStem;

  public SocialExtractionService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      @Value("${ANTHROPIC_MODEL_SOCIAL_EXTRACTION:}") String modelOverride,
      @Value("${beat.prompts.social-extraction.version:v1_1}") String version) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.modelOverride = modelOverride;
    this.promptStem = resolvePromptStem(version);
    log.info("SocialExtractionService prompt = {}", this.promptStem);
  }

  /** {@code v1_0} → legacy 3-value enum; {@code v1_1} → adds {@code missing}. */
  private static String resolvePromptStem(String configured) {
    if (configured == null) return "social-extraction-v1-1";
    return switch (configured.trim().toLowerCase()) {
      case "v1_0", "social-extraction-v1", "social_extraction_v1.0" -> "social-extraction-v1";
      case "v1_1", "social-extraction-v1-1", "social_extraction_v1.1" -> "social-extraction-v1-1";
      default -> "social-extraction-v1-1";
    };
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  public record Outcome(
      SocialExtractionSchema.Result result, String promptVersion, String rawJson) {}

  public Optional<Outcome> extract(
      FetchedSocialPost post, String subjectName, ClientContext context) {
    if (!isEnabled()) return Optional.empty();
    PromptTemplate t = prompts.get(promptStem);

    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("platform", safe(post.platform()));
    vars.put("url", safe(post.externalPostId()));
    vars.put("author_handle", safe(post.authorHandle()));
    vars.put(
        "author_display_name", post.authorDisplayName() == null ? "" : post.authorDisplayName());
    vars.put(
        "author_follower_count",
        post.authorFollowerCount() == null ? "unknown" : post.authorFollowerCount().toString());
    vars.put("author_bio", post.authorBio() == null ? "" : post.authorBio());
    vars.put("posted_at", post.postedAt() == null ? "" : post.postedAt().toString());
    vars.put("subject_name", subjectName == null ? "the subject" : subjectName);
    vars.put(
        "client_context",
        context == null || context.isEmpty()
            ? ""
            : app.beat.llm.ExtractionService.renderClientContext(context, subjectName));
    vars.put("post_text", safe(post.contentText()));
    vars.put("is_reply", String.valueOf(post.isReply()));
    vars.put("is_quote", String.valueOf(post.isQuote()));
    vars.put(
        "parent_post_text",
        post.parentPostText() == null ? "" : truncate(post.parentPostText(), 500));
    vars.put("has_media", String.valueOf(post.hasMedia()));
    vars.put(
        "media_descriptions",
        post.mediaDescriptions() == null || post.mediaDescriptions().isEmpty()
            ? ""
            : String.join("; ", post.mediaDescriptions()));
    vars.put("engagement_likes", post.likesCount() == null ? "0" : post.likesCount().toString());
    vars.put(
        "engagement_reposts", post.repostsCount() == null ? "0" : post.repostsCount().toString());
    vars.put(
        "engagement_replies", post.repliesCount() == null ? "0" : post.repliesCount().toString());
    vars.put("engagement_views", post.viewsCount() == null ? "" : post.viewsCount().toString());

    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result first;
    try {
      first = anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), rendered);
    } catch (RuntimeException e) {
      log.warn("social_extraction: anthropic call failed: {}", e.toString());
      throw e;
    }

    try {
      var parsed = SocialExtractionSchema.parseStrict(first.text());
      return Optional.of(new Outcome(parsed, t.version(), normalizeJson(first.text())));
    } catch (SocialExtractionSchema.ValidationException badJson) {
      log.warn("social_extraction: invalid JSON, re-prompting once: {}", badJson.getMessage());
    }

    String reprompt =
        rendered + "\n\nYour previous response was not valid JSON. Return ONLY the JSON object.";
    AnthropicClient.Result second =
        anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), reprompt);
    var parsed = SocialExtractionSchema.parseStrict(second.text());
    return Optional.of(new Outcome(parsed, t.version(), normalizeJson(second.text())));
  }

  /** Strip leading/trailing prose from the model response so we can persist clean JSON. */
  private static String normalizeJson(String raw) {
    if (raw == null) return "{}";
    String s = raw.trim();
    int open = s.indexOf('{');
    int close = s.lastIndexOf('}');
    if (open < 0 || close <= open) return "{}";
    return s.substring(open, close + 1);
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String truncate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }
}
