package app.beat.calendar;

import app.beat.calendar.feed.FeedItem;
import app.beat.calendar.feed.FeedSource;
import app.beat.client.ClientRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified calendar feed that aggregates across every registered {@link FeedSource} (posts, reports,
 * standalone calendar_events, …) and returns a single chronologically-sorted list.
 *
 * <p>Spec: see Option C in the development chat. Sources own their own SQL and projection; this
 * controller is the seam that binds them together at read time.
 */
@RestController
public class CalendarFeedController {

  private final List<FeedSource> sources;
  private final ClientRepository clients;

  public CalendarFeedController(List<FeedSource> sources, ClientRepository clients) {
    this.sources = sources;
    this.clients = clients;
  }

  public record FeedResponse(List<FeedItem> items, List<String> available_types) {}

  @GetMapping("/v1/calendar/feed")
  public FeedResponse feed(
      @RequestParam(required = false) UUID client_id,
      @RequestParam(required = false) String types,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (client_id != null) {
      clients
          .findInWorkspace(ctx.workspaceId(), client_id)
          .orElseThrow(() -> AppException.notFound("Client"));
    }
    Set<String> requested = parseTypes(types);
    var out = new java.util.ArrayList<FeedItem>();
    for (FeedSource source : sources) {
      // Intersect requested types with what this source produces. Empty requested = all.
      if (!requested.isEmpty() && source.types().stream().noneMatch(requested::contains)) {
        continue;
      }
      var items = source.fetch(ctx.workspaceId(), client_id, from, to);
      if (requested.isEmpty()) {
        out.addAll(items);
      } else {
        for (FeedItem i : items) if (requested.contains(i.type())) out.add(i);
      }
    }
    out.sort(Comparator.comparing(FeedItem::occurs_at));

    Set<String> available = new LinkedHashSet<>();
    for (FeedSource s : sources) available.addAll(s.types());
    return new FeedResponse(out, List.copyOf(available));
  }

  private static Set<String> parseTypes(String csv) {
    if (csv == null || csv.isBlank()) return Set.of();
    Set<String> out = new LinkedHashSet<>();
    for (String t : csv.split(",")) {
      String trimmed = t.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }
}
