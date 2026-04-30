package app.beat.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Standalone calendar event row. Things that don't fit in {@code owned_posts}, {@code reports},
 * {@code coverage_items}, etc. — embargoes, launches, earnings calls, meetings, blackouts,
 * milestones, and a catch-all 'other'.
 */
public record CalendarEvent(
    UUID id,
    UUID workspaceId,
    UUID clientId,
    String eventType,
    String title,
    String description,
    Instant occursAt,
    Instant endsAt,
    boolean allDay,
    String url,
    String color,
    UUID createdByUserId,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static final List<String> VALID_TYPES =
      List.of("embargo", "launch", "earnings", "meeting", "blackout", "milestone", "other");

  public static boolean isValidType(String t) {
    return t != null && VALID_TYPES.contains(t);
  }
}
