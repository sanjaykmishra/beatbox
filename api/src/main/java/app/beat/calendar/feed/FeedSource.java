package app.beat.calendar.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A pluggable source of unified-calendar feed items (posts, reports, ad-hoc events, etc.). */
public interface FeedSource {

  /**
   * The set of feed-item type identifiers this source can produce. Used for filtering: when the
   * caller passes {@code types=[…]}, only sources whose types intersect are queried.
   */
  List<String> types();

  /**
   * Fetch feed items in the {@code [from, to)} window for the workspace, optionally narrowed to a
   * single client.
   */
  List<FeedItem> fetch(UUID workspaceId, UUID clientId, Instant from, Instant to);
}
