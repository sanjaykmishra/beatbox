package app.beat.alerts;

import app.beat.client.Client;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Pure(ish) computation of the alert set for one client. Stateless aside from the JdbcClient
 * lookups it needs. Per docs/16-client-dashboard.md §Alert types — Phase 1 covers:
 *
 * <ul>
 *   <li>{@code report.overdue} (red)
 *   <li>{@code extraction.failed} (amber)
 *   <li>{@code context.stale} (amber)
 *   <li>{@code client.setup_incomplete} (blue) — special: replaces the column UI-side
 *   <li>{@code client.healthy} (green) — fallback when no other alerts apply
 * </ul>
 *
 * <p>{@code inbox.pending}, pitch alerts, and attribution alerts are deferred until those tables
 * exist (Phase 3+).
 *
 * <p>Per CLAUDE.md guardrail #8, the {@code setup_incomplete} gate and the {@code
 * extraction.failed} count both consider {@code coverage_items} AND {@code social_mentions}: a
 * workspace whose only content is social mentions is set up, and a failed social-mention scrape is
 * just as much an extraction failure as a failed article fetch.
 */
@Component
public class AlertEngine {

  private final ClientContextRepository contexts;
  private final ReportRepository reports;
  private final JdbcClient jdbc;

  public AlertEngine(ClientContextRepository contexts, ReportRepository reports, JdbcClient jdbc) {
    this.contexts = contexts;
    this.reports = reports;
    this.jdbc = jdbc;
  }

  public List<ComputedAlert> computeFor(Client client) {
    List<ComputedAlert> out = new ArrayList<>();

    // 1. setup_incomplete — special; replaces the column. Skip if dismissed.
    boolean hasContext = contexts.findByClient(client.id()).filter(c -> !c.isEmpty()).isPresent();
    boolean hasCoverage = hasAnyCoverageOrSocialMention(client.id());
    boolean hasCadence = client.defaultCadence() != null && !client.defaultCadence().isBlank();
    if (!hasContext && !hasCoverage && !hasCadence && client.setupDismissedAt() == null) {
      out.add(setupIncomplete(client));
      // Setup card replaces the column; nothing else to compute usefully.
      return out;
    }

    // 2. report.overdue — cadence-derived. Only if a cadence is set.
    overdueAlert(client).ifPresent(out::add);

    // 3. extraction.failed — scope to the latest non-deleted report for this client. Counting
    // across all historical reports compounds noise as the user generates more reports; what they
    // care about is "this run still has problems." Per CLAUDE.md guardrail #8, "what's been
    // written about a client" must include both articles AND social mentions in that report.
    Optional<java.util.UUID> latestReport = latestReportId(client.id());
    if (latestReport.isPresent()) {
      int failed = failedCountInReport(latestReport.get());
      if (failed > 0) {
        out.add(extractionFailed(client, failed, latestReport.get()));
      }
    }

    // 4. context.stale — context older than threshold (skip if never set).
    contexts
        .findByClient(client.id())
        .filter(c -> c.updatedAt() != null)
        .filter(c -> ageDays(c.updatedAt()) >= AlertTypes.CONTEXT_STALE_DAYS)
        .ifPresent(c -> out.add(contextStale(client, ageDays(c.updatedAt()))));

    // 5. healthy — explicit green when nothing else applies (other than setup, handled above).
    if (out.isEmpty()) out.add(healthy(client));
    return out;
  }

  /**
   * Whether the client has any captured content — articles or social mentions. Drives the {@code
   * setup_incomplete} gate. Per CLAUDE.md guardrail #8, a workspace whose only captured content is
   * a Bluesky mention shouldn't get a "finish setup" alert; mentions are first-class.
   */
  private boolean hasAnyCoverageOrSocialMention(java.util.UUID clientId) {
    Boolean hasArticle =
        jdbc.sql(
                """
                SELECT EXISTS(
                  SELECT 1 FROM coverage_items ci
                  JOIN reports r ON r.id = ci.report_id
                  WHERE r.client_id = :c AND r.deleted_at IS NULL
                )
                """)
            .param("c", clientId)
            .query(Boolean.class)
            .single();
    if (Boolean.TRUE.equals(hasArticle)) return true;
    Boolean hasMention =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM social_mentions WHERE client_id = :c)")
            .param("c", clientId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(hasMention);
  }

  /** Failed coverage_items + social_mentions in a single report. */
  private int failedCountInReport(java.util.UUID reportId) {
    Integer articleFails =
        jdbc.sql(
                """
                SELECT count(*) FROM coverage_items
                WHERE report_id = :r AND extraction_status = 'failed'
                """)
            .param("r", reportId)
            .query(Integer.class)
            .single();
    Integer socialFails =
        jdbc.sql(
                """
                SELECT count(*) FROM social_mentions
                WHERE report_id = :r AND extraction_status = 'failed'
                """)
            .param("r", reportId)
            .query(Integer.class)
            .single();
    return (articleFails == null ? 0 : articleFails) + (socialFails == null ? 0 : socialFails);
  }

