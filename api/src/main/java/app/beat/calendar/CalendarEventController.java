package app.beat.calendar;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD on the standalone {@code calendar_events} table. The unified calendar feed (which unions
 * across owned_posts, reports, etc.) lives at {@link CalendarFeedController}.
 */
@RestController
@RequestMapping("/v1/calendar/events")
public class CalendarEventController {

  private final CalendarEventRepository events;
  private final ClientRepository clients;
  private final ActivityRecorder activity;

  public CalendarEventController(
      CalendarEventRepository events, ClientRepository clients, ActivityRecorder activity) {
    this.events = events;
    this.clients = clients;
    this.activity = activity;
  }

  // ===================== DTOs =====================

  public record CalendarEventDto(
      UUID id,
      UUID workspace_id,
      UUID client_id,
      String event_type,
      String title,
      String description,
      Instant occurs_at,
      Instant ends_at,
      boolean all_day,
      String url,
      String color,
      UUID created_by_user_id,
      Instant created_at,
      Instant updated_at) {

    static CalendarEventDto from(CalendarEvent e) {
      return new CalendarEventDto(
          e.id(),
          e.workspaceId(),
          e.clientId(),
          e.eventType(),
          e.title(),
          e.description(),
          e.occursAt(),
          e.endsAt(),
          e.allDay(),
          e.url(),
          e.color(),
          e.createdByUserId(),
          e.createdAt(),
          e.updatedAt());
    }
  }

  public record CreateRequest(
      UUID client_id,
      @NotNull @Pattern(regexp = "embargo|launch|earnings|meeting|blackout|milestone|other")
          String event_type,
      @NotNull @Size(min = 1, max = 200) String title,
      @Size(max = 2000) String description,
      @NotNull Instant occurs_at,
      Instant ends_at,
      Boolean all_day,
      @Size(max = 500) String url,
      @Pattern(regexp = "^[0-9A-Fa-f]{6}$", message = "must be 6 hex digits without #")
          String color) {}

  public record UpdateRequest(
      UUID client_id,
      @Pattern(regexp = "embargo|launch|earnings|meeting|blackout|milestone|other")
          String event_type,
      @Size(min = 1, max = 200) String title,
      @Size(max = 2000) String description,
      Instant occurs_at,
      Instant ends_at,
      Boolean all_day,
      @Size(max = 500) String url,
      @Pattern(regexp = "^[0-9A-Fa-f]{6}$") String color) {}

  public record ListResponse(List<CalendarEventDto> items) {}

  // ===================== Endpoints =====================

  /** List standalone calendar events. The unified feed lives at GET /v1/calendar/feed. */
  @GetMapping
  public ListResponse list(
      @RequestParam(required = false) UUID client_id,
      @RequestParam(required = false) String event_type,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (client_id != null) requireClient(ctx.workspaceId(), client_id);
    List<String> types = event_type == null ? List.of() : List.of(event_type);
    var rows = events.listInWindow(ctx.workspaceId(), client_id, types, from, to);
    return new ListResponse(rows.stream().map(CalendarEventDto::from).toList());
  }

  @GetMapping("/{id}")
  public CalendarEventDto get(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return CalendarEventDto.from(
        events
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("CalendarEvent")));
  }

  @PostMapping
  public ResponseEntity<CalendarEventDto> create(
      @Valid @RequestBody CreateRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (body.client_id() != null) requireClient(ctx.workspaceId(), body.client_id());
    if (body.ends_at() != null && body.ends_at().isBefore(body.occurs_at())) {
      throw AppException.badRequest(
          "/errors/invalid-range", "Invalid range", "ends_at must be at or after occurs_at.");
    }
    CalendarEvent e =
        events.insert(
            ctx.workspaceId(),
            body.client_id(),
            body.event_type(),
            body.title(),
            body.description(),
            body.occurs_at(),
            body.ends_at(),
            Boolean.TRUE.equals(body.all_day()),
            body.url(),
            body.color(),
            ctx.userId());
    var meta = new HashMap<String, Object>();
    meta.put("event_type", e.eventType());
    if (e.clientId() != null) meta.put("client_id", e.clientId().toString());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.CALENDAR_EVENT_CREATED,
        "calendar_event",
        e.id(),
        meta);
    return ResponseEntity.status(HttpStatus.CREATED).body(CalendarEventDto.from(e));
  }

  @PatchMapping("/{id}")
  public CalendarEventDto update(
      @PathVariable UUID id, @Valid @RequestBody UpdateRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var existing =
        events
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("CalendarEvent"));
    if (body.client_id() != null) requireClient(ctx.workspaceId(), body.client_id());
    Instant newOccurs = body.occurs_at() != null ? body.occurs_at() : existing.occursAt();
    Instant newEnds = body.ends_at() != null ? body.ends_at() : existing.endsAt();
    if (newEnds != null && newEnds.isBefore(newOccurs)) {
      throw AppException.badRequest(
          "/errors/invalid-range", "Invalid range", "ends_at must be at or after occurs_at.");
    }
    CalendarEvent updated =
        events.update(
            id,
            body.client_id(),
            body.event_type(),
            body.title(),
            body.description(),
            body.occurs_at(),
            body.ends_at(),
            body.all_day(),
            body.url(),
            body.color());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.CALENDAR_EVENT_UPDATED,
        "calendar_event",
        id,
        Map.of());
    return CalendarEventDto.from(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    events
        .findInWorkspace(ctx.workspaceId(), id)
        .orElseThrow(() -> AppException.notFound("CalendarEvent"));
    events.softDelete(id);
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.CALENDAR_EVENT_DELETED,
        "calendar_event",
        id,
        Map.of());
    return ResponseEntity.noContent().build();
  }

  private void requireClient(UUID workspaceId, UUID clientId) {
    clients
        .findInWorkspace(workspaceId, clientId)
        .orElseThrow(() -> AppException.notFound("Client"));
  }
}
