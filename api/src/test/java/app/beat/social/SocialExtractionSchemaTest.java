package app.beat.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Locks in the wire-level support for {@code subject_prominence = "missing"} added in
 * social-extraction-v1.1. Without this test a regression that drops 'missing' from the schema (e.g.
 * a stale Set replaced) would silently fall back to throwing on every off-topic post, which the
 * worker then catches and retries — easy to miss in a code review.
 */
class SocialExtractionSchemaTest {

  private static String good(String prominence) {
    return """
        {
          "summary": "Reddit thread discussing barbecue restaurants in Austin without naming the subject.",
          "key_excerpt": null,
          "sentiment": "neutral",
          "sentiment_rationale": "subject not mentioned in post.",
          "subject_prominence": "%s",
          "topics": ["amplification"],
          "is_amplification": false,
          "media_summary": null
        }
        """
        .formatted(prominence);
  }

  @Test
  void acceptsMissingProminence() {
    var r = SocialExtractionSchema.parseStrict(good("missing"));
    assertThat(r.subjectProminence()).isEqualTo("missing");
    assertThat(r.sentiment()).isEqualTo("neutral");
    assertThat(r.keyExcerpt()).isNull();
    assertThat(r.isAmplification()).isFalse();
  }

  @Test
  void acceptsAllFourProminenceValues() {
    for (String p : new String[] {"feature", "mention", "passing", "missing"}) {
      assertThat(SocialExtractionSchema.parseStrict(good(p)).subjectProminence()).isEqualTo(p);
    }
  }

  @Test
  void rejectsUnknownProminence() {
    assertThatThrownBy(() -> SocialExtractionSchema.parseStrict(good("absent")))
        .isInstanceOf(SocialExtractionSchema.ValidationException.class)
        .hasMessageContaining("subject_prominence");
  }
}
