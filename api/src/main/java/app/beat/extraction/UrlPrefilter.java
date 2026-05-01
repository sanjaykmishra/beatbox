package app.beat.extraction;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Cheap deterministic check that rules out URLs that obviously aren't articles before any LLM call.
 * Per docs/18-cost-engineering.md §"Pre-filter with deterministic code", and per the
 * extraction-v1-2 orchestration steps: homepage paths, category indexes, tag pages, paginated
 * archives, search and feed paths never produce a useful coverage extraction — failing them at the
 * door saves 5–15% of LLM calls.
 *
 * <p>The prefilter is conservative: a URL it doesn't recognize passes through and goes to the LLM.
 * Better to extract once unnecessarily than to silently drop a real article.
 */
@Component
public final class UrlPrefilter {

  /** Path segments that, when present anywhere, indicate a non-article surface. */
  private static final List<Pattern> NON_ARTICLE_SEGMENTS =
      List.of(
          Pattern.compile("^/?(tag|tags|topic|topics|category|categories)/"),
          Pattern.compile("^/?(author|authors)/"),
          Pattern.compile("^/?(search|results)(/|$|\\?)"),
          Pattern.compile("^/?(page)/\\d+/?$"),
          Pattern.compile("^/?(feed|rss|atom)(/|$)"),
          Pattern.compile("^/?(login|signin|signup|register|account)(/|$)"));

  /**
   * Substrings that, when present anywhere in the path, indicate a continuously-updating page
   * rather than a single article (a live blog, ticker, etc.). LLM extraction on these returns
   * garbage because the source has no stable headline / lede.
   */
  private static final List<Pattern> NON_ARTICLE_SUBSTRINGS =
      List.of(
          Pattern.compile("(?i)live[-_]?updates?\\b"),
          Pattern.compile("(?i)/live/"),
          Pattern.compile("(?i)/ticker/"));

  /**
   * Reddit URL pattern that points to an actual post: {@code /r/<sub>/comments/<id>}. Anything else
   * on reddit.com (subreddit listing, user page, hot/new/top sort) is rejected — those surfaces
   * aren't articles even though the article extractor's HTML scrape might silently succeed and feed
   * the LLM noise.
   */
  private static final Pattern REDDIT_POST_PATH =
      Pattern.compile("^/r/[^/]+/comments/[^/]+(?:/.*)?$");

  /** Returns the rejection reason if the URL is obviously not an article; otherwise empty. */
  public Optional<String> reject(String url) {
    if (url == null || url.isBlank()) return Optional.of("empty url");
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return Optional.of("invalid url");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) return Optional.of("missing host");

    String path = uri.getPath() == null ? "" : uri.getPath();
    if (path.isEmpty() || "/".equals(path)) return Optional.of("homepage");

    String hostLower = host.toLowerCase();
    String pathLower = path.toLowerCase();

    // Reddit special case — the article extractor doesn't know what to do with subreddit
    // listings, sort tabs, or user pages. Catch them here with a helpful message that points
    // the user at the right shape.
    if (hostLower.endsWith("reddit.com") && !REDDIT_POST_PATH.matcher(pathLower).matches()) {
      return Optional.of(
          "reddit listing (paste a single post URL like /r/<sub>/comments/<id>/...)");
    }

    for (Pattern p : NON_ARTICLE_SEGMENTS) {
      if (p.matcher(pathLower).find()) {
        return Optional.of("non-article path: " + p.pattern());
      }
    }
    for (Pattern p : NON_ARTICLE_SUBSTRINGS) {
      if (p.matcher(pathLower).find()) {
        return Optional.of("live-updates / ticker page (not a single article)");
      }
    }

    return Optional.empty();
  }
}
