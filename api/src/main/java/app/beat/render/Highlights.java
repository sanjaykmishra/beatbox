package app.beat.render;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Picks the top N coverage items by {@code tier × prominence × log(reach)} per docs/08 week 6.
 * Lower tier number = higher rank, so we use {@code (4 - tier)}. "feature" outranks "mention"
 * outranks "passing".
 */
public final class Highlights {

  private Highlights() {}

  public static List<CoverageItem> pickTop(
      List<CoverageItem> items, Map<UUID, Outlet> outlets, int n) {
    return items.stream()
        .filter(c -> "done".equals(c.extractionStatus()))
        .sorted(Comparator.comparingDouble(Highlights::score).reversed())
        .limit(Math.max(0, n))
        .toList();
  }

  static double score(CoverageItem c) {
    int tierWeight = 4 - (c.tierAtExtraction() == null ? 3 : c.tierAtExtraction()); // 1→3, 2→2, 3→1
    int prominenceWeight = prominenceWeight(c.subjectProminence());
    long reach = c.estimatedReach() == null ? 100 : Math.max(c.estimatedReach(), 100);
    return tierWeight * prominenceWeight * Math.log10(reach);
  }

  private static int prominenceWeight(String p) {
    if (p == null) return 1;
    return switch (p) {
      case "feature" -> 3;
      case "mention" -> 2;
      case "passing" -> 1;
      default -> 1;
    };
  }
}
