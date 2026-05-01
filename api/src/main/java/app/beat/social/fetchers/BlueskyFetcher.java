package app.beat.social.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bluesky fetcher. Uses the AT Protocol public read API ({@code https://public.api.bsky.app}),
 * which is free and unauthenticated. Two calls per post: resolve the handle to a DID via {@code
 * com.atproto.identity.resolveHandle}, then fetch the thread root via {@code
 * app.bsky.feed.getPostThread}.
 *
 * <p>URL shape: {@code https://bsky.app/profile/<handle>/post/<rkey>}. {@link
 * app.beat.social.UrlClassifier} matches this exactly.
 */
@Component
public class BlueskyFetcher implements SocialPostFetcher {

  private static final Logger log = LoggerFactory.getLogger(BlueskyFetcher.class);
  private static final String API_BASE = "https://public.api.bsky.app/xrpc";
  private static final Pattern URL_PATTERN =
      Pattern.compile("^https?://(?:www\\.)?bsky\\.app/profile/([^/]+)/post/([^/?#]+)");

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();

  @Override
  public String platform() {
    return "bluesky";
  }

  @Override
  public Optional<FetchedSocialPost> fetch(String url) {
    Matcher m = URL_PATTERN.matcher(url);
    if (!m.find()) {
      log.warn("bluesky: url did not match expected shape: {}", url);
      return Optional.empty();
    }
    String handle = m.group(1);
    String rkey = m.group(2);

    String did = resolveDid(handle).orElse(null);
    if (did == null) return Optional.empty();

    String atUri = "at://" + did + "/app.bsky.feed.post/" + rkey;
    JsonNode thread = getPostThread(atUri).orElse(null);
    if (thread == null) return Optional.empty();

    JsonNode post = thread.path("thread").path("post");
    if (post.isMissingNode() || !post.isObject()) {
      log.warn("bluesky: missing post in thread response for {}", atUri);
      return Optional.empty();
    }

    JsonNode record = post.path("record");
    JsonNode author = post.path("author");

    String contentText = record.path("text").asText("");
    Instant postedAt = parseInstant(record.path("createdAt").asText(null));
    String contentLang = firstString(record.path("langs"));

    String authorHandle = author.path("handle").asText(handle);
    String authorDisplayName = author.path("displayName").asText(null);
    String authorAvatar = author.path("avatar").asText(null);
    String authorBio = author.path("description").asText(null);
    Long followerCount = nullableLong(author.path("followersCount"));
    boolean verified =
        author.path("verification").path("verifiedStatus").asText("").equals("valid");

    Long likes = nullableLong(post.path("likeCount"));
    Long reposts = nullableLong(post.path("repostCount"));
    Long replies = nullableLong(post.path("replyCount"));
    Long views =
        null; // Bluesky public API doesn't expose view counts; falls back to follower mult.

    boolean isReply = !record.path("reply").isMissingNode();
    boolean isQuote =
        record.path("embed").path("$type").asText("").contains("app.bsky.embed.record");

    String parentPostUrl = null;
    String parentPostText = null;
    String threadRootUrl = null;
    if (isReply) {
      String parentUri = record.path("reply").path("parent").path("uri").asText(null);
      parentPostUrl = atUriToWebUrl(parentUri);
      String rootUri = record.path("reply").path("root").path("uri").asText(null);
      threadRootUrl = atUriToWebUrl(rootUri);
      // The thread response includes the parent body when fetched at depth=0 via parent_height,
      // but we fetched the thread root at default depth — parent text isn't always nested.
      JsonNode parentInThread = thread.path("thread").path("parent").path("post").path("record");
      if (parentInThread.isObject()) {
        parentPostText = parentInThread.path("text").asText(null);
      }
    }

    List<String> mediaUrls = new ArrayList<>();
    List<String> mediaDescriptions = new ArrayList<>();
    JsonNode embed = post.path("embed");
    JsonNode embedImages = embed.path("images");
    if (embedImages.isArray()) {
      for (JsonNode img : embedImages) {
        String fullsize = img.path("fullsize").asText(null);
        if (fullsize != null) mediaUrls.add(fullsize);
        String alt = img.path("alt").asText(null);
        if (alt != null && !alt.isBlank()) mediaDescriptions.add(alt);
      }
    }
    boolean hasMedia = !mediaUrls.isEmpty();

    Long estimatedReach = ReachEstimator.estimate("bluesky", followerCount, views);

    String authorProfileUrl = "https://bsky.app/profile/" + authorHandle;

    return Optional.of(
        new FetchedSocialPost(
            "bluesky",
            atUri,
            authorHandle,
            authorDisplayName,
            authorBio,
            followerCount,
            authorProfileUrl,
            authorAvatar,
            verified,
            postedAt,
            contentText,
            contentLang,
            isReply,
            isQuote,
            parentPostUrl,
            parentPostText,
            threadRootUrl,
            hasMedia,
            mediaUrls,
            mediaDescriptions,
            likes,
            reposts,
            replies,
            views));
  }

  private Optional<String> resolveDid(String handle) {
    String url =
        API_BASE
            + "/com.atproto.identity.resolveHandle?handle="
            + URLEncoder.encode(handle, StandardCharsets.UTF_8);
    return getJson(url).map(n -> n.path("did").asText(null)).filter(s -> s != null && !s.isBlank());
  }

  private Optional<JsonNode> getPostThread(String atUri) {
    String url =
        API_BASE
            + "/app.bsky.feed.getPostThread?uri="
            + URLEncoder.encode(atUri, StandardCharsets.UTF_8)
            + "&depth=0&parentHeight=1";
    return getJson(url);
  }

  private Optional<JsonNode> getJson(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .header("accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status / 100 != 2) {
        log.warn("bluesky: {} returned {}", url, status);
        return Optional.empty();
      }
      return Optional.of(json.readTree(res.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("bluesky: interrupted fetching {}", url);
      return Optional.empty();
    } catch (java.io.IOException e) {
      log.warn("bluesky: transport error fetching {}: {}", url, e.toString());
      return Optional.empty();
    }
  }

  /** Convert {@code at://did/app.bsky.feed.post/rkey} to the public web URL. */
  static String atUriToWebUrl(String atUri) {
    if (atUri == null) return null;
    // at://did:plc:xxxx/app.bsky.feed.post/3kabc → bsky.app/profile/did:plc:xxxx/post/3kabc
    String body = atUri.startsWith("at://") ? atUri.substring(5) : atUri;
    int firstSlash = body.indexOf('/');
    if (firstSlash < 0) return null;
    String did = body.substring(0, firstSlash);
    int lastSlash = body.lastIndexOf('/');
    if (lastSlash < 0 || lastSlash == firstSlash) return null;
    String rkey = body.substring(lastSlash + 1);
    return "https://bsky.app/profile/" + did + "/post/" + rkey;
  }

  private static Instant parseInstant(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Instant.parse(s);
    } catch (Exception e) {
      return null;
    }
  }

  private static Long nullableLong(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return null;
    return n.asLong();
  }

  private static String firstString(JsonNode arr) {
    if (!arr.isArray() || arr.isEmpty()) return null;
    JsonNode first = arr.get(0);
    return first.isTextual() ? first.asText() : null;
  }
}
