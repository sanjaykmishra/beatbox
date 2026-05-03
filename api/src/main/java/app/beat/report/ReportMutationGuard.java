package app.beat.report;

import app.beat.infra.AppException;

/**
 * Centralizes the "is this report mutable right now?" check. The lifecycle (per CLAUDE.md guardrail
 * #5) allows edits in {@code draft}, {@code ready}, {@code failed}; rejects {@code processing}
 * (mid-render — adds would race the worker) and {@code published} (terminal).
 *
 * <p>Apply this to every endpoint that mutates a report or its child rows (coverage items, social
 * mentions, executive summary). Without a single source of truth, the same five-line status check
 * drifts between handlers — exactly the failure mode CLAUDE.md "same idiom in two places" warns
 * about.
 */
public final class ReportMutationGuard {

  private ReportMutationGuard() {}

  /**
   * Throws 400 / 409 when the report can't currently accept edits. Otherwise returns silently.
   *
   * @param actionLabel short noun phrase for the error detail ("add URLs", "edit this item",
   *     "delete this item"). Surfaced verbatim to the user, so write it in plain English.
   */
  public static void assertEditable(Report r, String actionLabel) {
    switch (r.status()) {
      case "processing" ->
          throw AppException.badRequest(
              "/errors/report-in-flight",
              "Report is generating",
              "Wait for the current generation to finish before you " + actionLabel + ".");
      case "published" ->
          throw AppException.conflict(
              "/errors/report-published",
              "Report is published",
              "Published reports are locked. Duplicate the report to start a new one.");
      default -> {
        /* draft / ready / failed are all editable */
      }
    }
  }
}
