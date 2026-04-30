package app.beat.llm;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Structured stats fed into prompts/executive-summary-v1.md. Pure function over coverage items. */
public record SummaryInputs(
    String clientName,
    LocalDate periodStart,
    LocalDate periodEnd,
    int count,
    int tier1,
    int tier2,
    int tier3,
    int positive,
    int neutral,
    int mixed,
    int negative,
    String outletList,
    String topicList,
    String headlines) {

  public static SummaryInputs build(
      String clientName,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<CoverageItem> items,
      Map<UUID, Outlet> outlets) {
    int t1 = 0, t2 = 0, t3 = 0;
    int pos = 0, neu = 0, mix = 0, neg = 0;
    Map<UUID, Long> outletReach = new HashMap<>();
    Map<String, Integer> topicCounts = new LinkedHashMap<>();
    List<CoverageItem> ranked = items.stream().filter(c -> c.headline() != null).toList();

    for (CoverageItem c : items) {
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

    String headlines =
        ranked.stream()
            .sorted(
                Comparator.comparing((CoverageItem c) -> tierWeight(c.tierAtExtraction()))
                    .reversed())
            .limit(5)
            .map(CoverageItem::headline)
            .collect(Collectors.joining("\n"));

    return new SummaryInputs(
        clientName,
        periodStart,
        periodEnd,
        items.size(),
        t1,
        t2,
        t3,
        pos,
        neu,
        mix,
        neg,
        outletList,
        topicList,
        headlines);
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
    m.put("count", Integer.toString(count));
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
}
