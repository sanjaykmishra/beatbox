package app.beat.client;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.alerts.AlertService;
import app.beat.alerts.AlertTypes;
import app.beat.alerts.ClientAlert;
import app.beat.alerts.ClientAlertRepository;
import app.beat.audit.AuditService;
import app.beat.billing.PlanGuard;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/clients")
public class ClientController {

  private final ClientRepository clients;
  private final AuditService audit;
  private final ActivityRecorder activity;
  private final PlanGuard guard;
  private final AlertService alerts;
  private final ClientAlertRepository alertRepo;

  public ClientController(
      ClientRepository clients,
      AuditService audit,
      ActivityRecorder activity,
      PlanGuard guard,
      AlertService alerts,
      ClientAlertRepository alertRepo) {
    this.clients = clients;
    this.audit = audit;
    this.activity = activity;
    this.guard = guard;
    this.alerts = alerts;
    this.alertRepo = alertRepo;
  }

  public record ClientDto(
      UUID id,
      String name,
      String logo_url,
      String primary_color,
      String notes,
      String default_cadence,
      Instant created_at) {
    public static ClientDto from(Client c) {
      return new ClientDto(
          c.id(),
          c.name(),
          c.logoUrl(),
          c.primaryColor(),
          c.notes(),
          c.defaultCadence(),
          c.createdAt());
    }
  }

  public record ListResponse(
      List<ListItem> items, WorkspaceSummary workspace_summary, String next_cursor) {}

  public record ListItem(
      UUID id,
      String name,
      String logo_url,
      String primary_color,
      String default_cadence,
      Instant created_at,
      AlertsSummary alerts_summary) {}

  public record AlertsSummary(
      int total_score,
      Map<String, Integer> by_severity,
      List<TopBadge> top_badges,
      int overflow_count) {}

  public record TopBadge(String alert_type, String severity, String label) {}

  public record WorkspaceSummary(
      int total_clients, int total_attention_items, Map<String, Integer> by_severity) {}

  public record CreateClientRequest(
      @NotBlank @Size(max = 120) String name,
      String logo_url,
      @Pattern(regexp = "^[0-9a-fA-F]{6}$", message = "must be 6 hex digits without #")
          String primary_color,
      String notes,
      @Pattern(regexp = "weekly|biweekly|monthly|quarterly") String default_cadence) {}

  public record UpdateClientRequest(
      @Size(max = 120) String name,
      String logo_url,
      @Pattern(regexp = "^[0-9a-fA-F]{6}$", message = "must be 6 hex digits without #")
          String primary_color,
      String notes,
      @Pattern(regexp = "weekly|biweekly|monthly|quarterly") String default_cadence) {}

  @GetMapping
  public ListResponse list(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var clientList = clients.listByWorkspace(ctx.workspaceId(), 100);
    var alertsByClient =
        alertRepo.findByWorkspace(ctx.workspaceId()).stream()
            .collect(java.util.stream.Collectors.groupingBy(ClientAlert::clientId));

    int wsRed = 0, wsAmber = 0, wsBlue = 0;
    var rows = new java.util.ArrayList<ListItem>();
    for (var c : clientList) {
      var alerts = alertsByClient.getOrDefault(c.id(), List.of());
      int red = 0, amber = 0, blue = 0;
      for (var a : alerts) {
        switch (a.severity()) {
          case AlertTypes.RED -> red++;
          case AlertTypes.AMBER -> amber++;
          case AlertTypes.BLUE -> blue++;
          default -> {}
        }
      }
      wsRed += red;
      wsAmber += amber;
      wsBlue += blue;
      int score = red * 100 + amber * 10 + blue;
      var sorted =
          alerts.stream()
              .filter(a -> !AlertTypes.HEALTHY.equals(a.alertType()))
              .sorted(
                  java.util.Comparator.comparingInt(
                          (ClientAlert a) -> AlertTypes.score(a.severity()))
                      .reversed())
              .toList();
      var badges =
          sorted.stream()
              .limit(3)
              .map(a -> new TopBadge(a.alertType(), a.severity(), a.badgeLabel()))
              .toList();
      int overflow = Math.max(0, sorted.size() - 3);
      rows.add(
          new ListItem(
              c.id(),
              c.name(),
              c.logoUrl(),
              c.primaryColor(),
              c.defaultCadence(),
              c.createdAt(),
              new AlertsSummary(
                  score,
                  Map.of(AlertTypes.RED, red, AlertTypes.AMBER, amber, AlertTypes.BLUE, blue),
                  badges,
                  overflow)));
    }
    rows.sort(
        java.util.Comparator.comparingInt((ListItem i) -> i.alerts_summary().total_score())
            .reversed()
            .thenComparing(java.util.Comparator.comparing(ListItem::created_at).reversed())
            .thenComparing(i -> i.id().toString()));
    var ws =
        new WorkspaceSummary(
            rows.size(),
            wsRed + wsAmber + wsBlue,
            Map.of(AlertTypes.RED, wsRed, AlertTypes.AMBER, wsAmber, AlertTypes.BLUE, wsBlue));
    return new ListResponse(rows, ws, null);
  }

  @GetMapping("/{id}")
  public ClientDto get(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return clients
        .findInWorkspace(ctx.workspaceId(), id)
        .map(ClientDto::from)
        .orElseThrow(() -> AppException.notFound("Client"));
  }

  @PostMapping
  public ResponseEntity<ClientDto> create(
      @Valid @RequestBody CreateClientRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    guard.requireClientSlot(ctx.workspaceId());
    Client c =
        clients.insert(
            ctx.workspaceId(),
            body.name().trim(),
            body.logo_url(),
            body.primary_color(),
            body.notes(),
            body.default_cadence());
    audit.record(
        ctx.workspaceId(),
        ctx.userId(),
        "client.created",
        "client",
        c.id(),
        Map.of("name", c.name()),
        req);
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.CLIENT_CREATED, "client", c.id(), Map.of());
    alerts.recomputeFor(c.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(ClientDto.from(c));
  }

  @PatchMapping("/{id}")
  public ClientDto update(
      @PathVariable UUID id, @Valid @RequestBody UpdateClientRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Client updated =
        clients
            .update(
                ctx.workspaceId(),
                id,
                body.name(),
                body.logo_url(),
                body.primary_color(),
                body.notes(),
                body.default_cadence())
            .orElseThrow(() -> AppException.notFound("Client"));
    audit.record(ctx.workspaceId(), ctx.userId(), "client.updated", "client", id, Map.of(), req);
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.CLIENT_UPDATED, "client", id, Map.of());
    return ClientDto.from(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (!clients.softDelete(ctx.workspaceId(), id)) {
      throw AppException.notFound("Client");
    }
    audit.record(ctx.workspaceId(), ctx.userId(), "client.deleted", "client", id, Map.of(), req);
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.CLIENT_DELETED, "client", id, Map.of());
    return ResponseEntity.noContent().build();
  }
}
