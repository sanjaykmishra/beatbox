package app.beat.calendar.feed;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One item in the unified calendar feed. {@code type} is the discriminator the SPA switches on to
 * render the correct card. {@code source_id} is the UUID in the underlying source table — stable
 * per-type (a post id from owned_posts, a report id from reports, a row from calendar_events,
 * etc.).
 *
 * <p>{@code payload} carries type-specific extras (status pill text, platform array, period dates,
 * etc.) the SPA uses without requiring a second fetch.
 */
public record FeedItem(
    String id,
    String type,
    UUID source_id,
    UUID client_id,
    String title,
    String subtitle,
    Instant occurs_at,
    Instant ends_at,
    boolean all_day,
    String href,
    String color,
    Map<String, Object> payload) {

  /** Stable composite ID so the SPA can key list rendering across types. */
  public static String compose(String type, UUID sourceId) {
    return type + ":" + sourceId;
  }
}
