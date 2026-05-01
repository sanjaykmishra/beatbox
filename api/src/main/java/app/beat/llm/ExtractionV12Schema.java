package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * Strict parser for prompts/extraction-v1-2.md output. Adds a {@code confidence} field on top of
 * the v1.0/v1.1 schema; otherwise identical. The orchestrator inspects {@link Tiered#confidence()}
 * to decide whether to escalate from Haiku to Sonnet.
 *
 * <p>The confidence field is parser-internal — once a result is committed to {@code
 * coverage_items}, only the {@link ExtractionResult} fields are persisted. Confidence is a routing
 * signal, not a downstream attribute.
 */
public final class ExtractionV12Schema {

  public static final Set<String> CONFIDENCE = Set.of("high", "medium", "low");

  private ExtractionV12Schema() {}

  /**
   * A v1.2 extraction parsed result: the strictly-validated body + the model's self-assessed
   * confidence.
   */
  public record Tiered(ExtractionResult result, String confidence) {}

  public static Tiered parseStrict(String text) {
    if (text == null) throw new ExtractionSchema.ValidationException("empty response");
    String trimmed = text.trim();
    int start = trimmed.indexOf('{');
    int end = trimmed.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new ExtractionSchema.ValidationException("no JSON object found");
    }
    String json = trimmed.substring(start, end + 1);
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new ExtractionSchema.ValidationException("invalid JSON: " + e.getMessage());
    }
    ExtractionResult result = ExtractionSchema.validate(root);

    JsonNode confNode = root.get("confidence");
    String confidence;
    if (confNode == null || confNode.isNull() || !confNode.isTextual()) {
      // v1.2 prompt requires confidence; absence is a schema violation worth escalating on.
      confidence = "low";
    } else {
      String c = confNode.asText().trim().toLowerCase();
      confidence = CONFIDENCE.contains(c) ? c : "low";
    }
    return new Tiered(result, confidence);
  }
}
