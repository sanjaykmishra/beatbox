package app.beat.workspace;

import app.beat.audit.AuditService;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workspace")
public class WorkspaceController {

  private final WorkspaceRepository workspaces;
  private final AuditService audit;

  public WorkspaceController(WorkspaceRepository workspaces, AuditService audit) {
    this.workspaces = workspaces;
    this.audit = audit;
  }

  public record WorkspaceDto(
      UUID id,
      String name,
      String slug,
      String logo_url,
      String primary_color,
      String plan,
      int plan_limit_clients,
      int plan_limit_reports_monthly,
      Instant trial_ends_at,
      UUID default_template_id) {
    public static WorkspaceDto from(Workspace w) {
      return new WorkspaceDto(
          w.id(),
          w.name(),
          w.slug(),
          w.logoUrl(),
          w.primaryColor(),
          w.plan(),
          w.planLimitClients(),
          w.planLimitReportsMonthly(),
          w.trialEndsAt(),
          w.defaultTemplateId());
    }
  }

  public record UpdateWorkspaceRequest(
      @Size(min = 1, max = 120) String name,
      String logo_url,
      @Pattern(regexp = "^[0-9a-fA-F]{6}$", message = "must be 6 hex digits without #")
          String primary_color,
      UUID default_template_id) {}

  @GetMapping
  public WorkspaceDto get(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return WorkspaceDto.from(
        workspaces
            .findById(ctx.workspaceId())
            .orElseThrow(() -> AppException.notFound("Workspace")));
  }

  @PatchMapping
  public WorkspaceDto update(
      @Valid @RequestBody UpdateWorkspaceRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Workspace updated =
        workspaces.update(
            ctx.workspaceId(),
            body.name(),
            body.logo_url(),
            body.primary_color(),
            body.default_template_id());
    audit.record(
        ctx.workspaceId(),
        ctx.userId(),
        "workspace.updated",
        "workspace",
        ctx.workspaceId(),
        Map.of(),
        req);
    return WorkspaceDto.from(updated);
  }
}
