package app.beat.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlClassifierTest {

  @Test
  void detectsXAndTwitterStatusUrls() {
    assertThat(UrlClassifier.platformOf("https://twitter.com/sarahperez/status/1234567890"))
        .contains("x");
    assertThat(UrlClassifier.platformOf("https://x.com/sarahperez/status/1234567890"))
        .contains("x");
    assertThat(UrlClassifier.platformOf("https://mobile.twitter.com/sarahperez/status/1234567890"))
        .contains("x");
    // A profile URL is not a post.
    assertThat(UrlClassifier.platformOf("https://x.com/sarahperez")).isEmpty();
  }

  @Test
  void detectsLinkedInPosts() {
    assertThat(
            UrlClassifier.platformOf(
                "https://www.linkedin.com/posts/jane-doe_some-update-activity-7123456789012345678-AbCd/"))
        .contains("linkedin");
    assertThat(
            UrlClassifier.platformOf(
                "https://www.linkedin.com/feed/update/urn:li:activity:7123456789/"))
        .contains("linkedin");
    assertThat(UrlClassifier.platformOf("https://www.linkedin.com/in/jane-doe/")).isEmpty();
  }

  @Test
  void detectsBlueskyAndThreads() {
    assertThat(UrlClassifier.platformOf("https://bsky.app/profile/example.bsky.social/post/abc123"))
        .contains("bluesky");
    assertThat(UrlClassifier.platformOf("https://www.threads.net/@example/post/CzAbC123"))
        .contains("threads");
  }

  @Test
  void detectsRedditPosts() {
    assertThat(
            UrlClassifier.platformOf(
                "https://www.reddit.com/r/PublicRelations/comments/abc123/some_title/"))
        .contains("reddit");
    assertThat(
            UrlClassifier.platformOf(
                "https://old.reddit.com/r/PublicRelations/comments/abc123/some_title/"))
        .contains("reddit");
    // Subreddit landing page is not a post.
    assertThat(UrlClassifier.platformOf("https://www.reddit.com/r/PublicRelations/")).isEmpty();
  }

  @Test
  void detectsYouTubeVideosAndShorts() {
    assertThat(UrlClassifier.platformOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .contains("youtube");
    assertThat(UrlClassifier.platformOf("https://www.youtube.com/shorts/abc123XYZ"))
        .contains("youtube");
  }

  @Test
  void substackNotesAreSocialButLongFormArticlesAreNot() {
    assertThat(UrlClassifier.platformOf("https://example.substack.com/notes/post/p-12345"))
        .contains("substack");
    // A long-form article on Substack should NOT classify as social — falls through to article
    // extractor.
    assertThat(UrlClassifier.platformOf("https://example.substack.com/p/some-essay-title"))
        .isEmpty();
  }

  @Test
  void mastodonOnKnownInstancesOnly() {
    assertThat(UrlClassifier.platformOf("https://mastodon.social/@example/110123456789"))
        .contains("mastodon");
    // Unknown instance: falls through. Phase 3+ may add a federated lookup.
    assertThat(UrlClassifier.platformOf("https://random-instance.example/@example/110123456789"))
        .isEmpty();
  }

  @Test
  void articleUrlsAreNotSocial() {
    assertThat(UrlClassifier.isSocialPost("https://techcrunch.com/2025/12/04/acme-raises-series-b"))
        .isFalse();
    assertThat(UrlClassifier.isSocialPost("https://www.wsj.com/articles/acme-acquires-foo"))
        .isFalse();
    assertThat(UrlClassifier.isSocialPost("https://www.theverge.com/2025/12/18/acme-launches"))
        .isFalse();
  }

  @Test
  void handlesNullsAndJunk() {
    assertThat(UrlClassifier.platformOf(null)).isEqualTo(Optional.empty());
    assertThat(UrlClassifier.platformOf("")).isEqualTo(Optional.empty());
    assertThat(UrlClassifier.platformOf("   ")).isEqualTo(Optional.empty());
    assertThat(UrlClassifier.platformOf("not a url")).isEqualTo(Optional.empty());
    assertThat(UrlClassifier.platformOf("ftp://x.com/sarahperez/status/1234")).isEmpty();
  }
}
