package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptLoaderTest {

  private static final String GOOD =
      """
      ---
      version: extraction_v1.0
      model: claude-sonnet-4
      temperature: 0.1
      max_tokens: 1500
      ---
      # Some prompt

      ```
      Hello {{name}}, the article is at {{url}}.
      ```

      Notes after the fence are ignored.
      """;

  @Test
  void parsesFrontmatterAndBody() {
    PromptTemplate t = PromptLoader.parse(GOOD);
    assertThat(t.version()).isEqualTo("extraction_v1.0");
    assertThat(t.model()).isEqualTo("claude-sonnet-4");
    assertThat(t.temperature()).isEqualTo(0.1);
    assertThat(t.maxTokens()).isEqualTo(1500);
    assertThat(t.body()).startsWith("Hello {{name}}");
  }

  @Test
  void substitutesPlaceholders() {
    PromptTemplate t = PromptLoader.parse(GOOD);
    String out = t.render(Map.of("name", "Alex", "url", "https://x"));
    assertThat(out).isEqualTo("Hello Alex, the article is at https://x.");
  }

  @Test
  void missingFrontmatterFails() {
    assertThatThrownBy(() -> PromptLoader.parse("no frontmatter"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingFenceFails() {
    String bad =
        """
        ---
        version: x_v1
        model: claude-sonnet-4
        ---
        no fence here
        """;
    assertThatThrownBy(() -> PromptLoader.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("template body");
  }
}