  /** Most recent non-deleted report for the client; the one the user is currently working on. */
  private Optional<java.util.UUID> latestReportId(java.util.UUID clientId) {
    return jdbc.sql(
            """
            SELECT id FROM reports
            WHERE client_id = :c AND deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT 1
            """)
        .param("c", clientId)
        .query((rs, i) -> rs.getObject("id", java.util.UUID.class))
        .optional();
  }

  private static long ageDays(Instant t) {
    return ChronoUnit.DAYS.between(t, Instant.now());
  }

  // ---- Alert constructors (UI copy lives here so the frontend doesn't re-derive). ----

  private ComputedAlert setupIncomplete(Client c) {
    return new ComputedAlert(
        AlertTypes.SETUP_INCOMPLETE,
        AlertTypes.BLUE,
        1,
        "Setup",
        "Finish setting up " + c.name(),
        "Add context, set a cadence, paste your first coverage URLs.",
        "Set up →",
        "/clients/" + c.id() + "/edit");
  }

  private Optional<ComputedAlert> overdueAlert(Client c) {
    if (c.defaultCadence() == null) return Optional.empty();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate expectedPeriodEnd = previousPeriodEnd(today, c.defaultCadence());
    if (expectedPeriodEnd == null) return Optional.empty();
    long graceCutoffEpochDay =
        expectedPeriodEnd.toEpochDay() + AlertTypes.REPORT_OVERDUE_GRACE_DAYS;
    if (today.toEpochDay() <= graceCutoffEpochDay) return Optional.empty();

    Optional<Report> latest = latestReportEndingBy(c.id(), expectedPeriodEnd);
    if (latest.isPresent() && !latest.get().periodEnd().isBefore(expectedPeriodEnd)) {
      return Optional.empty();
    }
    long daysLate = today.toEpochDay() - graceCutoffEpochDay;
    String monthLabel =
        expectedPeriodEnd
            .getMonth()
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ROOT);
    String periodIso =
        expectedPeriodEnd.getYear()
            + "-"
            + String.format("%02d", expectedPeriodEnd.getMonthValue());
    return Optional.of(
        new ComputedAlert(
            AlertTypes.REPORT_OVERDUE,
            AlertTypes.RED,
            1,
            "Report " + daysLate + "d late",
            monthLabel + " report is " + daysLate + " day" + (daysLate == 1 ? "" : "s") + " late",
            "Period closed " + expectedPeriodEnd + "; not yet generated.",
            "Start →",
            "/clients/" + c.id() + "/reports/new?period=" + periodIso));
  }

  /** End date of the most recent expected reporting period for this cadence. */
  public static LocalDate previousPeriodEnd(LocalDate today, String cadence) {
    if (cadence == null) return null;
    return switch (cadence) {
      case "weekly" -> today.minusDays(today.getDayOfWeek().getValue() % 7);
      case "biweekly" -> today.minusDays(((int) today.toEpochDay() % 14));
      case "monthly" -> today.withDayOfMonth(1).minusDays(1); // last day of previous month
      case "quarterly" -> {
        int month = today.getMonthValue();
        int prevQuarterEndMonth = ((month - 1) / 3) * 3; // 0,3,6,9
        if (prevQuarterEndMonth == 0) {
          yield LocalDate.of(today.getYear() - 1, 12, 31);
        }
        yield today.withMonth(prevQuarterEndMonth).withDayOfMonth(1).plusMonths(1).minusDays(1);
      }
      default -> null;
    };
  }

  private Optional<Report> latestReportEndingBy(java.util.UUID clientId, LocalDate ceiling) {
    return reports.findLatestForClientUpTo(clientId, ceiling);
  }

  private ComputedAlert extractionFailed(Client c, int failed, java.util.UUID reportId) {
    // Prefer linking to the report builder for the report that owns most of the failed items —
    // that's where the FailedPanel UI lets the user click Retry / Edit / Remove on each row. Fall
    // back to the client-edit page when no report-attached failures exist (e.g., social mentions
    // captured into the client pool with no report yet).
    String actionPath = reportId != null ? "/reports/" + reportId : "/clients/" + c.id() + "/edit";
    return new ComputedAlert(
        AlertTypes.EXTRACTION_FAILED,
        AlertTypes.AMBER,
        failed,
        failed + " failed",
        failed + " extraction" + (failed == 1 ? "" : "s") + " failed",
        "Tap to review and retry.",
        "Review →",
        actionPath);
  }

  private ComputedAlert contextStale(Client c, long days) {
    return new ComputedAlert(
        AlertTypes.CONTEXT_STALE,
        AlertTypes.AMBER,
        1,
        "Context stale",
        "Context is " + days + " days old",
        "Review and refresh client context.",
        "Open →",
        "/clients/" + c.id() + "/context");
  }

  private ComputedAlert healthy(Client c) {
    return new ComputedAlert(
        AlertTypes.HEALTHY,
        AlertTypes.GREEN,
        1,
        "All caught up",
        c.name() + " is all caught up",
        null,
        null,
        null);
  }
}
