package app.beat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import app.beat.llm.HyperboleDetector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Eval coverage for the executive summary path.
 *
 * <p>The hyperbole gate is enforced offline (regex); fact-coverage / hallucination via LLM judge
 * happens in the live eval, gated on ANTHROPIC_API_KEY + -Dbeat.eval.live=true (see
 * ExtractionEvalTest for the same gating).
 *
 * <p>The reviewer rubric (api/src/test/eval/summary-rubric.yaml, per docs/06-evals.md and
 * prompts/executive-summary-v1-1.md §"Eval set") anchors the v1.0 → v1.1 migration. Offline gates
 * here:
 *
 * <ul>
 *   <li>every reference summary is hyperbole-free
 *   <li>every reference summary is 250–400 words
 *   <li>every reference summary is exactly three paragraphs
 *   <li>every reference summary contains all {@code must_include_phrases} and none of {@code
 *       must_not_include}
 * </ul>
 *
 * <p>Together they guarantee the rubric stays a reliable yardstick — if any reference falls out of
 * spec, the live LLM-as-judge comparison can't be trusted either.
 */
public class SummaryEvalTest {

  @Test
  void hyperboleDetectorCatchesForbiddenWords() {
    String bad =
        "This was a groundbreaking month for the brand. The team delivered a tremendous, "
            + "unprecedented set of placements that were truly revolutionary.";
    List<String> hits = HyperboleDetector.findViolations(bad);
    assertThat(hits)
        .containsAnyOf("groundbreaking", "tremendous", "unprecedented", "revolutionary");
    assertThat(hits.size()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void hyperboleDetectorPassesNeutralCopy() {
    String good =
        "This month delivered fourteen pieces of coverage across nine outlets, including three "
            + "tier-1 placements. The dominant theme was the company's funding announcement.";
    assertThat(HyperboleDetector.findViolations(good)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void reviewerRubricMeetsItsOwnGates() throws IOException {
    var entries = loadRubric();
    assertThat(entries).hasSizeGreaterThanOrEqualTo(3);

    for (Map<String, Object> e : entries) {
      String id = (String) e.get("id");
      String summary = (String) e.get("reference_summary");
      assertThat(summary).as("reference_summary for %s", id).isNotBlank();

      // Hyperbole gate.
      List<String> hits = HyperboleDetector.findViolations(summary);
      assertThat(hits).as("hyperbole hits in %s", id).isEmpty();

      // Word-count gate (250–400).
      int words = summary.trim().split("\\s+").length;
      assertThat(words)
          .as("word count in %s", id)
          .isGreaterThanOrEqualTo(250)
          .isLessThanOrEqualTo(400);

      // Three-paragraph gate (paragraphs separated by a blank line).
      String[] paragraphs = summary.trim().split("\\n\\s*\\n");
      assertThat(paragraphs).as("paragraph count in %s", id).hasSize(3);

      // Must-include / must-not-include phrase gates.
      Object include = e.get("must_include_phrases");
      if (include instanceof List<?> list) {
        for (Object phrase : list) {
          assertThat(summary)
              .as("must_include_phrases for %s missing %s", id, phrase)
              .contains((String) phrase);
        }
      }
      Object exclude = e.get("must_not_include");
      if (exclude instanceof List<?> list) {
        for (Object phrase : list) {
          assertThat(summary.toLowerCase())
              .as("must_not_include for %s contains %s", id, phrase)
              .doesNotContain(((String) phrase).toLowerCase());
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> loadRubric() throws IOException {
    Path rubric = EvalRunner.defaultEvalDir().resolve("summary-rubric.yaml");
    if (Files.exists(rubric)) {
      try (InputStream in = Files.newInputStream(rubric)) {
        return (List<Map<String, Object>>) new Yaml().load(in);
      }
    }
    // Fallback: load from classpath in case the project layout shifts.
    try (InputStream in = SummaryEvalTest.class.getResourceAsStream("/eval/summary-rubric.yaml")) {
      assertThat(in).as("summary-rubric.yaml on classpath").isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return (List<Map<String, Object>>) new Yaml().load(yaml);
    }
  }
}
