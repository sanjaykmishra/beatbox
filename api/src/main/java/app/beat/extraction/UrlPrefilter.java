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
    // Bare host or root path: a homepage, never a single article.
    if (path.isEmpty() || "/".equals(path)) return Optional.of("homepage");

    // Walk every path segment looking for non-article markers; a marker at any depth is a reject.
    String pathLower = path.toLowerCase();
    for (Pattern p : NON_ARTICLE_SEGMENTS) {
      // Match against the leading segment AND every interior `/segment` opportunity.
      if (p.matcher(pathLower).find()) {
        return Optional.of("non-article path: " + p.pattern());
      }
    }

    return Optional.empty();
  }
}
