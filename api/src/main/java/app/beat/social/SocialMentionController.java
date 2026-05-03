package app.beat.social;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edit / delete / retry endpoints for social mentions on a draft report. Mirrors {@link
 * app.beat.coverage.CoverageController}'s shape — same is_user_edited semantics, same activity
 * events. Adding a social mention is handled by {@code POST /v1/reports/:id/coverage} (the unified
 * URL paste endpoint dispatches by {@link UrlClassifier}).
 *
 * <p>Spec: docs/17-phase-1-5-social.md §17.1 "API additions".
 */
@RestController
public class SocialMentionController {

  private final ReportRepository reports;
  private final SocialMentionRepository mentions;
  private final SocialExtractionJobRepository jobs;
  private final ActivityRecorder activity;

  public SocialMentionController(
      ReportRepository reports,
      SocialMentionRepository mentions,
      SocialExtractionJobRepository jobs,
      ActivityRecorder activity) {
    this.reports = reports;
    this.mentions = mentions;
    this.jobs = jobs;
    this.activity = activity;
  }

  public record EditSocialMentionRequest(
      String summary,
      @Pattern(regexp = "positive|neutral|negative|mixed") String sentiment,
      String sentiment_rationale,
      @Pattern(regexp = "feature|mention|passing|missing") String subject_prominence,
      List<String> topics) {}

  public record EditedSocialMentionDto(
      UUID id,
      String summary,
      String sentiment,
      String sentiment_rationale,
      String subject_prominence,
      List<String> topics,
      boolean is_user_edited,
      List<String> edited_fields) {

    static EditedSocialMentionDto from(SocialMention m) {
      return new EditedSocialMentionDto(
          m.id(),
          m.summary(),
          m.sentiment(),
          m.sentimentRationale(),
          m.subjectProminence(),
          m.topics(),
          m.isUserEdited(),
          m.editedFields());
    }
  }

  @PatchMapping("/v1/reports/{reportId}/social-mentions/{itemId}")
  public EditedSocialMentionDto edit(
      @PathVariable UUID reportId,
      @PathVariable UUID itemId,
      @Valid @RequestBody EditSocialMentionRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    if (!"draft".equals(report.status())) {
      throw AppException.badRequest(
          "/errors/report-not-draft", "Report not editable", "Only draft reports accept edits.");
    }
    SocialMention existing =
        mentions
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Social mention"));
    if (!existing.reportId().equals(report.id())) {
      throw AppException.notFound("Social mention");
    }
    SocialMention updated =
        mentions
            .userPatch(
                ctx.workspaceId(),
                existing.id(),
                body.summary(),
                body.sentiment(),
                body.sentiment_rationale(),
                body.subject_prominence(),
                body.topics())
            .orElseThrow(() -> AppException.notFound("Social mention"));
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.SOCIAL_MENTION_EDITED,
        "social_mention",
        existing.id(),
        Map.of("platform", existing.platform()));
    return EditedSocialMentionDto.from(updated);
  }

  @DeleteMapping("/v1/reports/{reportId}/social-mentions/{itemId}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    SocialMention existing =
        mentions
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Social mention"));
    if (!existing.reportId().equals(report.id())) {
      throw AppException.notFound("Social mention");
    }
    mentions.delete(ctx.workspaceId(), existing.id());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.SOCIAL_MENTION_DELETED,
        "social_mention",
        existing.id(),
        Map.of("platform", existing.platform()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/v1/reports/{reportId}/social-mentions/{itemId}/retry")
  public ResponseEntity<Void> retry(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    SocialMention existing =
        mentions
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Social mention"));
    if (!existing.reportId().equals(report.id())) {
      throw AppException.notFound("Social mention");
    }
    mentions.requeue(existing.id());
    // Idempotent enqueue — if a queued job already exists this is a no-op.
    jobs.enqueue(existing.id());
    // Re-extracting invalidates the rendered PDF (see CoverageController.retry rationale).
    // Reset report to 'draft' so the user can Generate again.
    if ("ready".equals(report.status()) || "failed".equals(report.status())) {
      reports.setStatus(report.id(), "draft");
    }
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.SOCIAL_MENTION_RETRIED,
        "social_mention",
        existing.id(),
        Map.of("platform", existing.platform()));
    return ResponseEntity.accepted().build();
  }

  /** Cancel an in-flight social extraction — see CoverageController.cancel for the rationale. */
  @PostMapping("/v1/reports/{reportId}/social-mentions/{itemId}/cancel")
  public ResponseEntity<Void> cancel(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    SocialMention existing =
        mentions
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Social mention"));
    if (!existing.reportId().equals(report.id())) {
      throw AppException.notFound("Social mention");
    }
    String s = existing.extractionStatus();
    if (!"queued".equals(s) && !"running".equals(s)) {
      throw AppException.badRequest(
          "/errors/not-cancellable",
          "Mention not in flight",
          "Only queued or running mentions can be cancelled; this one is " + s + ".");
    }
    mentions.markFailed(existing.id(), "cancelled by user");
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.SOCIAL_MENTION_CANCELLED,
        "social_mention",
        existing.id(),
        Map.of("platform", existing.platform(), "prior_status", s));
    return ResponseEntity.accepted().build();
  }
}
