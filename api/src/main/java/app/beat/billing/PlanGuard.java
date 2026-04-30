package app.beat.billing;

import app.beat.client.ClientRepository;
import app.beat.infra.AppException;
import app.beat.report.ReportRepository;
import app.beat.workspace.Workspace;
import app.beat.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces plan-derived limits and trial expiry. Returns 402 (Payment Required) per the docs/04
 * convention for billing-blocked operations.
 */
@Component
public class PlanGuard {

  private final WorkspaceRepository workspaces;
  private final ClientRepository clients;
  private final ReportRepository reports;

  public PlanGuard(
      WorkspaceRepository workspaces, ClientRepository clients, ReportRepository reports) {
    this.workspaces = workspaces;
    this.clients = clients;
    this.reports = reports;
  }

  public Workspace requireActive(UUID workspaceId) {
    return workspaces.findById(workspaceId).orElseThrow(() -> AppException.notFound("Workspace"));
  }

  /** Throws 402 if the workspace's trial has expired without an active paid plan. */
  public void requireNotTrialExpired(Workspace ws) {
    if (!"trial".equals(ws.plan())) return;
    if (ws.trialEndsAt() != null && ws.trialEndsAt().isBefore(Instant.now())) {
      throw new AppException(
          HttpStatus.PAYMENT_REQUIRED,
          "/errors/trial-expired",
          "Trial expired",
          "Your 14-day trial has ended. Add a card from settings to continue creating reports.");
    }
  }

  /** Throws 402 if the workspace is at the client cap for its plan. */
  public void requireClientSlot(UUID workspaceId) {
    Workspace ws = requireActive(workspaceId);
    int active = clients.countActive(workspaceId);
    if (active >= ws.planLimitClients()) {
      throw new AppException(
          HttpStatus.PAYMENT_REQUIRED,
          "/errors/client-limit-reached",
          "Client limit reached",
          "Your "
              + ws.plan()
              + " plan is capped at "
              + ws.planLimitClients()
              + " clients. Upgrade from settings to add more.");
    }
  }

  /** Throws 402 if the workspace has hit its monthly report cap. */
  public void requireReportSlot(UUID workspaceId) {
    Workspace ws = requireActive(workspaceId);
    requireNotTrialExpired(ws);
    if (ws.planLimitReportsMonthly() == PlanLimits.UNLIMITED) return;
    int used = reports.countThisMonth(workspaceId);
    if (used >= ws.planLimitReportsMonthly()) {
      throw new AppException(
          HttpStatus.PAYMENT_REQUIRED,
          "/errors/report-limit-reached",
          "Monthly report limit reached",
          "You've used "
              + used
              + " of "
              + ws.planLimitReportsMonthly()
              + " reports this month. Upgrade from settings for unlimited reports.");
    }
  }
}
