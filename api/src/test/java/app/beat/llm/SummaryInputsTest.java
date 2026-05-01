package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryInputsTest {

  @Test
  void reportPeriodLabel_isMonthYearForFullCalendarMonth() {
    SummaryInputs in =
        new SummaryInputs(
            "Acme",
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
        new SummaryInputs(
            "Acme",
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
        new SummaryInputs(
            "Acme",
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
        new SummaryInputs(
            "Acme",
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
}
