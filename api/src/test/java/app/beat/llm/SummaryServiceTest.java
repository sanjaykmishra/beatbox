package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SummaryServiceTest {

  @Test
  void normalizeMode_acceptsKnownAndDefaultsToV1() {
    assertThat(SummaryService.normalizeMode("v1")).isEqualTo("v1");
    assertThat(SummaryService.normalizeMode("v1_1")).isEqualTo("v1_1");
    assertThat(SummaryService.normalizeMode("shadow")).isEqualTo("shadow");
    assertThat(SummaryService.normalizeMode("V1_1")).isEqualTo("v1_1");
    assertThat(SummaryService.normalizeMode(null)).isEqualTo("v1");
    assertThat(SummaryService.normalizeMode("nonsense")).isEqualTo("v1");
  }

  @Test
  void splitV11_splitsAtPerReportMarkerAndStripsBracketMarkers() {
    String rendered =
        """
        [CACHED — summary_instructions_block]
        You are writing the summary.
        Constraints: confident, factual.
        [/CACHED]

        [CACHED — workspace_style_block]
        Style guidance: the agency voice.
        [/CACHED]

        [NOT CACHED — per-report]
        Client: Acme (Manufacturing)
        Report period: January 2026

        Coverage data: 6 items, 1 tier-1.
        Write the executive summary.
        """;

    SummaryService.SystemAndUser split = SummaryService.splitV11(rendered);

    assertThat(split.system())
        .contains("You are writing the summary.")
        .contains("Style guidance: the agency voice.")
        .doesNotContain("[CACHED")
        .doesNotContain("[/CACHED")
        .doesNotContain("[NOT CACHED");

    assertThat(split.user())
        .contains("Client: Acme (Manufacturing)")
        .contains("Coverage data: 6 items, 1 tier-1.")
        .contains("Write the executive summary.")
        .doesNotContain("[CACHED")
        .doesNotContain("[/CACHED")
        .doesNotContain("[NOT CACHED");
  }

  @Test
  void splitV11_handlesMissingMarkerByPuttingAllInSystem() {
    String unstructured = "Some prompt text with no markers at all.";
    SummaryService.SystemAndUser split = SummaryService.splitV11(unstructured);
    assertThat(split.system()).isEqualTo(unstructured);
    assertThat(split.user()).isEmpty();
  }
}
