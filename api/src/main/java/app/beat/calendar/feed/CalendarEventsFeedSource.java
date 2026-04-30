package app.beat.calendar.feed;

import app.beat.calendar.CalendarEvent;
import app.beat.calendar.CalendarEventRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Projects standalone {@code calendar_events} rows. Type = the row's event_type (embargo / launch /
 * earnings / meeting / blackout / milestone / other) so users can filter to just embargoes or just
 * blackouts in the SPA.
 */
@Component
public class CalendarEventsFeedSource implements FeedSource {

  private final CalendarEventRepository events;

  public CalendarEventsFeedSource(CalendarEventRepository events) {
    this.events = events;
  }

  @Override
  public List<String> types() {
    return CalendarEvent.VALID_TYPES;
  }

  @Override
  public List<FeedItem> fetch(UUID workspaceId, UUID clientId, Instant from, Instant to) {
    var rows = events.listInWindow(workspaceId, clientId, List.of(), from, to);
    return rows.stream().map(CalendarEventsFeedSource::project).toList();
  }

  private static FeedItem project(CalendarEvent e) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("event_type", e.eventType());
    if (e.description() != null) payload.put("description", e.description());
    return new FeedItem(
        FeedItem.compose(e.eventType(), e.id()),
        e.eventType(),
        e.id(),
        e.clientId(),
        e.title(),
        e.description(),
        e.occursAt(),
        e.endsAt(),
        e.allDay(),
        e.url(),
        e.color(),
        payload);
  }
}
