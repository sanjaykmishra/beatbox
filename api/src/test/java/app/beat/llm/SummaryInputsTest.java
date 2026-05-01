package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryInputsTest {

  /** Minimal-allocation builder so adding fields doesn't churn every fixture. */
  private static SummaryInputs make(
      LocalDate start,
      LocalDate end,
      int count,
      int t1,
      int t2,
      int t3,
      int pos,
      int neu,
      int mix,
      int neg,
      String outlets,
      String topics,
      String headlines) {
    return new SummaryInputs(
        "Acme",
        start,
        end,
        count,
        t1,
        t2,
        t3,
        pos,
        neu,
        mix,
        neg,
        /* feature */ 0, /* mention */
        0, /* passing */
        0, /* unknown */
        count,
        outlets,
        topics,
        headlines,
        /* perItemBlock */ "");
  }

  @Test
  void reportPeriodLabel_isMonthYearForFullCalendarMonth() {
    SummaryInputs in =
        make(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            6,
            1,
            3,
            2,
            4,
            1,
            1,
            0,
            "TechCrunch, NYT",
            "funding, product",
            "Acme raises Series B");
    assertThat(in.reportPeriodLabel()).isEqualTo("January 2026");
  }

  @Test
  void reportPeriodLabel_fallsBackToRangeForPartialPeriod() {
    SummaryInputs in =
        make(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 19),
            2,
            0,
            1,
            1,
            1,
            1,
            0,
            0,
            "",
            "",
            "");
    assertThat(in.reportPeriodLabel()).isEqualTo("2026-01-05 to 2026-01-19");
  }

  @Test
  void coverageItemsSummary_includesAllDimensions() {
    SummaryInputs in =
        make(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            6,
            1,
            3,
            2,
            4,
            1,
            1,
            0,
            "TechCrunch, NYT",
            "funding, product",
            "Acme raises Series B\nAcme launches platform");
    String s = in.coverageItemsSummary();
    assertThat(s).contains("Total coverage items: 6");
    assertThat(s).contains("Tier 1: 1");
    assertThat(s).contains("positive: 4");
    assertThat(s).contains("Top outlets by reach: TechCrunch, NYT");
    assertThat(s).contains("Most-mentioned topics: funding, product");
    assertThat(s).contains("Acme raises Series B");
  }

  @Test
  void coverageItemsSummary_omitsBlankSections() {
    SummaryInputs in =
        make(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            "");
    String s = in.coverageItemsSummary();
    assertThat(s).contains("Total coverage items: 0");
    assertThat(s).doesNotContain("Top outlets by reach:");
    assertThat(s).doesNotContain("Most-mentioned topics:");
    assertThat(s).doesNotContain("Notable headlines:");
  }

  @Test
  void existingV1PromptVarsRemainStableForV1Path() {
    SummaryInputs in =
        make(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            6,
            1,
            3,
            2,
            4,
            1,
            1,
            0,
            "TechCrunch, NYT",
            "funding",
            "Acme raises Series B");
    Map<String, String> vars = in.toPromptVars();
    assertThat(vars).containsKeys("client_name", "tier_1_n", "positive_n", "up_to_5_headlines");
    assertThat(vars.get("count")).isEqualTo("6");
  }

  @Test
  void hasNoSubstantiveCoverage_trueWhenAllPassing() {
    // 3 items, all passing — Franklin BBQ scenario
    SummaryInputs in =
        new SummaryInputs(
            "Franklin BBQ",
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            3,
            0,
            2,
            1,
            0,
            3,
            0,
            0,
            /* feature */ 0, /* mention */
            0, /* passing */
            3, /* unknown */
            0,
            "TechCrunch, Let's Data Science",
            "funding, product launch",
            "Sources: Anthropic potential $900B valuation",
            "");
    assertThat(in.hasNoSubstantiveCoverage()).isTrue();
  }

  @Test
  void hasNoSubstantiveCoverage_falseWhenAnyMention() {
    SummaryInputs in =
        new SummaryInputs(
            "Acme",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            3,
            0,
            2,
            1,
            0,
            3,
            0,
            0,
            /* feature */ 0, /* mention */
            1, /* passing */
            2, /* unknown */
            0,
            "TechCrunch",
            "funding",
            "Acme funded",
            "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void hasNoSubstantiveCoverage_falseWhenZeroItems() {
    SummaryInputs in =
        new SummaryInputs(
            "Acme",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            "",
            "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void coverageItemsSummaryV12_includesProminenceBreakdownAndPerItem() {
    SummaryInputs in =
        new SummaryInputs(
            "Acme",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            3,
            0,
            2,
            1,
            0,
            3,
            0,
            0,
            /* feature */ 0, /* mention */
            1, /* passing */
            2, /* unknown */
            0,
            "TechCrunch",
            "funding",
            "Acme funded",
            "Item 1: Acme funded\n  Outlet: TechCrunch (Tier 2)\n  Subject prominence: mention  |  Sentiment: positive");
    String s = in.coverageItemsSummaryV12();
    assertThat(s).contains("Subject prominence — feature: 0, mention: 1, passing: 2");
    assertThat(s).contains("Per-item detail");
    assertThat(s).contains("Item 1: Acme funded");
  }
}
