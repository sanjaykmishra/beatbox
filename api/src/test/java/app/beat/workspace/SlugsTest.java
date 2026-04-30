package app.beat.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

  @Test
  void normalizesNamesIntoSlugs() {
    assertThat(Slugs.fromName("Hayworth PR")).isEqualTo("hayworth-pr");
    assertThat(Slugs.fromName("  ACME & Co.  ")).isEqualTo("acme-co");
    assertThat(Slugs.fromName("--weird---name__")).isEqualTo("weird-name");
  }

  @Test
  void emptyNameFallsBackToWorkspace() {
    assertThat(Slugs.fromName("!!!")).isEqualTo("workspace");
  }

  @Test
  void truncatesLongNames() {
    String s = Slugs.fromName("a".repeat(200));
    assertThat(s).hasSize(48);
  }

  @Test
  void suffixedAddsRandomDigits() {
    String s = Slugs.suffixed("acme");
    assertThat(s).startsWith("acme-").matches("acme-\\d{4}");
  }
}
