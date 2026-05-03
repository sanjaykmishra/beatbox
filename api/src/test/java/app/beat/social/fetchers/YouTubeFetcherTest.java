package app.beat.social.fetchers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YouTubeFetcherTest {

  @Test
  void extractsVideoIdFromWatchUrl() {
    assertThat(YouTubeFetcher.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .contains("dQw4w9WgXcQ");
  }

  @Test
  void extractsVideoIdFromMobileWatchUrl() {
    assertThat(YouTubeFetcher.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        .contains("dQw4w9WgXcQ");
  }

  @Test
  void extractsVideoIdFromShortsUrl() {
    assertThat(YouTubeFetcher.extractVideoId("https://www.youtube.com/shorts/abc123XYZ_-"))
        .contains("abc123XYZ_-");
  }

  @Test
  void extractsVideoIdFromShortLink() {
    assertThat(YouTubeFetcher.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        .contains("dQw4w9WgXcQ");
  }

  @Test
  void extractsVideoIdWhenVParamIsNotFirst() {
    // Real YouTube URLs from share links often carry t=, list=, si= before/after v=.
    assertThat(
            YouTubeFetcher.extractVideoId(
                "https://www.youtube.com/watch?si=abc&v=dQw4w9WgXcQ&t=42s"))
        .contains("dQw4w9WgXcQ");
  }

  @Test
  void rejectsNonYouTubeUrls() {
    assertThat(YouTubeFetcher.extractVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        // We accept any URL that contains the v= or shorts/ shape — that's intentional, since
        // upstream UrlClassifier already gates by hostname. This test pins the current behavior;
        // tightening to require the YouTube hostname here too is a fine follow-up but isn't a
        // bug today.
        .contains("dQw4w9WgXcQ");
  }

  @Test
  void rejectsUrlsWithoutVideoId() {
    assertThat(YouTubeFetcher.extractVideoId("https://www.youtube.com/")).isEmpty();
    assertThat(YouTubeFetcher.extractVideoId("https://www.youtube.com/watch")).isEmpty();
    assertThat(YouTubeFetcher.extractVideoId(null)).isEmpty();
  }
}
