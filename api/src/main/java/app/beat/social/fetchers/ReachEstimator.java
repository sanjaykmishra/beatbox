package app.beat.social.fetchers;

/**
 * Per-platform reach estimator. Where the platform exposes view counts, use them; otherwise fall
 * back to follower-based estimates with platform-specific multipliers reflecting average organic
 * reach. Numbers per docs/17-phase-1-5-social.md §17.1 "Reach formulas".
 *
 * <p>Conservative on purpose. Tune from real customer data once we have months of history.
 */
public final class ReachEstimator {

  private ReachEstimator() {}

  /**
   * @param platform e.g. {@code "bluesky"}, {@code "x"}
   * @param followerCount author's follower count at time of post; null treated as zero
   * @param viewsCount platform-reported views if available; null falls back to multiplier
   * @return estimated reach in audience units, or null if no signal at all
   */
  public static Long estimate(String platform, Long followerCount, Long viewsCount) {
    if (viewsCount != null && viewsCount > 0 && hasMeaningfulViews(platform)) {
      return viewsCount;
    }
    if (followerCount == null) return null;
    double mult =
        switch (platform) {
          case "x" -> 0.04;
          case "linkedin" -> 0.10;
          case "bluesky" -> 0.15;
          case "threads" -> 0.06;
          case "substack" -> 0.30;
          default -> 0.05;
        };
    return Math.round(followerCount * mult);
  }

  /**
   * Platforms whose view counts are publicly meaningful and broadly available. (Reddit and LinkedIn
   * don't reliably surface views; Bluesky has views in newer client APIs but they're sparse — use
   * the follower multiplier instead.)
   */
  private static boolean hasMeaningfulViews(String platform) {
    return "x".equals(platform) || "youtube".equals(platform);
  }
}
