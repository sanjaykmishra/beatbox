package app.beat.client;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
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

  public ClientController(
      ClientRepository clients, AuditService audit, ActivityRecorder activity, PlanGuard guard) {
    this.clients = clients;
    this.audit = audit;
    this.activity = activity;
    this.guard = guard;
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

  public record ListResponse(List<ClientDto> items, String next_cursor) {}

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
    var items =
        clients.listByWorkspace(ctx.workspaceId(), 100).stream().map(ClientDto::from).toList();
    return new ListResponse(items, null);
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
