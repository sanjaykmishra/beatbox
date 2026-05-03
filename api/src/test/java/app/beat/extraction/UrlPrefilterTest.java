package app.beat.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlPrefilterTest {

  private final UrlPrefilter prefilter = new UrlPrefilter();

  @Test
  void rejectsHomepages() {
    assertThat(prefilter.reject("https://techcrunch.com/")).isPresent();
    assertThat(prefilter.reject("https://nytimes.com")).isPresent();
  }

  @Test
  void rejectsTagAndCategoryListings() {
    assertThat(prefilter.reject("https://techcrunch.com/tag/startups/")).isPresent();
    assertThat(prefilter.reject("https://techcrunch.com/category/ai/")).isPresent();
    assertThat(prefilter.reject("https://example.com/topics/finance")).isPresent();
  }

  @Test
  void rejectsAuthorIndexes() {
    assertThat(prefilter.reject("https://nytimes.com/author/sarah-perez/")).isPresent();
  }

  @Test
  void rejectsPaginatedArchives() {
    assertThat(prefilter.reject("https://techcrunch.com/page/4/")).isPresent();
  }

  @Test
  void rejectsFeedAndAuthLikePaths() {
    assertThat(prefilter.reject("https://example.com/feed/")).isPresent();
    assertThat(prefilter.reject("https://example.com/login")).isPresent();
  }

  @Test
  void passesThroughRealArticles() {
    assertThat(
            prefilter.reject("https://techcrunch.com/2026/04/01/acme-raises-series-b-30m-funding/"))
        .isEmpty();
    assertThat(
            prefilter.reject("https://nytimes.com/2026/01/15/business/acme-launches-platform.html"))
        .isEmpty();
  }

  @Test
  void rejectsObviouslyBrokenInputs() {
    assertThat(prefilter.reject(null)).isPresent();
    assertThat(prefilter.reject("")).isPresent();
    assertThat(prefilter.reject("not-a-url")).isPresent();
  }

  @Test
  void rejectsRedditSubredditListings() {
    assertThat(prefilter.reject("https://www.reddit.com/r/MachineLearning/hot")).isPresent();
    assertThat(prefilter.reject("https://www.reddit.com/r/python/new/")).isPresent();
    assertThat(prefilter.reject("https://reddit.com/r/funny")).isPresent();
    assertThat(prefilter.reject("https://old.reddit.com/r/news/top")).isPresent();
  }

  @Test
  void rejectsRedditNonPostPaths() {
    assertThat(prefilter.reject("https://www.reddit.com/user/spez")).isPresent();
    assertThat(prefilter.reject("https://www.reddit.com/u/spez")).isPresent();
  }

  @Test
  void allowsRedditPostUrls() {
    assertThat(
            prefilter.reject(
                "https://www.reddit.com/r/MachineLearning/comments/1abc2de/some_paper_discussion/"))
        .isEmpty();
    assertThat(prefilter.reject("https://reddit.com/r/foo/comments/abc123")).isEmpty();
  }

  @Test
  void rejectsLiveUpdatesAndTickers() {
    assertThat(
            prefilter.reject(
                "https://www.cnbc.com/2026/04/29/stock-market-today-live-updates.html"))
        .isPresent();
    assertThat(prefilter.reject("https://example.com/news/live/qatar-summit-2026")).isPresent();
    assertThat(prefilter.reject("https://example.com/markets/ticker/AAPL")).isPresent();
  }

  @Test
  void allowsRegularNewsArticles() {
    assertThat(
            prefilter.reject(
                "https://www.bloomberg.com/news/articles/2026-04-26/big-tech-earnings-week"))
        .isEmpty();
    assertThat(
            prefilter.reject(
                "https://techcrunch.com/2026/04/30/anthropic-potential-900b-valuation-round/"))
        .isEmpty();
  }

  @Test
  void rejectsBinaryAssetUrls() {
    // Direct image / PDF / archive pastes — the article fetcher would either return empty or
    // feed the LLM raw bytes.
    assertThat(prefilter.reject("https://example.com/image.png")).isPresent();
    assertThat(prefilter.reject("https://example.com/photos/headshot.JPG")).isPresent();
    assertThat(prefilter.reject("https://example.com/decks/q1-2026.pdf")).isPresent();
    assertThat(prefilter.reject("https://cdn.example.com/icons/logo.svg")).isPresent();
    assertThat(prefilter.reject("https://example.com/release.zip")).isPresent();
    assertThat(prefilter.reject("https://example.com/installer.dmg")).isPresent();
    assertThat(prefilter.reject("https://example.com/podcast.mp3")).isPresent();
    assertThat(prefilter.reject("https://example.com/clip.mp4")).isPresent();
    assertThat(prefilter.reject("https://example.com/data.csv")).isPresent();
  }

  @Test
  void doesNotMisclassifySlugsContainingExtensionLetters() {
    // 'png' / 'pdf' appear inside legitimate article slugs all the time. Make sure the regex
    // anchors at the path's tail (with optional query/fragment) and doesn't trigger on a
    // substring match.
    assertThat(prefilter.reject("https://example.com/2026/png-vs-webp-explained/")).isEmpty();
    assertThat(prefilter.reject("https://example.com/podcast-mp3-quality/")).isEmpty();
  }

  @Test
  void rejectsEmailMarketingTrackerHosts() {
    // The user-reported pastes plus a few other common email-platform trackers.
    assertThat(prefilter.reject("https://list-manage.com/track/click/abc123")).isPresent();
    assertThat(prefilter.reject("https://email.sendgrid.net/wf/click?upn=xyz")).isPresent();
    assertThat(prefilter.reject("https://example.us2.list-manage.com/subscribe/post")).isPresent();
    assertThat(prefilter.reject("https://r20.rs6.net/tn.jsp?abc")).isPresent();
    assertThat(prefilter.reject("https://email.klaviyo.com/click/123")).isPresent();
    assertThat(prefilter.reject("https://hubspotlinks.com/abc")).isPresent();
    assertThat(prefilter.reject("https://link.example.mandrillapp.com/c/abc")).isPresent();
  }

  @Test
  void rejectsTrackerPathShapesEvenOnUnknownHosts() {
    // Generic tracker path patterns catch hosts we haven't enumerated.
    assertThat(prefilter.reject("https://send.example.com/track/click/abc")).isPresent();
    assertThat(prefilter.reject("https://t.example.com/wf/click?upn=xyz")).isPresent();
    assertThat(prefilter.reject("https://m.example.com/ls/click?upn=zzz")).isPresent();
  }

  @Test
  void doesNotRejectShorteners() {
    // bit.ly / t.co etc. shorten legit article URLs constantly. The article fetcher follows
    // redirects, so we let these through and reject (or accept) the destination on its own
    // shape. Pinning current behavior; tighten if real misuse appears.
    assertThat(prefilter.reject("https://bit.ly/3xyz")).isEmpty();
    assertThat(prefilter.reject("https://t.co/abc")).isEmpty();
  }
}
