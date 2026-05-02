package app.beat.render;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.social.SocialMention;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic stats for the "at a glance" tile row. Per docs/07 §Step 3 and CLAUDE.md guardrail
 * #8, social mentions are first-class — counts are unified across both streams.
 */
public final class AtAGlance {

  private AtAGlance() {}

  /**
   * Compute the at-a-glance row from already-filtered substantive items in both streams. Caller is
   * responsible for excluding extraction-failed and prominence='missing' items from each list.
   * {@code missingCount} is the combined off-topic count (drives the disclosure footnote).
   *
   * <p>Definitions:
   *
   * <ul>
   *   <li>{@code total} — articles + social posts.
   *   <li>{@code tier_1} — articles only (social posts don't have tier).
   *   <li>{@code outlets} — distinct outlets across articles + distinct platforms across social
   *       (Bluesky and Reddit count as 2 even if there are 5 Bluesky posts; matches how a PR owner
   *       thinks about reach diversity).
   *   <li>{@code reach_total} — sum across both streams (article {@code estimated_reach} + social
   *       {@code estimated_reach}).
   * </ul>
   */
  public static RenderPayload.Glance compute(
      List<CoverageItem> articles,
      List<SocialMention> mentions,
      Map<UUID, Outlet> outlets,
      int missingCount) {
    int total = articles.size() + mentions.size();
    int tier1 = 0;
    long reach = 0;
    Set<UUID> outletIds = new HashSet<>();
    for (CoverageItem c : articles) {
      if (c.tierAtExtraction() != null && c.tierAtExtraction() == 1) tier1++;
      if (c.estimatedReach() != null) reach += c.estimatedReach();
      if (c.outletId() != null) outletIds.add(c.outletId());
    }
    Set<String> platforms = new HashSet<>();
    for (SocialMention m : mentions) {
      if (m.estimatedReach() != null) reach += m.estimatedReach();
      if (m.platform() != null) platforms.add(m.platform());
    }
    int outletCount = outletIds.size() + platforms.size();
    return new RenderPayload.Glance(
        total, tier1, outletCount, reach, formatReach(reach), missingCount);
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
