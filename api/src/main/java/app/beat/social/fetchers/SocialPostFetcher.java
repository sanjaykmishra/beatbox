package app.beat.social.fetchers;

import java.util.Optional;

/**
 * One implementation per platform. Fetches a public post from the given URL and returns a {@link
 * FetchedSocialPost} the worker hands to the LLM extractor. Implementations must NOT throw on "post
 * not found" or "blocked by platform" — return {@link Optional#empty()} so the worker can mark the
 * row {@code failed} with a useful message.
 *
 * <p>Per docs/17-phase-1-5-social.md §17.1, every platform has its own fetcher. {@link
 * BlueskyFetcher} and {@link RedditFetcher} use free public APIs; X / LinkedIn require a paid
 * scraping service in Phase 1.5.
 */
public interface SocialPostFetcher {

  /** The {@code social_mentions.platform} value this fetcher serves. */
  String platform();

  Optional<FetchedSocialPost> fetch(String url);
}
