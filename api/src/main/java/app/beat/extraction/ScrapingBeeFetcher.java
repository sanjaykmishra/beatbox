package app.beat.extraction;

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

  @Override
  public String name() {
    return "scrapingbee";
  }

  @Override
  public Optional<FetchedArticle> fetch(String url) {
    if (apiKey == null || apiKey.isBlank()) return Optional.empty();
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

  private FetchedArticle tagAsScrapingBee(FetchedArticle a) {
    return new FetchedArticle(
        a.url(), a.cleanText(), a.headline(), a.byline(), a.publishDate(), a.outletName(), name());
  }
}
