package app.beat.alerts;

/** Canonical alert type identifiers per docs/16-client-dashboard.md §Alert types. */
public final class AlertTypes {

  private AlertTypes() {}

  public static final String REPORT_OVERDUE = "report.overdue";
  public static final String EXTRACTION_FAILED = "extraction.failed";
  public static final String CONTEXT_STALE = "context.stale";
  public static final String SETUP_INCOMPLETE = "client.setup_incomplete";
  public static final String HEALTHY = "client.healthy";
  // Phase 1 deferred: inbox.pending, pitch.awaiting_reply, attribution.suggested,
  // report.unpublished — those depend on tables that don't exist yet.

  // Severity literals (mirror the migration CHECK).
  public static final String RED = "red";
  public static final String AMBER = "amber";
  public static final String BLUE = "blue";
  public static final String GREEN = "green";

  // Thresholds per docs/16 §Alert thresholds.
  public static final int CONTEXT_STALE_DAYS = 60;
  public static final int REPORT_OVERDUE_GRACE_DAYS = 1;

  /** Numeric severity per docs/16 §Severity scoring. Used for sort / aggregate counts. */
  public static int score(String severity) {
    return switch (severity) {
      case RED -> 100;
      case AMBER -> 10;
      case BLUE -> 1;
      default -> 0;
    };
  }
}
