package app.beat.llm;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Structured stats fed into the executive-summary prompts. Pure function over coverage items.
 *
 * <p><b>Two-pass build.</b> {@code missing} items (subject not in the article body) are excluded
 * from the {@code substantive} aggregations the LLM uses to write the summary — tier counts,
 * sentiment counts, outlet reach, topic mix, top headlines, per-item detail. Including them causes
 * Sonnet/Opus to write about outlets and topics that came from off-topic articles. The prominence
 * breakdown ({@code featureCount} / {@code mentionCount} / {@code passingCount} / {@code
 * missingCount}) is collected over ALL items so the LLM sees the full off-topic context and so the
 * runtime guard ({@link #hasNoSubstantiveCoverage}) has the data it needs.
 *
 * <p>{@code count} is total items (drives the deterministic {@link
 * SummaryService#noSubstantiveCoverageText} text). {@code substantiveCount} is the filtered count
 * (drives the LLM-facing "Total coverage items" line).
 */
public record SummaryInputs(
    String clientName,
    LocalDate periodStart,
    LocalDate periodEnd,
    int count,
    int substantiveCount,
    int tier1,
    int tier2,
    int tier3,
    int positive,
    int neutral,
    int mixed,
    int negative,
    int featureCount,
    int mentionCount,
    int passingCount,
    int missingCount,
    int prominenceUnknownCount,
    String outletList,
    String topicList,
    String headlines,
    /**
     * Per-item lines for v1.2 prompt — headline, outlet, prominence, and the extraction-side
     * summary. Substantive items only (missing items are excluded since the LLM shouldn't be shown
     * headlines and summaries for articles that don't mention the subject).
     */
    String perItemBlock) {

  public static SummaryInputs build(
      String clientName,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<CoverageItem> items,
      Map<UUID, Outlet> outlets) {
    // Pass 1 — prominence counters over ALL items (drives the runtime guard + the prominence
    // breakdown in the LLM input, which intentionally surfaces missing items for transparency).
    int featureN = 0, mentionN = 0, passingN = 0, missingN = 0, unknownN = 0;
    for (CoverageItem c : items) {
      String p = c.subjectProminence();
      if (p == null) {
        unknownN++;
      } else {
        switch (p) {
          case "feature" -> featureN++;
          case "mention" -> mentionN++;
          case "passing" -> passingN++;
          case "missing" -> missingN++;
          default -> unknownN++;
        }
      }
    }

    // Pass 2 — every other aggregate is computed over substantive items only. An article
    // tagged 'missing' is one the user added but that doesn't actually mention the subject;
    // its tier / sentiment / outlet / topics / headline are not "coverage of the client" and
    // would mislead the LLM about what the client was actually in.
    List<CoverageItem> substantive =
        items.stream().filter(c -> !"missing".equals(c.subjectProminence())).toList();

    int t1 = 0, t2 = 0, t3 = 0;
    int pos = 0, neu = 0, mix = 0, neg = 0;
    Map<UUID, Long> outletReach = new HashMap<>();
    Map<String, Integer> topicCounts = new LinkedHashMap<>();
    for (CoverageItem c : substantive) {
      Integer t = c.tierAtExtraction();
      if (t != null) {
        if (t == 1) t1++;
        else if (t == 2) t2++;
        else t3++;
      }
      if (c.sentiment() != null) {
        switch (c.sentiment()) {
          case "positive" -> pos++;
          case "neutral" -> neu++;
          case "mixed" -> mix++;
          case "negative" -> neg++;
          default -> {}
        }
      }
      if (c.outletId() != null) {
        long add = c.estimatedReach() == null ? 1 : Math.max(c.estimatedReach(), 1);
        outletReach.merge(c.outletId(), add, Long::sum);
      }
      for (String topic : c.topics()) {
        if (topic != null && !topic.isBlank()) {
          topicCounts.merge(topic.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
        }
      }
    }

    String outletList =
        outletReach.entrySet().stream()
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(5)
            .map(e -> outlets.get(e.getKey()))
            .filter(java.util.Objects::nonNull)
            .map(Outlet::name)
            .collect(Collectors.joining(", "));
    String topicList =
        topicCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));

    List<CoverageItem> rankedSubstantive =
        substantive.stream().filter(c -> c.headline() != null).toList();
    String headlines =
        rankedSubstantive.stream()
            .sorted(
                Comparator.comparing((CoverageItem c) -> tierWeight(c.tierAtExtraction()))
                    .reversed())
            .limit(5)
            .map(CoverageItem::headline)
            .collect(Collectors.joining("\n"));

    String perItemBlock = renderPerItem(rankedSubstantive, outlets);

    return new SummaryInputs(
        clientName,
        periodStart,
        periodEnd,
        items.size(),
        substantive.size(),
        t1,
        t2,
        t3,
        pos,
        neu,
        mix,
        neg,
        featureN,
        mentionN,
        passingN,
        missingN,
        unknownN,
        outletList,
        topicList,
        headlines,
        perItemBlock);
  }

  /**
   * Render every item as one block: headline, outlet, prominence, sentiment, plus the extraction
   * summary text. The extraction summary is what flags "client not mentioned in this article" —
   * crucial grounding for v1.2 and absent from v1/v1.1.
   */
  private static String renderPerItem(List<CoverageItem> ranked, Map<UUID, Outlet> outlets) {
    var lines = new ArrayList<String>();
    int i = 1;
    for (CoverageItem c :
        ranked.stream()
            .sorted(
                Comparator.comparing((CoverageItem x) -> tierWeight(x.tierAtExtraction()))
                    .reversed())
            .toList()) {
      String outlet =
          c.outletId() != null && outlets.containsKey(c.outletId())
              ? outlets.get(c.outletId()).name()
              : "(unknown outlet)";
      String tier = c.tierAtExtraction() == null ? "?" : c.tierAtExtraction().toString();
      String prominence = c.subjectProminence() == null ? "unknown" : c.subjectProminence();
      String sentiment = c.sentiment() == null ? "unknown" : c.sentiment();
      lines.add(
          "Item "
              + i
              + ": "
              + c.headline()
              + "\n"
              + "  Outlet: "
              + outlet
              + " (Tier "
              + tier
              + ")"
              + "\n"
              + "  Subject prominence: "
              + prominence
              + "  |  Sentiment: "
              + sentiment);
      if (c.summary() != null && !c.summary().isBlank()) {
        lines.add("  Summary: " + truncate(c.summary(), 320));
      }
      i++;
    }
    return String.join("\n", lines);
  }

  private static String truncate(String s, int n) {
    if (s == null || s.length() <= n) return s;
    return s.substring(0, n - 1) + "…";
  }

  private static int tierWeight(Integer t) {
    if (t == null) return 0;
    return switch (t) {
      case 1 -> 3;
      case 2 -> 2;
      case 3 -> 1;
      default -> 0;
    };
  }

  public Map<String, String> toPromptVars() {
    var m = new LinkedHashMap<String, String>();
    m.put("client_name", clientName == null ? "the client" : clientName);
    m.put("period_start", periodStart == null ? "" : periodStart.toString());
    m.put("period_end", periodEnd == null ? "" : periodEnd.toString());
    // Substantive count is what the LLM should treat as the headline number; total count is
    // available for templates that want to surface "N additional off-topic items".
    m.put("count", Integer.toString(substantiveCount));
    m.put("total_count", Integer.toString(count));
    m.put("missing_count", Integer.toString(missingCount));
    m.put("tier_1_n", Integer.toString(tier1));
    m.put("tier_2_n", Integer.toString(tier2));
    m.put("tier_3_n", Integer.toString(tier3));
    m.put("positive_n", Integer.toString(positive));
    m.put("neutral_n", Integer.toString(neutral));
    m.put("mixed_n", Integer.toString(mixed));
    m.put("negative_n", Integer.toString(negative));
    m.put("outlet_list", outletList == null ? "" : outletList);
    m.put("topic_list", topicList == null ? "" : topicList);
    m.put("up_to_5_headlines", headlines == null ? "" : headlines);
    return m;
  }

  /** Human-readable period label, e.g. "January 2026" when one calendar month is covered. */
  public String reportPeriodLabel() {
    if (periodStart == null || periodEnd == null) return "";
    boolean fullMonth =
        periodStart.getDayOfMonth() == 1
            && periodEnd.getDayOfMonth() == periodEnd.lengthOfMonth()
            && periodStart.getYear() == periodEnd.getYear()
            && periodStart.getMonth() == periodEnd.getMonth();
    if (fullMonth) {
      return periodStart
              .getMonth()
              .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
          + " "
          + periodStart.getYear();
    }
    return periodStart + " to " + periodEnd;
  }

  /**
   * Multi-line structured summary suitable for the {@code {{coverage_items_summary}}} placeholder
   * in executive-summary-v1.1. Keeps the LLM grounded in the same data v1.0 received but in a
   * single block the prompt can quote.
   */
  public String coverageItemsSummary() {
    StringBuilder b = new StringBuilder();
    // 'Total coverage items' is the substantive count (excludes 'missing' items); the LLM uses
    // it as the headline number for the report. The off-topic context lives in the prominence
    // breakdown below (added by coverageItemsSummaryV12) and in the deterministic guard text
    // when all items are missing.
    b.append("Total coverage items: ").append(substantiveCount).append('\n');
    if (missingCount > 0) {
      b.append("(Plus ")
          .append(missingCount)
          .append(" additional item")
          .append(missingCount == 1 ? "" : "s")
          .append(" added to this report that did not mention the subject")
          .append(" — excluded from the counts above.)\n");
    }
    b.append("Tier breakdown — Tier 1: ")
        .append(tier1)
        .append(", Tier 2: ")
        .append(tier2)
        .append(", Tier 3: ")
        .append(tier3)
        .append('\n');
    b.append("Sentiment — positive: ")
        .append(positive)
        .append(", neutral: ")
        .append(neutral)
        .append(", mixed: ")
        .append(mixed)
        .append(", negative: ")
        .append(negative)
        .append('\n');
    if (outletList != null && !outletList.isBlank()) {
      b.append("Top outlets by reach: ").append(outletList).append('\n');
    }
    if (topicList != null && !topicList.isBlank()) {
      b.append("Most-mentioned topics: ").append(topicList).append('\n');
    }
    if (headlines != null && !headlines.isBlank()) {
      b.append("Notable headlines:\n").append(headlines);
    }
    return b.toString();
  }

  /**
   * Richer block for v1.2 — adds the prominence breakdown and the per-item details (extraction
   * summary, prominence, sentiment) so the LLM has grounding for any subject-prominence claim it
   * might make and can correctly state "client not mentioned" when extraction said so.
   */
  public String coverageItemsSummaryV12() {
    StringBuilder b = new StringBuilder();
    b.append(coverageItemsSummary()).append('\n');
    b.append("Subject prominence — feature: ")
        .append(featureCount)
        .append(", mention: ")
        .append(mentionCount)
        .append(", passing: ")
        .append(passingCount)
        .append(", missing: ")
        .append(missingCount)
        .append(", unknown: ")
        .append(prominenceUnknownCount)
        .append('\n');
    if (perItemBlock != null && !perItemBlock.isBlank()) {
      b.append("\nPer-item detail (use these summaries — they note when the client is not ")
          .append("mentioned in an article):\n")
          .append(perItemBlock);
    }
    return b.toString();
  }

  /**
   * True iff we have items but none of them are non-missing — i.e., zero items have prominence in
   * {feature, mention, passing}. Drives the runtime short-circuit in SummaryService: we don't ask
   * the LLM to write an executive summary about a period where the client wasn't named in any
   * article.
   *
   * <p>Under the v1.3 extraction prompt this fires when every item is tagged {@code missing}. For
   * older v1.0–v1.2-era data the LLM was forced to pick {@code passing} for off-topic articles
   * because the enum lacked {@code missing}; under the new logic those reports won't trip the guard
   * and will go through the LLM path. That's the intended trade-off — per {@code
   * docs/05-llm-prompts.md} we don't re-extract retroactively, and the guard is allowed to be
   * imprecise on legacy data so it can be precise on new data.
   */
  public boolean hasNoSubstantiveCoverage() {
    return count > 0 && featureCount == 0 && mentionCount == 0 && passingCount == 0;
  }
}
