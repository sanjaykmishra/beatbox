package app.beat.extraction;

import java.util.Optional;

public interface ArticleFetcher {
  /** Returns a parsed article, or empty if this fetcher couldn't extract one. */
  Optional<FetchedArticle> fetch(String url);

  /** Tag for logging / debugging. */
  String name();

  /**
   * Like {@link #fetch(String)} but additionally explains why the fetch failed when it did. The
   * default implementation just wraps {@link #fetch} with an empty reason; the {@link
   * LayeredArticleFetcher} overrides this to surface which layer skipped and why so the worker can
   * write a useful {@code coverage_items.extraction_error} instead of bare {@code
   * fetch_returned_empty}.
   */
  default FetchOutcome fetchWithReason(String url) {
    return new FetchOutcome(fetch(url), null);
  }

  /**
   * Carrier for {@link #fetchWithReason} — empty {@code article} pairs with a non-null {@code
   * reason}.
   */
  record FetchOutcome(Optional<FetchedArticle> article, String reason) {
    public boolean isEmpty() {
      return article == null || article.isEmpty();
    }
  }
}
