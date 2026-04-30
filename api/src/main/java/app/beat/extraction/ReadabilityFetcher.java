package app.beat.extraction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Direct fetch + jsoup-based content extraction. Cheap and works for ~80% of public articles. Falls
 * through to {@link ScrapingBeeFetcher} when content is too short or the site requires JS.
 */
@Component
public class ReadabilityFetcher implements ArticleFetcher {

  private static final Logger log = LoggerFactory.getLogger(ReadabilityFetcher.class);
  private static final int MIN_CONTENT_CHARS = 400;
  private static final List<String> NOISE_SELECTORS =
      List.of("script", "style", "nav", "header", "footer", "aside", "form", "noscript");
  private static final List<String> CONTENT_SELECTORS =
      List.of(
          "article",
          "main",
          "[role=main]",
          "div.article-content",
          "div.post-content",
          "div.entry-content",
          "div#content",
          "div.content");

  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(8))
          .build();

  @Override
  public String name() {
    return "readability";
  }

  @Override
  public Optional<FetchedArticle> fetch(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(15))
              .header("User-Agent", "Mozilla/5.0 (compatible; BeatBot/1.0; +https://beat.app/bot)")
              .header("Accept", "text/html,application/xhtml+xml")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        log.debug("readability: {} returned status {}", url, res.statusCode());
        return Optional.empty();
      }
      return parseHtml(url, res.body());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.debug("readability: {} fetch failed: {}", url, e.toString());
      return Optional.empty();
    }
  }

  Optional<FetchedArticle> parseHtml(String url, String html) {
    Document doc = Jsoup.parse(html, url);

    String headline = pickHeadline(doc);
    String byline = pickByline(doc);
    LocalDate publishDate = pickPublishDate(doc);
    String outletName = pickOutletName(doc);

    Element body = pickContentRoot(doc);
    NOISE_SELECTORS.forEach(sel -> body.select(sel).remove());
    String text = body.text().trim();

    if (text.length() < MIN_CONTENT_CHARS) {
      log.debug("readability: {} content too short ({} chars)", url, text.length());
      return Optional.empty();
    }
    return Optional.of(
        new FetchedArticle(url, text, headline, byline, publishDate, outletName, name()));
  }

  private static String pickHeadline(Document doc) {
    String og = doc.select("meta[property=og:title]").attr("content");
    if (!og.isBlank()) return og.trim();
    String tw = doc.select("meta[name=twitter:title]").attr("content");
    if (!tw.isBlank()) return tw.trim();
    Element h1 = doc.selectFirst("h1");
    if (h1 != null && !h1.text().isBlank()) return h1.text().trim();
    String t = doc.title();
    return t.isBlank() ? null : t.trim();
  }

  private static String pickByline(Document doc) {
    String meta = doc.select("meta[name=author]").attr("content");
    if (!meta.isBlank()) return meta.trim();
    String articleAuthor = doc.select("meta[property=article:author]").attr("content");
    if (!articleAuthor.isBlank() && !articleAuthor.startsWith("http")) return articleAuthor.trim();
    Element rel = doc.selectFirst("a[rel=author]");
    if (rel != null && !rel.text().isBlank()) return rel.text().trim();
    Element byline = doc.selectFirst(".byline, .author, .post-author");
    return byline == null ? null : byline.text().trim();
  }

  private static LocalDate pickPublishDate(Document doc) {
    String[] sources = {
      doc.select("meta[property=article:published_time]").attr("content"),
      doc.select("meta[name=date]").attr("content"),
      doc.select("meta[itemprop=datePublished]").attr("content"),
      Optional.ofNullable(doc.selectFirst("time[datetime]")).map(e -> e.attr("datetime")).orElse("")
    };
    for (String s : sources) {
      if (s == null || s.isBlank()) continue;
      try {
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
      } catch (Exception ignored) {
        try {
          return LocalDate.parse(s, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored2) {
          // fall through
        }
      }
    }
    return null;
  }

  private static String pickOutletName(Document doc) {
    String og = doc.select("meta[property=og:site_name]").attr("content");
    if (!og.isBlank()) return og.trim();
    String app = doc.select("meta[name=application-name]").attr("content");
    return app.isBlank() ? null : app.trim();
  }

  private static Element pickContentRoot(Document doc) {
    for (String sel : CONTENT_SELECTORS) {
      Elements el = doc.select(sel);
      if (!el.isEmpty()) {
        Element best = el.first();
        for (Element e : el) {
          if (e.text().length() > best.text().length()) best = e;
        }
        return best;
      }
    }
    return doc.body();
  }
}
