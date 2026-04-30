package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExtractionSchemaTest {

  private static final String GOOD =
      """
      {
        "headline": "Acme raises $30M Series B",
        "subheadline": null,
        "author": "Sarah Perez",
        "publish_date": "2025-12-04",
        "lede": "Acme Corp announced a $30M Series B led by Sequoia today.",
        "summary": "Acme raised $30M led by Sequoia to expand its enterprise tier.",
        "key_quote": "We're going to use this to ship faster.",
        "sentiment": "positive",
        "sentiment_rationale": "Funding and growth narrative.",
        "subject_prominence": "feature",
        "topics": ["funding"]
      }
      """;

  @Test
  void parsesValidJson() {
    var r = ExtractionSchema.parseStrict(GOOD);
    assertThat(r.headline()).startsWith("Acme");
    assertThat(r.sentiment()).isEqualTo("positive");
    assertThat(r.subjectProminence()).isEqualTo("feature");
    assertThat(r.topics()).containsExactly("funding");
    assertThat(r.publishDate().toString()).isEqualTo("2025-12-04");
  }

  @Test
  void toleratesProseAroundJson() {
    var r =
        ExtractionSchema.parseStrict(
            "Sure, here's the JSON:\n" + GOOD + "\nLet me know if you need more.");
    assertThat(r.sentiment()).isEqualTo("positive");
  }

  @Test
  void rejectsBadSentimentEnum() {
    String bad = GOOD.replace("\"positive\"", "\"happy\"");
    assertThatThrownBy(() -> ExtractionSchema.parseStrict(bad))
        .isInstanceOf(ExtractionSchema.ValidationException.class)
        .hasMessageContaining("sentiment");
  }

  @Test
  void rejectsMissingRequiredField() {
    String bad = GOOD.replace("\"headline\": \"Acme raises $30M Series B\",", "");
    assertThatThrownBy(() -> ExtractionSchema.parseStrict(bad))
        .isInstanceOf(ExtractionSchema.ValidationException.class)
        .hasMessageContaining("headline");
  }

  @Test
  void rejectsNonIsoDate() {
    String bad = GOOD.replace("\"2025-12-04\"", "\"December 4, 2025\"");
    assertThatThrownBy(() -> ExtractionSchema.parseStrict(bad))
        .isInstanceOf(ExtractionSchema.ValidationException.class)
        .hasMessageContaining("publish_date");
  }

  @Test
  void rejectsEmptyTopics() {
    String bad = GOOD.replace("[\"funding\"]", "[]");
    assertThatThrownBy(() -> ExtractionSchema.parseStrict(bad))
        .isInstanceOf(ExtractionSchema.ValidationException.class)
        .hasMessageContaining("topics");
  }
}
