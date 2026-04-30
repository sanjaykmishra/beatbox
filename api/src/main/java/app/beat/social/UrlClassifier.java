package app.beat.social;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classifies a URL as either a social post (one of the platforms enumerated in {@code
 * social_mentions.platform}) or an article. Used by the coverage dispatcher to route incoming URLs
 * to the correct extraction pipeline. Spec: docs/17-phase-1-5-social.md §17.1 "URL ingestion: the
 * entry points".
 *
 * <p>Conservative by default — when a host doesn't match a known social pattern, returns {@code
 * Optional.empty()} and the dispatcher falls back to article extraction. Adding a new platform
 * means appending one row to {@link #PATTERNS} and one entry to {@code social_mentions .platform}
 * CHECK constraint.
 */
public final class UrlClassifier {

  private record PlatformPattern(String platform, Pattern pathPattern, List<String> hostSuffixes) {}

  /**
   * Order matters only for performance — first match wins, but each platform's host suffixes are
   * disjoint so order doesn't affect correctness. Path patterns are compiled once.
   */
  private static final List<PlatformPattern> PATTERNS =
      List.of(
          // X / Twitter — twitter.com, x.com, mobile.twitter.com.  Match status URLs only;
          // /home, /search, /username (profile) are not posts.
          new PlatformPattern(
              "x",
              Pattern.compile("^/[^/]+/status(?:es)?/\\d+(?:/.*)?$"),
              List.of("twitter.com", "x.com")),
          // LinkedIn — feed, posts, pulse articles.
          new PlatformPattern(
              "linkedin",
              Pattern.compile(
                  "^/(?:posts/|feed/update/|pulse/|in/[^/]+/recent-activity/)" + "(?:.*)?$"),
              List.of("linkedin.com")),
          // Bluesky — /profile/{handle}/post/{rkey}
          new PlatformPattern(
              "bluesky",
              Pattern.compile("^/profile/[^/]+/post/[^/]+$"),
              List.of("bsky.app", "bsky.social")),
          // Threads — /@handle/post/{id}
          new PlatformPattern(
              "threads", Pattern.compile("^/@[^/]+/post/[^/]+$"), List.of("threads.net")),
          // Instagram — /p/{shortcode}, /reel/{shortcode}, /tv/{shortcode}
          new PlatformPattern(
              "instagram", Pattern.compile("^/(?:p|reel|tv)/[^/]+/?$"), List.of("instagram.com")),
          // Facebook — page posts, groups, photo permalinks.
          new PlatformPattern(
              "facebook",
              Pattern.compile(
                  "^/(?:[^/]+/posts/|groups/[^/]+/(?:permalink|posts)/|"
                      + "photo\\.php|story\\.php|permalink\\.php).*$"),
              List.of("facebook.com", "fb.watch")),
          // TikTok — /@handle/video/{id}
          new PlatformPattern(
              "tiktok",
              Pattern.compile("^/@[^/]+/video/\\d+/?$"),
              List.of("tiktok.com", "vm.tiktok.com")),
          // Reddit — /r/{sub}/comments/{id}/{slug?}
          new PlatformPattern(
              "reddit",
              Pattern.compile("^/r/[^/]+/comments/[^/]+(?:/.*)?$"),
              List.of("reddit.com", "old.reddit.com", "redd.it")),
          // Substack — Notes (/notes/post/...) classify as social; long-form articles route to
          // the article extractor (no match here).
          new PlatformPattern(
              "substack", Pattern.compile("^/notes/post/[^/]+$"), List.of("substack.com")),
          // YouTube — videos and Shorts.
          new PlatformPattern(
              "youtube",
              Pattern.compile("^/(?:watch|shorts/[^/]+)$"),
              List.of("youtube.com", "youtu.be", "m.youtube.com")),
          // Mastodon — federated; we only catch the canonical /@handle/{id} shape.
          // The host varies per instance so the host suffix list is empty and we fall through
          // unless a specific known instance is appended below.
          new PlatformPattern(
              "mastodon",
              Pattern.compile("^/@[^/]+/\\d+$"),
              List.of("mastodon.social", "mastodon.online", "fosstodon.org", "hachyderm.io")));

  private UrlClassifier() {}

  /** Returns true if {@link #platformOf(String)} would classify the URL as a social post. */
  public static boolean isSocialPost(String url) {
    return platformOf(url).isPresent();
  }

  /**
   * Returns the platform identifier (one of the values in {@code social_mentions.platform}) if the
   * URL matches a known social-post pattern, otherwise empty.
   */
  public static Optional<String> platformOf(String url) {
    if (url == null || url.isBlank()) return Optional.empty();
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      return Optional.empty();
    }
    String host = uri.getHost();
    String path = uri.getPath();
    if (host == null || path == null) return Optional.empty();
    String hostLower = host.toLowerCase(Locale.ROOT);
    if (hostLower.startsWith("www.")) hostLower = hostLower.substring(4);
    for (var p : PATTERNS) {
      for (String suffix : p.hostSuffixes()) {
        if (hostLower.equals(suffix) || hostLower.endsWith("." + suffix)) {
          if (p.pathPattern().matcher(path).matches()) {
            return Optional.of(p.platform());
          }
        }
      }
    }
    return Optional.empty();
  }
}
