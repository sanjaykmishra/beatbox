package app.beat.extraction;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Last-resort fallback that pays ScrapingBee to render JS, follow redirects, and bypass simple
 * paywalls. Re-uses {@link ReadabilityFetcher#parseHtml} to extract structured fields from the
 * rendered HTML so the pipeline stays consistent.
 */
@Component
public class ScrapingBeeFetcher implements ArticleFetcher {

  private static final Logger log = LoggerFactory.getLogger(ScrapingBeeFetcher.class);

  private final ReadabilityFetcher parser;
  private final String apiKey;
  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  public ScrapingBeeFetcher(
      ReadabilityFetcher parser, @Value("${SCRAPINGBEE_API_KEY:}") String apiKey) {
    this.parser = parser;
    this.apiKey = apiKey;
  }

  @PostConstruct
  void start() {
    if (isConfigured()) {
      log.info("ScrapingBeeFetcher configured (paid paywall fallback enabled)");
    } else {
      log.warn(
          "ScrapingBeeFetcher NOT configured — set SCRAPINGBEE_API_KEY to enable the paid"
              + " paywall / JS-render fallback. Without it, paywalled or JS-heavy URLs fail with"
              + " 'fetch_returned_empty (scrapingbee_unconfigured)'.");
    }
  }

  /** Whether the fallback can actually do work. False when {@code SCRAPINGBEE_API_KEY} is unset. */
  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public String name() {
    return "scrapingbee";
  }

  @Override
  public Optional<FetchedArticle> fetch(String url) {
    if (!isConfigured()) return Optional.empty();
    String endpoint =
        "https://app.scrapingbee.com/api/v1/?api_key="
            + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            + "&url="
            + URLEncoder.encode(url, StandardCharsets.UTF_8)
            + "&render_js=true";
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(45))
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        log.warn("scrapingbee: {} returned status {}", url, res.statusCode());
        return Optional.empty();
      }
      return parser.parseHtml(url, res.body()).map(a -> tagAsScrapingBee(a));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("scrapingbee: {} failed: {}", url, e.toString());
      return Optional.empty();
    }
  }

  /**
   * Same as {@link #fetch(String)} but reports the specific reason on empty so the worker can
   * persist a useful {@code coverage_items.extraction_error} message.
   */
  @Override
  public FetchOutcome fetchWithReason(String url) {
    if (!isConfigured()) {
      return new FetchOutcome(Optional.empty(), "scrapingbee_unconfigured");
    }
    String endpoint =
        "https://app.scrapingbee.com/api/v1/?api_key="
            + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            + "&url="
            + URLEncoder.encode(url, StandardCharsets.UTF_8)
            + "&render_js=true";
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(45))
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status / 100 != 2) {
        log.warn("scrapingbee: {} returned status {}", url, status);
        return new FetchOutcome(Optional.empty(), "scrapingbee_status_" + status);
      }
      Optional<FetchedArticle> parsed =
          parser.parseHtml(url, res.body()).map(this::tagAsScrapingBee);
      if (parsed.isEmpty()) {
        return new FetchOutcome(Optional.empty(), "scrapingbee_content_too_short_or_unparseable");
      }
      return new FetchOutcome(parsed, null);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("scrapingbee: {} failed: {}", url, e.toString());
      return new FetchOutcome(Optional.empty(), "scrapingbee_transport_error");
    }
  }

  private FetchedArticle tagAsScrapingBee(FetchedArticle a) {
    return new FetchedArticle(
        a.url(), a.cleanText(), a.headline(), a.byline(), a.publishDate(), a.outletName(), name());
  }
}
