package app.beat.social.fetchers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedditFetcherTest {

  @Test
  void parsesCanonicalUrl() {
    var parsed =
        RedditFetcher.parseUrl(
                "https://www.reddit.com/r/SaaS/comments/1abc2de/anyone_else_getting_acme_emails/")
            .orElseThrow();
    assertThat(parsed[0]).isEqualTo("SaaS");
    assertThat(parsed[1]).isEqualTo("1abc2de");
  }

  @Test
  void parsesOldRedditUrl() {
    var parsed =
        RedditFetcher.parseUrl("https://old.reddit.com/r/programming/comments/xyz789/")
            .orElseThrow();
    assertThat(parsed[0]).isEqualTo("programming");
    assertThat(parsed[1]).isEqualTo("xyz789");
  }

  @Test
  void parsesUrlWithoutSlug() {
    var parsed = RedditFetcher.parseUrl("https://reddit.com/r/foo/comments/abc123").orElseThrow();
    assertThat(parsed[0]).isEqualTo("foo");
    assertThat(parsed[1]).isEqualTo("abc123");
  }

  @Test
  void parsesUrlWithQueryString() {
    var parsed =
        RedditFetcher.parseUrl(
                "https://www.reddit.com/r/foo/comments/abc123/title/?utm_source=share")
            .orElseThrow();
    assertThat(parsed[0]).isEqualTo("foo");
    assertThat(parsed[1]).isEqualTo("abc123");
  }

  @Test
  void rejectsCommentDeepLink() {
    // /r/foo/comments/POST/slug/COMMENT/ — too deep; a future fetcher handles those.
    assertThat(RedditFetcher.parseUrl("https://www.reddit.com/r/foo/comments/abc/title/def/"))
        .isEmpty();
  }

  @Test
  void rejectsNonCommentsPaths() {
    assertThat(RedditFetcher.parseUrl("https://reddit.com/r/foo/")).isEmpty();
    assertThat(RedditFetcher.parseUrl("https://reddit.com/user/me")).isEmpty();
    assertThat(RedditFetcher.parseUrl("https://reddit.com/")).isEmpty();
  }

  @Test
  void jsonUrlBuildsCorrectly() {
    assertThat(RedditFetcher.jsonUrlFor("SaaS", "1abc2de"))
        .isEqualTo("https://www.reddit.com/r/SaaS/comments/1abc2de/.json");
  }
}
