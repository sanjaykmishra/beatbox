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
          Pattern.compile("^/?(login|signin|signup|register|account)(/|$)"),
          // Unsubscribe / opt-out / preference-center landing pages — never an article. Catches
          // both /unsubscribe/... and /u/... (the abbreviated form used by Mailchimp / SendGrid
          // 'manage your subscription' links the user pastes by mistake).
          Pattern.compile("^/?(unsubscribe|opt[-_]?out|preferences|email[-_]?preferences)(/|$)"),
          Pattern.compile("^/u/[^/]+/?$"));

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

  /**
   * Direct downloads / non-HTML resources. Pasting an image, PDF, or zip URL into the URL field
   * should fail at the door — the article fetcher would either return empty or feed the LLM raw
   * bytes / a binary preview. Matched against the path's last segment to avoid false- positives on
   * legit slugs that happen to contain the substring (e.g. "/ai-png-explainer/").
   */
  private static final Pattern BINARY_EXTENSION =
      Pattern.compile(
          "(?i)\\.(?:png|jpe?g|gif|svg|webp|ico|bmp|tiff?|"
              + "pdf|epub|mobi|"
              + "zip|tar|gz|tgz|bz2|7z|rar|"
              + "exe|dmg|pkg|deb|rpm|msi|"
              + "mp3|wav|flac|aac|ogg|opus|m4a|"
              + "mp4|mov|avi|mkv|webm|wmv|"
              + "css|js|map|woff2?|ttf|otf|eot|"
              + "csv|xml|json|yaml|yml|"
              + "doc|docx|xls|xlsx|ppt|pptx|odt|ods|odp"
              + ")(?:[?#].*)?$");

  /**
   * Email / marketing tracker hosts. These domains exist to redirect a click out to the real
   * article — the URL the prefilter sees is a tracker URL, not the destination, and fetching it
   * either returns a redirect-stub HTML or a 302 the article fetcher won't follow into useful
   * content. Matched as host suffixes so subdomains like {@code email.sendgrid.net} also reject.
   *
   * <p>Curated from the trackers we've seen most often pasted by accident: Mailchimp, SendGrid,
   * Constant Contact, Klaviyo, HubSpot, Marketo, Salesforce Marketing Cloud, Mandrill, Mailgun. URL
   * shorteners (bit.ly, t.co, etc.) are deliberately NOT in this list — they often shorten
   * legitimate article URLs and fetching them follows redirects to the real destination, which the
   * article pipeline handles fine.
   */
  private static final List<String> TRACKER_HOST_SUFFIXES =
      List.of(
          "list-manage.com",
          "sendgrid.net",
          "mailchi.mp",
          "rs6.net",
          "marketo.com",
          "marketo.net",
          "klaviyo.com",
          "hubspotlinks.com",
          "hubspotemail.net",
          "exct.net",
          "mandrillapp.com",
          "mailgun.org");

  /**
   * Tracker path shapes that show up across multiple platforms — caught here so a tracker host we
   * haven't enumerated yet still gets rejected if it follows the conventional shape.
   */
  private static final List<Pattern> TRACKER_PATHS =
      List.of(
          Pattern.compile("(?i)^/track/click(/|$)"),
          Pattern.compile("(?i)^/wf/click(\\?|$)"),
          Pattern.compile("(?i)^/ls/click(/|$|\\?)"),
          Pattern.compile("(?i)^/cl[0-9]/"),
          Pattern.compile("(?i)^/e/click(\\?|$)"));

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

    // Hosts that exist solely for unsubscribe / opt-out flows. The path-pattern check below
    // catches /unsubscribe paths on normal hosts, but a dedicated host like
    // unsubscribe.example.com may pair the unsubscribe semantic with an opaque path
    // (/u/<token>) that path patterns can't reliably classify.
    if (hostLower.startsWith("unsubscribe.") || hostLower.startsWith("optout.")) {
      return Optional.of("unsubscribe / opt-out URL (not an article)");
    }

    // Binary / non-HTML resource — rejected before any host-specific logic so a PDF on a tracked
    // host doesn't confuse the rejection reason.
    if (BINARY_EXTENSION.matcher(pathLower).find()) {
      return Optional.of("non-article file (image / PDF / archive / asset)");
    }

    // Email / marketing tracker hosts (Mailchimp, SendGrid, Klaviyo, HubSpot, etc.).
    for (String suffix : TRACKER_HOST_SUFFIXES) {
      if (hostLower.equals(suffix) || hostLower.endsWith("." + suffix)) {
        return Optional.of("email/marketing tracker URL (" + suffix + ")");
      }
    }
    for (Pattern p : TRACKER_PATHS) {
      if (p.matcher(pathLower).find()) {
        return Optional.of("tracker click URL (" + p.pattern() + ")");
      }
    }

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
