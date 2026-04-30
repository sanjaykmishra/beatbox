package app.beat.extraction;

import java.util.Optional;

public interface ArticleFetcher {
  /** Returns a parsed article, or empty if this fetcher couldn't extract one. */
  Optional<FetchedArticle> fetch(String url);

  /** Tag for logging / debugging. */
  String name();
}
