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
        /* substantiveCount */ count,
        t1,
        t2,
        t3,
        pos,
        neu,
        mix,
        neg,
        /* feature */ 0, /* mention */
        0, /* passing */
        0, /* missing */
        0,
        /* unknown */ count,
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

  /**
   * Builder for prominence-breakdown fixtures: one helper that takes the full set of integer
   * counters explicitly so adding tests doesn't churn whole-record literals.
   */
  private static SummaryInputs withProminence(
      String clientName,
      int total,
      int substantiveCount,
      int feature,
      int mention,
      int passing,
      int missing,
      int unknown,
      String outletList,
      String topicList,
      String headlines,
      String perItemBlock) {
    return new SummaryInputs(
        clientName,
        LocalDate.of(2026, 4, 1),
        LocalDate.of(2026, 4, 30),
        total,
        substantiveCount,
        /* tier1 */ 0,
        /* tier2 */ 0,
        /* tier3 */ 0,
        /* positive */ 0,
        /* neutral */ 0,
        /* mixed */ 0,
        /* negative */ 0,
        feature,
        mention,
        passing,
        missing,
        unknown,
        outletList,
        topicList,
        headlines,
        perItemBlock);
  }

  @Test
  void hasNoSubstantiveCoverage_trueWhenAllMissing() {
    // 3 items, all missing — Franklin BBQ scenario under v1.3 extraction.
    SummaryInputs in = withProminence("Franklin BBQ", 3, 0, 0, 0, 0, 3, 0, "", "", "", "");
    assertThat(in.hasNoSubstantiveCoverage()).isTrue();
  }

  @Test
  void hasNoSubstantiveCoverage_falseWhenAnyPassing() {
    // Under v1.3 logic, even one passing item disables the guard. The LLM gets called and the
    // v1.2 prompt's grounding rules are responsible for honest framing.
    SummaryInputs in = withProminence("Acme", 3, 1, 0, 0, 1, 2, 0, "TechCrunch", "funding", "", "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void hasNoSubstantiveCoverage_falseWhenAnyMention() {
    SummaryInputs in =
        withProminence("Acme", 3, 1, 0, 1, 0, 2, 0, "TechCrunch", "funding", "Acme funded", "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void hasNoSubstantiveCoverage_falseWhenZeroItems() {
    SummaryInputs in = withProminence("Acme", 0, 0, 0, 0, 0, 0, 0, "", "", "", "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void hasNoSubstantiveCoverage_isImpreciseOnLegacyAllPassingData() {
    // v1.0–v1.2 era data: LLM tagged off-topic articles as 'passing' because the enum lacked
    // 'missing'. The new logic treats this as substantive and routes to the LLM. Documented
    // trade-off — the alternative would be re-extracting old reports, which docs/05 forbids.
    SummaryInputs in =
        withProminence("Franklin BBQ", 3, 3, 0, 0, 3, 0, 0, "TechCrunch", "funding", "...", "");
    assertThat(in.hasNoSubstantiveCoverage()).isFalse();
  }

  @Test
  void coverageItemsSummaryV12_includesProminenceBreakdownAndPerItem() {
    SummaryInputs in =
        withProminence(
            "Acme",
            3,
            2,
            0,
            1,
            1,
            1,
            0,
            "TechCrunch",
            "funding",
            "Acme funded",
            "Item 1: Acme funded\n  Outlet: TechCrunch (Tier 2)\n  Subject prominence: mention  |  Sentiment: positive");
    String s = in.coverageItemsSummaryV12();
    assertThat(s).contains("Subject prominence — feature: 0, mention: 1, passing: 1, missing: 1");
    assertThat(s).contains("Per-item detail");
    assertThat(s).contains("Item 1: Acme funded");
  }

  @Test
  void coverageItemsSummary_usesSubstantiveCountAndAddsDisclosure() {
    // 3 items total, 1 substantive (mention), 2 missing.
    SummaryInputs in =
        withProminence("Acme", 3, 1, 0, 1, 0, 2, 0, "TechCrunch", "funding", "Acme funded", "");
    String s = in.coverageItemsSummary();
    // Headline number is the substantive count, not the total.
    assertThat(s).contains("Total coverage items: 1").doesNotContain("Total coverage items: 3");
    // Disclosure footnote acknowledges the 2 off-topic additions.
    assertThat(s).contains("2 additional items");
    assertThat(s).contains("did not mention the subject");
  }

  @Test
  void coverageItemsSummary_omitsDisclosureWhenNoMissing() {
    SummaryInputs in =
        withProminence("Acme", 3, 3, 0, 1, 2, 0, 0, "TechCrunch", "funding", "Acme funded", "");
    String s = in.coverageItemsSummary();
    assertThat(s).contains("Total coverage items: 3");
    assertThat(s).doesNotContain("additional item");
  }
}
