package app.beat.render;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic stats for the "at a glance" tile row. Per docs/07 §Step 3. */
public final class AtAGlance {

  private AtAGlance() {}

  public static RenderPayload.Glance compute(List<CoverageItem> items, Map<UUID, Outlet> outlets) {
    int total = items.size();
    int tier1 = 0;
    long reach = 0;
    Set<UUID> outletIds = new HashSet<>();
    for (CoverageItem c : items) {
      if (c.tierAtExtraction() != null && c.tierAtExtraction() == 1) tier1++;
      if (c.estimatedReach() != null) reach += c.estimatedReach();
      if (c.outletId() != null) outletIds.add(c.outletId());
    }
    return new RenderPayload.Glance(total, tier1, outletIds.size(), reach, formatReach(reach));
  }

  static String formatReach(long n) {
    if (n <= 0) return "—";
    if (n >= 1_000_000_000L) return roundDiv(n, 1_000_000_000L) + "B";
    if (n >= 1_000_000L) return roundDiv(n, 1_000_000L) + "M";
    if (n >= 1_000L) return roundDiv(n, 1_000L) + "K";
    return Long.toString(n);
  }

  private static String roundDiv(long n, long d) {
    double v = (double) n / d;
    if (v >= 100) return String.format("%.0f", v);
    if (v >= 10) return String.format("%.1f", v);
    return String.format("%.1f", v);
  }
}
