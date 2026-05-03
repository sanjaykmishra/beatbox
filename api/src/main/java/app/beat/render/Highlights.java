package app.beat.render;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.social.SocialMention;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Picks the top N highlights across both streams (articles + social posts). Per CLAUDE.md guardrail
 * #8 the rendered report shouldn't be article-only; a high-engagement social post about the client
 * is a real highlight.
 *
 * <p>Scoring intentionally produces comparable scalars across kinds:
 *
 * <ul>
 *   <li>Article: {@code tierWeight × prominenceWeight × log10(max(reach, 100))} — same shape as the
 *       original article-only highlights.
 *   <li>Social: {@code prominenceWeight × log10(max(engagementScore, 10))}, where engagementScore =
 *       likes + 2·reposts + replies + log10(max(views,1)). The lack of a tierWeight term means a
 *       high-engagement social post can still crack the top ranks but has to clear a higher
 *       engagement bar than a Tier-1 article does on reach.
 * </ul>
 *
 * <p>Both streams are pre-filtered by the caller (substantive only — {@code 'done'} and {@code
 * prominence != 'missing'}); this class just ranks.
 */
public final class Highlights {

  private Highlights() {}

  /** A picked highlight: the item itself plus its kind, used by RenderPayloadBuilder to map. */
  public sealed interface Picked permits PickedArticle, PickedSocial {}

  public record PickedArticle(CoverageItem item) implements Picked {}

  public record PickedSocial(SocialMention mention) implements Picked {}

  /**
   * Returns up to {@code n} top picks across both streams, ranked by the unified score above.
   * Caller is responsible for substantive-filtering the inputs.
   */
  public static List<Picked> pickTop(
      List<CoverageItem> articles, List<SocialMention> mentions, Map<UUID, Outlet> outlets, int n) {
    if (n <= 0) return List.of();
    record Scored(Picked p, double score) {}
    List<Scored> scored = new ArrayList<>();
    for (CoverageItem c : articles) {
      scored.add(new Scored(new PickedArticle(c), articleScore(c)));
    }
    for (SocialMention m : mentions) {
      scored.add(new Scored(new PickedSocial(m), socialScore(m)));
    }
    return scored.stream()
        .sorted(Comparator.comparingDouble(Scored::score).reversed())
        .limit(n)
        .map(Scored::p)
        .toList();
  }

  /**
   * Backwards-compatible article-only pick (kept for any other caller; current callers use the
   * unified pickTop above).
   */
  public static List<CoverageItem> pickTopArticles(
      List<CoverageItem> items, Map<UUID, Outlet> outlets, int n) {
    return items.stream()
        .filter(c -> "done".equals(c.extractionStatus()))
        .sorted(Comparator.comparingDouble(Highlights::articleScore).reversed())
        .limit(Math.max(0, n))
        .toList();
  }

  static double articleScore(CoverageItem c) {
    int tierWeight = 4 - (c.tierAtExtraction() == null ? 3 : c.tierAtExtraction()); // 1→3, 2→2, 3→1
    int prominenceWeight = prominenceWeight(c.subjectProminence());
    long reach = c.estimatedReach() == null ? 100 : Math.max(c.estimatedReach(), 100);
    return tierWeight * prominenceWeight * Math.log10(reach);
  }

  static double socialScore(SocialMention m) {
    int prominenceWeight = prominenceWeight(m.subjectProminence());
    long likes = m.likesCount() == null ? 0 : Math.max(m.likesCount(), 0);
    long reposts = m.repostsCount() == null ? 0 : Math.max(m.repostsCount(), 0);
    long replies = m.repliesCount() == null ? 0 : Math.max(m.repliesCount(), 0);
    long views = m.viewsCount() == null ? 0 : Math.max(m.viewsCount(), 0);
    double engagement = likes + 2.0 * reposts + replies + Math.log10(Math.max(views, 1));
    return prominenceWeight * Math.log10(Math.max(engagement, 10));
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
