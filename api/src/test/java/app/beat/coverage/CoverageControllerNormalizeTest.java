package app.beat.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageControllerNormalizeTest {

  @Test
  void splitsOnWhitespaceCommasAndNewlines() {
    var out =
        CoverageController.normalize(
            List.of("https://a.example/1, https://b.example/2\nhttps://c.example/3"));
    assertThat(out)
        .containsExactly("https://a.example/1", "https://b.example/2", "https://c.example/3");
  }

  @Test
  void dedupesAndDropsBadEntries() {
    var out =
        CoverageController.normalize(
            List.of(
                "https://a.example/1",
                "https://a.example/1", // dup
                "ftp://nope.example",
                "not-a-url",
                "  https://b.example/2  "));
    assertThat(out).containsExactly("https://a.example/1", "https://b.example/2");
  }
}
