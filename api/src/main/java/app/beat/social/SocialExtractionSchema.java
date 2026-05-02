package app.beat.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strict validator + parser for prompts/social-extraction-v1.md output. Mirrors the JSON schema
 * documented in that prompt; a regression in schema or prompt must update both sides together.
 */
public final class SocialExtractionSchema {

  public static final Set<String> SENTIMENT = Set.of("positive", "neutral", "negative", "mixed");
  // social-extraction-v1.1 added 'missing' (subject's name does not appear in the post body)
  // for parity with extraction-v1.3 so the runtime guard sees a unified signal across both
  // streams. v1.0 outputs that don't use 'missing' still validate.
  public static final Set<String> PROMINENCE = Set.of("feature", "mention", "passing", "missing");

  private SocialExtractionSchema() {}

  public static class ValidationException extends RuntimeException {
    public ValidationException(String msg) {
      super(msg);
    }
  }

  public record Result(
      String summary,
      String keyExcerpt,
      String sentiment,
      String sentimentRationale,
      String subjectProminence,
      List<String> topics,
      boolean isAmplification,
      String mediaSummary) {}

  public static Result parseStrict(String text) {
    if (text == null) throw new ValidationException("empty response");
    String trimmed = text.trim();
    int start = trimmed.indexOf('{');
    int end = trimmed.lastIndexOf('}');
    if (start < 0 || end <= start) throw new ValidationException("no JSON object found");
    String json = trimmed.substring(start, end + 1);
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new ValidationException("invalid JSON: " + e.getMessage());
    }
    return validate(root);
  }

  static Result validate(JsonNode root) {
    if (!root.isObject()) throw new ValidationException("root must be an object");
    String summary = stringRequired(root, "summary");
    String keyExcerpt = stringNullable(root, "key_excerpt");
    String sentiment = enumRequired(root, "sentiment", SENTIMENT);
    String sentimentRationale = stringRequired(root, "sentiment_rationale");
    String subjectProminence = enumRequired(root, "subject_prominence", PROMINENCE);

    JsonNode topicsNode = root.get("topics");
    if (topicsNode == null || !topicsNode.isArray()) {
      throw new ValidationException("topics must be an array");
    }
    List<String> topics = new ArrayList<>();
    for (JsonNode t : topicsNode) {
      if (!t.isTextual()) throw new ValidationException("topics entries must be strings");
      topics.add(t.asText());
    }
    if (topics.isEmpty()) throw new ValidationException("topics must have at least 1 entry");
    if (topics.size() > 8) throw new ValidationException("topics has too many entries");

    JsonNode amp = root.get("is_amplification");
    if (amp == null || !amp.isBoolean()) {
      throw new ValidationException("is_amplification must be a boolean");
    }
    boolean isAmplification = amp.asBoolean();

    String mediaSummary = stringNullable(root, "media_summary");

    return new Result(
        summary,
        keyExcerpt,
        sentiment,
        sentimentRationale,
        subjectProminence,
        topics,
        isAmplification,
        mediaSummary);
  }

  private static String stringRequired(JsonNode root, String field) {
    JsonNode n = root.get(field);
    if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
      throw new ValidationException("missing required field: " + field);
    }
    return n.asText();
  }

  private static String stringNullable(JsonNode root, String field) {
    JsonNode n = root.get(field);
    if (n == null || n.isNull()) return null;
    if (!n.isTextual()) throw new ValidationException("field " + field + " must be string or null");
    String s = n.asText();
    return s.isBlank() ? null : s;
  }

  private static String enumRequired(JsonNode root, String field, Set<String> allowed) {
    String s = stringRequired(root, field);
    if (!allowed.contains(s)) {
      throw new ValidationException(
          "field " + field + " must be one of " + allowed + ", got: " + s);
    }
    return s;
  }
}
