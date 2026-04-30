package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

  private static PromptTemplate t(String body) {
    return new PromptTemplate("test", "claude-sonnet", 0.0, 100, body);
  }

  @Test
  void plainVariableSubstitution() {
    assertThat(t("Hello {{name}}!").render(Map.of("name", "Alex"))).isEqualTo("Hello Alex!");
    assertThat(t("Hello {{name}}!").render(Map.of())).isEqualTo("Hello !");
  }

  @Test
  void ifBlockKeptWhenTruthy() {
    String body = "X{{#if note}}NOTE: {{note}}{{/if}}Y";
    assertThat(t(body).render(Map.of("note", "hi"))).isEqualTo("XNOTE: hiY");
  }

  @Test
  void ifBlockRemovedWhenMissingOrEmpty() {
    String body = "X{{#if note}}NOTE: {{note}}{{/if}}Y";
    assertThat(t(body).render(Map.of())).isEqualTo("XY");
    assertThat(t(body).render(Map.of("note", ""))).isEqualTo("XY");
    assertThat(t(body).render(Map.of("note", "false"))).isEqualTo("XY");
  }

  @Test
  void ifBlockMatchingMatchesPostVariantPrompt() {
    String body =
        "Client: {{client_name}}\n"
            + "{{#if client_style_notes}}\nClient style: {{client_style_notes}}\n{{/if}}\n"
            + "{{#if series_tag}}\nSeries: {{series_tag}}\n{{/if}}";
    String out =
        t(body)
            .render(
                Map.of(
                    "client_name", "Acme",
                    "client_style_notes", "CEO is Mike, never Michael.",
                    "series_tag", ""));
    assertThat(out).contains("Client: Acme");
    assertThat(out).contains("CEO is Mike");
    assertThat(out).doesNotContain("Series:");
    assertThat(out).doesNotContain("{{");
  }
}
