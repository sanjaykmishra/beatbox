package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SummaryServiceTest {

  @Test
  void normalizeMode_acceptsKnownAndDefaultsToV12() {
    // Default flipped from v1 (Opus, no grounding rules) to v1_2 (Sonnet + cached + grounding
    // rules) per docs/18-cost-engineering.md migration sequencing item 8.
    assertThat(SummaryService.normalizeMode("v1")).isEqualTo("v1");
    assertThat(SummaryService.normalizeMode("v1_1")).isEqualTo("v1_1");
    assertThat(SummaryService.normalizeMode("v1_2")).isEqualTo("v1_2");
    assertThat(SummaryService.normalizeMode("shadow")).isEqualTo("shadow");
    assertThat(SummaryService.normalizeMode("shadow_v12")).isEqualTo("shadow_v12");
    assertThat(SummaryService.normalizeMode("V1_2")).isEqualTo("v1_2");
    assertThat(SummaryService.normalizeMode(null)).isEqualTo("v1_2");
    assertThat(SummaryService.normalizeMode("nonsense")).isEqualTo("v1_2");
  }

  @Test
  void promptCacheSplit_splitsAtPerReportMarkerAndStripsBracketMarkers() {
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

    PromptCacheSplit.SystemAndUser split = PromptCacheSplit.split(rendered);

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
  void promptCacheSplit_handlesMissingMarkerByPuttingAllInSystem() {
    String unstructured = "Some prompt text with no markers at all.";
    PromptCacheSplit.SystemAndUser split = PromptCacheSplit.split(unstructured);
    assertThat(split.system()).isEqualTo(unstructured);
    assertThat(split.user()).isEmpty();
    assertThat(split.cacheable()).isFalse();
  }

  @Test
  void promptCacheSplit_acceptsAnyNotCachedSuffix() {
    // extraction-v1-2 uses "per-article", executive-summary-v1-1 uses "per-report" — both work.
    PromptCacheSplit.SystemAndUser article =
        PromptCacheSplit.split("instructions\n[NOT CACHED — per-article]\ninputs");
    assertThat(article.user()).isEqualTo("inputs");
    assertThat(article.cacheable()).isTrue();
  }
}
