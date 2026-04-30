package app.beat.admin;

import app.beat.auth.User;
import app.beat.auth.UserRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Founder admin endpoints. Gated by an env-driven email allowlist (beat.admin.emails). Read-only —
 * pulls aggregates from activity_events and other tables.
 */
@RestController
public class AdminController {

  private final UserRepository users;
  private final JdbcClient jdbc;
  private final Set<String> allowedEmails;

  public AdminController(
      UserRepository users, JdbcClient jdbc, @Value("${beat.admin.emails:}") String adminEmails) {
    this.users = users;
    this.jdbc = jdbc;
    this.allowedEmails =
        Arrays.stream(adminEmails.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(String::toLowerCase)
            .collect(Collectors.toUnmodifiableSet());
  }

  public record DailyCount(LocalDate day, long count) {}

  public record WorkspaceCost(
      UUID workspace_id, String workspace_name, long extractions, long reports, double cost_usd) {}

  public record ErrorClass(String error_class, long count) {}

  public record AdminDashboardDto(
      List<DailyCount> daily_extractions,
      List<DailyCount> daily_reports,
      List<WorkspaceCost> workspace_costs,
      Long p95_extraction_ms,
      List<ErrorClass> top_errors) {}

  @GetMapping("/v1/admin/dashboard")
  public AdminDashboardDto dashboard(HttpServletRequest req) {
    requireAdmin(req);
    return new AdminDashboardDto(
        dailyEventCounts("report.coverage_extracted"),
        dailyEventCounts("report.generated"),
        workspaceCosts(),
        p95ExtractionMs(),
        topErrorClasses());
  }

  /** Boolean helper for the SPA to decide whether to show the /admin link. */
  @GetMapping("/v1/admin/whoami")
  public Map<String, Boolean> whoami(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return Map.of("is_admin", isAdmin(ctx.userId()));
  }

  private void requireAdmin(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (!isAdmin(ctx.userId())) {
      throw AppException.forbidden("Admin only.");
    }
  }

  private boolean isAdmin(UUID userId) {
    if (allowedEmails.isEmpty()) return false;
    return users
        .findById(userId)
        .map(User::email)
        .map(String::toLowerCase)
        .map(allowedEmails::contains)
        .orElse(false);
  }

  private List<DailyCount> dailyEventCounts(String kind) {
    return jdbc.sql(
            """
            SELECT (occurred_at AT TIME ZONE 'UTC')::date AS d, COUNT(*) AS n
            FROM activity_events
            WHERE kind = :k AND occurred_at > now() - INTERVAL '30 days'
            GROUP BY d ORDER BY d
            """)
        .param("k", kind)
        .query((rs, n) -> new DailyCount(rs.getDate("d").toLocalDate(), rs.getLong("n")))
        .list();
  }

  private List<WorkspaceCost> workspaceCosts() {
    // Cost USD comes from extraction_runs.cost_micros if the table exists; otherwise zero.
    return jdbc.sql(
            """
            SELECT w.id AS workspace_id, w.name AS workspace_name,
              COUNT(*) FILTER (WHERE ae.kind = 'report.coverage_extracted') AS extractions,
              COUNT(*) FILTER (WHERE ae.kind = 'report.generated') AS reports,
              COALESCE(SUM(((ae.metadata->>'cost_usd')::numeric)) FILTER (WHERE ae.metadata ? 'cost_usd'), 0) AS cost_usd
            FROM workspaces w
            LEFT JOIN activity_events ae
              ON ae.workspace_id = w.id AND ae.occurred_at > now() - INTERVAL '30 days'
            WHERE w.deleted_at IS NULL
            GROUP BY w.id, w.name
            ORDER BY cost_usd DESC NULLS LAST, extractions DESC
            LIMIT 50
            """)
        .query(
            (rs, n) ->
                new WorkspaceCost(
                    rs.getObject("workspace_id", UUID.class),
                    rs.getString("workspace_name"),
                    rs.getLong("extractions"),
                    rs.getLong("reports"),
                    rs.getDouble("cost_usd")))
        .list();
  }

  private Long p95ExtractionMs() {
    return jdbc.sql(
            """
            SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95
            FROM activity_events
            WHERE kind = 'report.coverage_extracted'
              AND duration_ms IS NOT NULL
              AND occurred_at > now() - INTERVAL '7 days'
            """)
        .query((rs, n) -> (Long) rs.getObject("p95"))
        .optional()
        .orElse(null);
  }

  private List<ErrorClass> topErrorClasses() {
    return jdbc.sql(
            """
            SELECT COALESCE(metadata->>'error_class', 'unknown') AS error_class, COUNT(*) AS n
            FROM activity_events
            WHERE kind = 'extraction.failed' AND occurred_at > now() - INTERVAL '30 days'
            GROUP BY error_class
            ORDER BY n DESC
            LIMIT 10
            """)
        .query((rs, n) -> new ErrorClass(rs.getString("error_class"), rs.getLong("n")))
        .list();
  }
}
