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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * YouTube fetcher for Phase 1.5 social mentions (per docs/17-phase-1-5-social.md). Uses the public
 * oEmbed endpoint at {@code https://www.youtube.com/oembed} — keyless, no rate limit worth worrying
 * about, returns title / channel name / thumbnail.
 *
 * <p><b>Engagement counts are NOT available from oEmbed.</b> Likes / views / comments require the
 * YouTube Data API v3 (an API key + per-quota cost) which we haven't wired up yet. For v1 we leave
 * engagement fields null and let {@link ReachEstimator} fall back to channel-level heuristics
 * (which it currently lacks for YouTube — to be added when channel statistics are reachable). Net
 * effect: YouTube items still flow through the social pipeline, surface in the Social mentions
 * section of the rendered report, and are LLM-extracted for sentiment / prominence / topics, but
 * they don't compete with high-engagement social posts in {@link app.beat.render.Highlights}
 * ranking until counts are populated.
 *
 * <p>Supports the four URL shapes accepted by {@link app.beat.social.UrlClassifier}:
 *
 * <ul>
 *   <li>{@code https://www.youtube.com/watch?v=VIDEO_ID}
 *   <li>{@code https://m.youtube.com/watch?v=VIDEO_ID}
 *   <li>{@code https://www.youtube.com/shorts/VIDEO_ID}
 *   <li>{@code https://youtu.be/VIDEO_ID}
 * </ul>
 */
@Component
public class YouTubeFetcher implements SocialPostFetcher {

  private static final Logger log = LoggerFactory.getLogger(YouTubeFetcher.class);
  private static final String OEMBED = "https://www.youtube.com/oembed";

  /** {@code v=VIDEO_ID} from a watch URL's query string. */
  private static final Pattern WATCH_V_PARAM = Pattern.compile("[?&]v=([A-Za-z0-9_\\-]{6,20})");

  /** {@code /shorts/VIDEO_ID} for Shorts URLs. */
  private static final Pattern SHORTS_PATH = Pattern.compile("/shorts/([A-Za-z0-9_\\-]{6,20})");

  /** {@code youtu.be/VIDEO_ID} short links. */
  private static final Pattern SHORT_HOST =
      Pattern.compile("^https?://youtu\\.be/([A-Za-z0-9_\\-]{6,20})");

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper();

  @Override
  public String platform() {
    return "youtube";
  }

  @Override
  public Optional<FetchedSocialPost> fetch(String url) {
    String videoId = extractVideoId(url).orElse(null);
    if (videoId == null) {
      log.warn("youtube: url did not match expected shape: {}", url);
      return Optional.empty();
    }

    // Build a canonical watch URL and pass it to oEmbed. oEmbed accepts both watch and youtu.be
    // shapes, but standardizing simplifies downstream URL canonicalization.
    String canonical = "https://www.youtube.com/watch?v=" + videoId;
    JsonNode embed = getOembed(canonical).orElse(null);
    if (embed == null) return Optional.empty();

    String title = embed.path("title").asText("");
    String channelName = embed.path("author_name").asText(null);
    String channelUrl = embed.path("author_url").asText(null);
    String thumbnail = embed.path("thumbnail_url").asText(null);

    // oEmbed has no posted_at, like counts, view counts, or description. Leave them null;
    // ReachEstimator + the LLM extraction prompt both tolerate nulls.
    return Optional.of(
        new FetchedSocialPost(
            "youtube",
            videoId,
            channelName == null ? null : "@" + channelName.replaceAll("\\s+", ""),
            channelName,
            /* authorBio */ null,
            /* authorFollowerCount */ null,
            channelUrl,
            thumbnail,
            /* authorVerified */ false,
            /* postedAt */ null,
            // Title carries the entire signal for a YouTube video at v1 — the description isn't
            // returned by oEmbed. The LLM extractor reads this as the post body and uses it to
            // derive sentiment / prominence / topics.
            title,
            /* contentLang */ null,
            /* isReply */ false,
            /* isQuote */ false,
            /* parentPostUrl */ null,
            /* parentPostText */ null,
            /* threadRootUrl */ null,
            /* hasMedia */ thumbnail != null,
            thumbnail == null ? List.of() : List.of(thumbnail),
            /* mediaDescriptions */ List.of(),
            /* likesCount */ null,
            /* repostsCount */ null,
            /* repliesCount */ null,
            /* viewsCount */ null));
  }

  /** Visible for test. */
  static Optional<String> extractVideoId(String url) {
    if (url == null) return Optional.empty();
    Matcher m = WATCH_V_PARAM.matcher(url);
    if (m.find()) return Optional.of(m.group(1));
    m = SHORTS_PATH.matcher(url);
    if (m.find()) return Optional.of(m.group(1));
    m = SHORT_HOST.matcher(url);
    if (m.find()) return Optional.of(m.group(1));
    return Optional.empty();
  }

  private Optional<JsonNode> getOembed(String videoUrl) {
    String url =
        OEMBED + "?url=" + URLEncoder.encode(videoUrl, StandardCharsets.UTF_8) + "&format=json";
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(8))
              .header("accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status == 401 || status == 404) {
        // oEmbed returns 401 for private/age-restricted videos and 404 for deleted ones. Both
        // mean "we can't fetch this" — log and let the worker mark the row failed cleanly.
        log.info("youtube: oEmbed {} for {}", status, videoUrl);
        return Optional.empty();
      }
      if (status / 100 != 2) {
        log.warn("youtube: oEmbed returned {} for {}", status, videoUrl);
        return Optional.empty();
      }
      return Optional.of(json.readTree(res.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("youtube: interrupted fetching {}", videoUrl);
      return Optional.empty();
    } catch (java.io.IOException e) {
      log.warn("youtube: transport error fetching {}: {}", videoUrl, e.toString());
      return Optional.empty();
    }
  }
}
