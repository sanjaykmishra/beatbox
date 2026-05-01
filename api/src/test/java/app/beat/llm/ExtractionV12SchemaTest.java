package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExtractionV12SchemaTest {

  private static final String HIGH =
      """
      {
        "headline": "Acme raises Series B",
        "subheadline": null,
        "author": "Sarah Perez",
        "publish_date": "2026-04-01",
        "lede": "Acme Corp announced a $30M Series B led by Foo Ventures.",
        "summary": "Acme closed a $30M Series B led by Foo Ventures. Customer base reached 5000.",
        "key_quote": null,
        "sentiment": "positive",
        "sentiment_rationale": "Funding announcement framed as growth.",
        "subject_prominence": "feature",
        "topics": ["funding"],
        "confidence": "high"
      }
      """;

  @Test
  void parsesConfidenceFieldVerbatim() {
    var t = ExtractionV12Schema.parseStrict(HIGH);
    assertThat(t.confidence()).isEqualTo("high");
    assertThat(t.result().headline()).isEqualTo("Acme raises Series B");
  }

  @Test
  void coercesUnknownConfidenceToLow() {
    String missing = HIGH.replace("\"confidence\": \"high\"", "\"confidence\": \"unsure\"");
    assertThat(ExtractionV12Schema.parseStrict(missing).confidence()).isEqualTo("low");
  }

  @Test
  void treatsAbsentConfidenceAsLow() {
    // Drop the entire confidence line including the comma+newline before it. Whitespace inside the
    // text-block is normalized at compile time, so match the visible characters without indent.
    String absent = HIGH.replaceAll("(?s),\\s*\"confidence\":\\s*\"high\"", "");
    assertThat(ExtractionV12Schema.parseStrict(absent).confidence()).isEqualTo("low");
  }

  @Test
  void rejectsMissingRequiredFields() {
    String broken = HIGH.replace("\"headline\": \"Acme raises Series B\",", "");
    assertThatThrownBy(() -> ExtractionV12Schema.parseStrict(broken))
        .isInstanceOf(ExtractionSchema.ValidationException.class);
  }
}
