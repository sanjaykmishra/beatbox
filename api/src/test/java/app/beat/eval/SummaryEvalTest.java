package app.beat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import app.beat.llm.HyperboleDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Eval coverage for the executive summary path. The hyperbole gate is enforced offline (regex);
 * fact-coverage / hallucination via LLM judge happens in the live eval, gated on ANTHROPIC_API_KEY
 * + -Dbeat.eval.live=true (see ExtractionEvalTest for the same gating).
 *
 * <p>For the no-API-key path we exercise the detector against a known-bad and known-good sample so
 * a regression in the forbidden-word list is caught immediately.
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
}
