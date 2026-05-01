package app.beat.social.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reddit fetcher. Uses the free public JSON API: appending {@code .json} to any post URL returns a
 * two-element array — [post listing, comments listing]. We only consume the post.
 *
 * <p>URL shape: {@code https://(www.|old.)?reddit.com/r/<sub>/comments/<id>[/<slug>]}. The {@link
 * app.beat.social.UrlClassifier} also matches {@code redd.it} short links, but those redirect to
 * the canonical URL — we don't follow redirects here, so for Phase 1.5 redd.it URLs fall through to
 * the worker's "fetch_returned_empty" retry path until we add follow-redirects.
 *
 * <p>Reddit requires a non-default User-Agent or it returns 429. We send {@code beat-extraction}.
 */
@Component
public class RedditFetcher implements SocialPostFetcher {

  private static final Logger log = LoggerFactory.getLogger(RedditFetcher.class);
  private static final String USER_AGENT = "beat-extraction/1.0 (+https://beat.app)";

  /**
   * Captures: (1) subreddit, (2) post id. Allows an optional slug segment after the id but stops at
   * any further path component (so a deep-link to a specific comment doesn't accidentally match —
   * those follow a different code path we haven't built yet).
   */
  private static final Pattern URL_PATTERN =
      Pattern.compile(
          "^https?://(?:www\\.|old\\.)?reddit\\.com/r/([^/?#]+)/comments/([^/?#]+)"
              + "(?:/[^/?#]*)?/?(?:\\?.*)?$");

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();

  @Override
  public String platform() {
    return "reddit";
  }

  @Override
  public Optional<FetchedSocialPost> fetch(String url) {
    Matcher m = URL_PATTERN.matcher(url);
    if (!m.find()) {
      log.warn("reddit: url did not match expected shape: {}", url);
      return Optional.empty();
    }
    String subreddit = m.group(1);
    String postId = m.group(2);

    JsonNode listing = getJson(jsonUrlFor(subreddit, postId)).orElse(null);
    if (listing == null) return Optional.empty();
    if (!listing.isArray() || listing.size() == 0) {
      log.warn("reddit: unexpected listing shape for {}/{}", subreddit, postId);
      return Optional.empty();
    }
    JsonNode postData = listing.get(0).path("data").path("children").path(0).path("data");
    if (!postData.isObject()) {
      log.warn("reddit: missing post data for {}/{}", subreddit, postId);
      return Optional.empty();
    }

    String title = postData.path("title").asText("");
    String selftext = postData.path("selftext").asText("");
    // Combine title + body for the LLM. Title leads because Reddit titles often carry the whole
    // signal (link posts have empty selftext).
    String contentText = selftext.isBlank() ? title : title + "\n\n" + selftext;

    Instant postedAt = parseUnixSeconds(postData.path("created_utc"));
    String author = postData.path("author").asText(null);
    if ("[deleted]".equals(author) || author == null || author.isBlank()) author = "[deleted]";
    String authorHandle = "u/" + author;
    String authorProfile = "[deleted]".equals(author) ? null : "https://reddit.com/user/" + author;

    Long likes = nullableLong(postData.path("score"));
    Long comments = nullableLong(postData.path("num_comments"));

    boolean over18 = postData.path("over_18").asBoolean(false);
    boolean isSelf = postData.path("is_self").asBoolean(true);

    // Reddit doesn't expose a per-post audience signal. Use the subreddit's subscriber count as
    // a reach proxy — the worker passes this through ReachEstimator.estimate(platform, followers,
    // views) just like the other platforms, so we don't compute reach here.
    Long subredditSubscribers = subredditSubscribers(subreddit).orElse(null);

    boolean isReply = false;
    boolean isQuote = false;
    String parentPostUrl = null;
    String parentPostText = null;
    String threadRootUrl = null;

    boolean hasMedia = !isSelf || !postData.path("preview").path("images").isMissingNode();
    List<String> mediaUrls = List.of();
    List<String> mediaDescriptions = List.of();

    String externalPostId = "t3_" + postId;

    return Optional.of(
        new FetchedSocialPost(
            "reddit",
            externalPostId,
            authorHandle,
            authorHandle, // Reddit usernames are display names — no separate field.
            null,
            subredditSubscribers, // surfaces as follower_count so ReachEstimator estimates reach
            authorProfile,
            null,
            false,
            postedAt,
            // NSFW posts: don't blank out, but tag in subreddit metadata downstream once we have
            // it.
            over18 ? "[NSFW] " + contentText : contentText,
            null,
            isReply,
            isQuote,
            parentPostUrl,
            parentPostText,
            threadRootUrl,
            hasMedia,
            mediaUrls,
            mediaDescriptions,
            likes,
            null, // Reddit has no reposts in the social-platform sense
            comments,
            null));
  }

  /** {@code https://www.reddit.com/r/<sub>/comments/<id>/.json}. */
  static String jsonUrlFor(String subreddit, String postId) {
    return "https://www.reddit.com/r/" + subreddit + "/comments/" + postId + "/.json";
  }

  private Optional<Long> subredditSubscribers(String subreddit) {
    JsonNode about = getJson("https://www.reddit.com/r/" + subreddit + "/about.json").orElse(null);
    if (about == null) return Optional.empty();
    JsonNode subs = about.path("data").path("subscribers");
    if (subs.isMissingNode() || subs.isNull()) return Optional.empty();
    return Optional.of(subs.asLong());
  }

  private Optional<JsonNode> getJson(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .header("accept", "application/json")
              .header("user-agent", USER_AGENT)
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status / 100 != 2) {
        log.warn("reddit: {} returned {}", url, status);
        return Optional.empty();
      }
      return Optional.of(json.readTree(res.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("reddit: interrupted fetching {}", url);
      return Optional.empty();
    } catch (java.io.IOException e) {
      log.warn("reddit: transport error fetching {}: {}", url, e.toString());
      return Optional.empty();
    }
  }

  private static Instant parseUnixSeconds(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return null;
    return Instant.ofEpochSecond(n.asLong());
  }

  private static Long nullableLong(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return null;
    return n.asLong();
  }

  /** Visible for test. */
  static Optional<String[]> parseUrl(String url) {
    Matcher m = URL_PATTERN.matcher(url);
    if (!m.find()) return Optional.empty();
    return Optional.of(new String[] {m.group(1), m.group(2)});
  }
}
