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
}
