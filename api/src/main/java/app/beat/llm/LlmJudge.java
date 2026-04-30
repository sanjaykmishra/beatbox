package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Opus-as-judge for summary fact coverage and hallucination per docs/06-evals.md §LLM-as-judge.
 * Strictly returns:
 *
 * <pre>
 * { "facts_covered": int, "facts_total": int,
 *   "forbidden_violations": [...], "rationale": "..." }
 * </pre>
 */
@Service
public class LlmJudge {

  private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
  private static final String JUDGE_MODEL_DEFAULT = "claude-opus";

  private final AnthropicClient anthropic;
  private final ObjectMapper json = new ObjectMapper();
  private final String modelOverride;

  public LlmJudge(
      AnthropicClient anthropic, @Value("${ANTHROPIC_MODEL_SUMMARY:}") String modelOverride) {
    this.anthropic = anthropic;
    this.modelOverride = modelOverride;
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  public record Judgement(
      int factsCovered, int factsTotal, List<String> forbiddenViolations, String rationale) {}

  public Judgement judge(
      String summary, List<String> mustIncludeFacts, List<String> mustNotInclude) {
    if (!isEnabled()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
    String prompt =
        """
        You are evaluating a summary against a known set of facts. Be strict.

        Original facts (must all appear in the summary, possibly paraphrased):
        %s

        Forbidden content (must NOT appear in any form):
        %s

        Generated summary:
        %s

        Return JSON:
        { "facts_covered": <int 0..N>,
          "facts_total": <int N>,
          "forbidden_violations": [<list of any forbidden items found, with verbatim quotes>],
          "rationale": "one paragraph" }

        Return ONLY the JSON object, no surrounding text.
        """
            .formatted(bullet(mustIncludeFacts), bullet(mustNotInclude), summary);
    String model = modelOverride.isBlank() ? JUDGE_MODEL_DEFAULT : modelOverride;
    AnthropicClient.Result r = anthropic.call(model, 0.0, 800, prompt);
    return parse(r.text(), mustIncludeFacts == null ? 0 : mustIncludeFacts.size());
  }

  private Judgement parse(String text, int defaultTotal) {
    try {
      String s = text == null ? "" : text.trim();
      int start = s.indexOf('{');
      int end = s.lastIndexOf('}');
      if (start < 0 || end <= start) {
        log.warn("judge: no JSON in response");
        return new Judgement(0, defaultTotal, List.of(), "judge returned no JSON");
      }
      JsonNode node = json.readTree(s.substring(start, end + 1));
      List<String> hits = new ArrayList<>();
      JsonNode arr = node.get("forbidden_violations");
      if (arr != null && arr.isArray()) {
        for (JsonNode v : arr) hits.add(v.asText());
      }
      return new Judgement(
          node.path("facts_covered").asInt(0),
          node.path("facts_total").asInt(defaultTotal),
          hits,
          node.path("rationale").asText(""));
    } catch (Exception e) {
      log.warn("judge: parse failed: {}", e.toString());
      return new Judgement(0, defaultTotal, List.of(), "judge parse failed: " + e.getMessage());
    }
  }

  private static String bullet(List<String> items) {
    if (items == null || items.isEmpty()) return "(none)";
    StringBuilder b = new StringBuilder();
    for (String s : items) b.append("- ").append(s).append('\n');
    return b.toString().trim();
  }
}
