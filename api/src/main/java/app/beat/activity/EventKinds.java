package app.beat.activity;

/**
 * Canonical activity event kinds. New events get a constant here so the codebase isn't
 * stringly-typed.
 *
 * <p>Source of truth: docs/15-additions.md §15.2 "Standard event taxonomy".
 */
public final class EventKinds {

  private EventKinds() {}

  // Auth & workspace
  public static final String USER_SIGNED_UP = "user.signed_up";
  public static final String USER_LOGGED_IN = "user.logged_in";
  public static final String WORKSPACE_CREATED = "workspace.created";
  public static final String WORKSPACE_BRANDING_UPDATED = "workspace.branding_updated";

  // Clients
  public static final String CLIENT_CREATED = "client.created";
  public static final String CLIENT_UPDATED = "client.updated";
  public static final String CLIENT_DELETED = "client.deleted";
  public static final String CLIENT_CONTEXT_UPDATED = "client.context_updated";

  // Reports
  public static final String REPORT_CREATED = "report.created";
  public static final String REPORT_URLS_ADDED = "report.urls_added";
  public static final String REPORT_COVERAGE_EXTRACTED = "report.coverage_extracted";
  public static final String REPORT_COVERAGE_EDITED = "report.coverage_edited";
  public static final String REPORT_COVERAGE_RETRIED = "report.coverage_retried";
  public static final String REPORT_COVERAGE_DISMISSED = "report.coverage_dismissed";
  public static final String REPORT_SUMMARY_GENERATED = "report.summary_generated";
  public static final String REPORT_SUMMARY_EDITED = "report.summary_edited";
  public static final String REPORT_GENERATED = "report.generated";
  public static final String REPORT_PDF_DOWNLOADED = "report.pdf_downloaded";
  public static final String REPORT_SHARED = "report.shared";
  public static final String REPORT_SHARE_REVOKED = "report.share_revoked";

  // System / cost tracking
  public static final String LLM_CALL_COMPLETED = "llm.call_completed";
  public static final String EXTRACTION_FAILED = "extraction.failed";
  public static final String RENDER_FAILED = "render.failed";
}
