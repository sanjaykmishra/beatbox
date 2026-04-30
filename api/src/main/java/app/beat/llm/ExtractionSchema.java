package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strict validator + parser for prompts/extraction-v1.md output. The schema definition lives here
 * (Java) and the prompt's documented schema is mirrored. A test asserts they don't drift.
 */
public final class ExtractionSchema {

  public static final Set<String> SENTIMENT = Set.of("positive", "neutral", "negative", "mixed");
  public static final Set<String> PROMINENCE = Set.of("feature", "mention", "passing");

  private ExtractionSchema() {}

  public static class ValidationException extends RuntimeException {
    public ValidationException(String msg) {
      super(msg);
    }
  }

  /**
   * Strip any leading/trailing prose and parse the first JSON object found. Throws on parse or
   * structural errors.
   */
  public static ExtractionResult parseStrict(String text) {
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

  static ExtractionResult validate(JsonNode root) {
    if (!root.isObject()) throw new ValidationException("root must be an object");

    String headline = stringRequired(root, "headline");
    String subheadline = stringNullable(root, "subheadline");
    String author = stringNullable(root, "author");
    LocalDate publishDate = dateNullable(root, "publish_date");
    String lede = stringRequired(root, "lede");
    String summary = stringRequired(root, "summary");
    String keyQuote = stringNullable(root, "key_quote");
    String sentiment = enumRequired(root, "sentiment", SENTIMENT);
    String sentimentRationale = stringRequired(root, "sentiment_rationale");
    String subjectProminence = enumRequired(root, "subject_prominence", PROMINENCE);

    JsonNode topicsNode = root.get("topics");
    if (topicsNode == null || !topicsNode.isArray())
      throw new ValidationException("topics must be an array");
    List<String> topics = new ArrayList<>();
    for (JsonNode t : topicsNode) {
      if (!t.isTextual()) throw new ValidationException("topics entries must be strings");
      topics.add(t.asText());
    }
    if (topics.isEmpty()) throw new ValidationException("topics must have at least 1 entry");
    if (topics.size() > 8) throw new ValidationException("topics has too many entries");

    return new ExtractionResult(
        headline,
        subheadline,
        author,
        publishDate,
        lede,
        summary,
        keyQuote,
        sentiment,
        sentimentRationale,
        subjectProminence,
        topics);
  }

  private static String stringRequired(JsonNode root, String field) {
    JsonNode n = root.get(field);
    if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank())
      throw new ValidationException("missing required field: " + field);
    return n.asText();
  }

  private static String stringNullable(JsonNode root, String field) {
    JsonNode n = root.get(field);
    if (n == null || n.isNull()) return null;
    if (!n.isTextual()) throw new ValidationException("field " + field + " must be string or null");
    String s = n.asText();
    return s.isBlank() ? null : s;
  }

  private static LocalDate dateNullable(JsonNode root, String field) {
    String s = stringNullable(root, field);
    if (s == null) return null;
    try {
      return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    } catch (Exception e) {
      throw new ValidationException("field " + field + " not ISO-8601: " + s);
    }
  }

  private static String enumRequired(JsonNode root, String field, Set<String> allowed) {
    String s = stringRequired(root, field);
    if (!allowed.contains(s))
      throw new ValidationException(
          "field " + field + " must be one of " + allowed + ", got: " + s);
    return s;
  }
}
