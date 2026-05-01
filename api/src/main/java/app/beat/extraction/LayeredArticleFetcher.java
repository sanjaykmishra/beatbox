package app.beat.extraction;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Tries Readability first (cheap, no quota), falls through to ScrapingBee (paid, JS-rendered,
 * paywall-bypassing) only if the cheap path returned nothing.
 */
@Component
@Primary
public class LayeredArticleFetcher implements ArticleFetcher {

  private static final Logger log = LoggerFactory.getLogger(LayeredArticleFetcher.class);

  private final ReadabilityFetcher readability;
  private final ScrapingBeeFetcher scrapingBee;

  public LayeredArticleFetcher(ReadabilityFetcher readability, ScrapingBeeFetcher scrapingBee) {
    this.readability = readability;
    this.scrapingBee = scrapingBee;
  }

  @Override
  public String name() {
    return "layered";
  }

  @Override
  public Optional<FetchedArticle> fetch(String url) {
    return fetchWithReason(url).article();
  }

  @Override
  public FetchOutcome fetchWithReason(String url) {
    Optional<FetchedArticle> r = readability.fetch(url);
    if (r.isPresent()) return new FetchOutcome(r, null);
    log.info(
        "readability empty for {}, falling through to scrapingbee (configured={})",
        url,
        scrapingBee.isConfigured());
    FetchOutcome sb = scrapingBee.fetchWithReason(url);
    if (sb.article().isPresent()) return sb;
    String reason =
        sb.reason() == null
            ? "readability_empty_and_scrapingbee_empty"
            : "readability_empty_then_" + sb.reason();
    return new FetchOutcome(Optional.empty(), reason);
  }
}
