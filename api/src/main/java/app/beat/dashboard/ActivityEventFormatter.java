package app.beat.dashboard;

import app.beat.activity.ActivityEventRepository.ActivityEventRow;
import app.beat.activity.EventKinds;

/**
 * Renders an {@link ActivityEventRow} into the human label + tag the dashboard timeline shows.
 * Shared between the dashboard "Recent activity" slice and (later) the full activity page + weekly
 * digest narrative.
 */
public final class ActivityEventFormatter {

  public record ActivityRow(
      String kind, String label, String detail, String tagLabel, String tagTone) {}

  private ActivityEventFormatter() {}

  public static ActivityRow format(ActivityEventRow e) {
    String kind = e.kind();
    String detail = e.metadata() == null ? null : e.metadata().toString();
    return switch (kind) {
      case EventKinds.REPORT_CREATED ->
          new ActivityRow(kind, "Report created", null, "Report", "neutral");
      case EventKinds.REPORT_URLS_ADDED ->
          new ActivityRow(
              kind, "URLs added to report", countDetail(e, "count", "URL"), "Coverage", "neutral");
      case EventKinds.REPORT_COVERAGE_EXTRACTED ->
          new ActivityRow(kind, "Coverage extracted", durationDetail(e), "Done", "success");
      case EventKinds.REPORT_COVERAGE_EDITED ->
          new ActivityRow(kind, "Coverage edited", null, "Edit", "neutral");
      case EventKinds.REPORT_COVERAGE_RETRIED ->
          new ActivityRow(kind, "Extraction retried", null, "Retry", "neutral");
      case EventKinds.REPORT_COVERAGE_DISMISSED ->
          new ActivityRow(kind, "Coverage removed", null, "Removed", "neutral");
      case EventKinds.REPORT_SUMMARY_GENERATED ->
          new ActivityRow(kind, "Executive summary generated", null, "Summary", "neutral");
      case EventKinds.REPORT_SUMMARY_EDITED ->
          new ActivityRow(kind, "Executive summary edited", null, "Edit", "neutral");
      case EventKinds.REPORT_GENERATED ->
          new ActivityRow(kind, "Report generated", null, "Ready", "success");
      case EventKinds.REPORT_PDF_DOWNLOADED ->
          new ActivityRow(kind, "PDF downloaded", null, null, null);
      case EventKinds.REPORT_SHARED ->
          new ActivityRow(kind, "Share link created", null, "Shared", "info");
      case EventKinds.REPORT_SHARE_REVOKED ->
          new ActivityRow(kind, "Share link revoked", null, null, null);
      case EventKinds.CLIENT_CREATED -> new ActivityRow(kind, "Client created", null, null, null);
      case EventKinds.CLIENT_UPDATED -> new ActivityRow(kind, "Client updated", null, null, null);
      case EventKinds.CLIENT_CONTEXT_UPDATED ->
          new ActivityRow(kind, "Context updated", null, "Context", "info");
      case EventKinds.EXTRACTION_FAILED ->
          new ActivityRow(kind, "Extraction failed", detail, "Failed", "danger");
      case EventKinds.RENDER_FAILED ->
          new ActivityRow(kind, "Render failed", detail, "Failed", "danger");
      default -> new ActivityRow(kind, kind, null, null, null);
    };
  }

  private static String countDetail(ActivityEventRow e, String key, String unit) {
    if (e.metadata() == null) return null;
    Object n = e.metadata().get(key);
    if (n == null) return null;
    return n + " " + unit + ("1".equals(n.toString()) ? "" : "s");
  }

  private static String durationDetail(ActivityEventRow e) {
    if (e.durationMs() == null) return null;
    int ms = e.durationMs();
    return ms < 1000 ? ms + " ms" : (ms / 1000) + " s";
  }
}
