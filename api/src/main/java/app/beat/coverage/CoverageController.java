package app.beat.coverage;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.extraction.ExtractionJobRepository;
import app.beat.extraction.UrlPrefilter;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.outlet.Domains;
import app.beat.report.Report;
import app.beat.report.ReportMutationGuard;
import app.beat.report.ReportRepository;
import app.beat.social.SocialExtractionJobRepository;
import app.beat.social.SocialMentionRepository;
import app.beat.social.UrlClassifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CoverageController {

  private final ReportRepository reports;
  private final CoverageItemRepository coverage;
  private final ExtractionJobRepository jobs;
  private final SocialMentionRepository socialMentions;
  private final SocialExtractionJobRepository socialJobs;
  private final UrlPrefilter urlPrefilter;
  private final ActivityRecorder activity;

  public CoverageController(
      ReportRepository reports,
      CoverageItemRepository coverage,
      ExtractionJobRepository jobs,
      SocialMentionRepository socialMentions,
      SocialExtractionJobRepository socialJobs,
      UrlPrefilter urlPrefilter,
      ActivityRecorder activity) {
    this.reports = reports;
    this.coverage = coverage;
    this.jobs = jobs;
    this.socialMentions = socialMentions;
    this.socialJobs = socialJobs;
    this.urlPrefilter = urlPrefilter;
    this.activity = activity;
  }

  public record AddCoverageRequest(@NotEmpty List<String> urls) {}

  /**
   * Per-URL outcome the dispatcher returns. {@code kind} is "article" or "social"; for social we
   * also surface the {@code platform} so the frontend can render the right card immediately.
   */
  public record QueuedItemDto(
      UUID id, String source_url, String extraction_status, String kind, String platform) {

    static QueuedItemDto article(UUID id, String url, String status) {
      return new QueuedItemDto(id, url, status, "article", null);
    }

    static QueuedItemDto social(UUID id, String url, String status, String platform) {
      return new QueuedItemDto(id, url, status, "social", platform);
    }
  }

  public record AddCoverageResponse(List<QueuedItemDto> items) {}

  @PostMapping("/v1/reports/{reportId}/coverage")
  public ResponseEntity<AddCoverageResponse> add(
      @PathVariable UUID reportId,
      @Valid @RequestBody AddCoverageRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    ReportMutationGuard.assertEditable(report, "add more URLs");
    // Status is intentionally NOT flipped on add. Per the lifecycle spec a 'ready' or 'failed'
    // report stays in that status while the user iterates; the existing rendered PDF stays
    // valid until the user re-Generates. Re-running Generate is what re-renders.
    List<String> normalized = normalize(body.urls());
    if (normalized.isEmpty()) {
      throw AppException.badRequest(
          "/errors/no-urls", "No URLs provided", "Provide at least one valid http(s) URL.");
    }
    List<QueuedItemDto> created = new ArrayList<>();
    int sortOrder = 0;
    int socialCount = 0;
    for (String url : normalized) {
      // Per docs/17 §17.1: dispatch by URL classifier so a single paste field handles both
      // articles and social posts. Social URLs route to social_mentions + social_extraction_jobs;
      // anything else falls through to the existing article pipeline.
      String platform = UrlClassifier.platformOf(url).orElse(null);
      if (platform != null) {
        var inserted =
            socialMentions.insertQueued(
                ctx.workspaceId(), report.id(), report.clientId(), platform, url, sortOrder++);
        if (inserted.isPresent()) {
          socialJobs.enqueue(inserted.get().id());
          created.add(
              QueuedItemDto.social(
                  inserted.get().id(), url, inserted.get().extractionStatus(), platform));
          socialCount++;
          activity.recordUser(
              ctx.workspaceId(),
              ctx.userId(),
              EventKinds.SOCIAL_MENTION_ADDED,
              "social_mention",
              inserted.get().id(),
              Map.of("platform", platform));
        }
      } else {
        var inserted = coverage.insertQueued(report.id(), url, sortOrder++);
        if (inserted.isPresent()) {
          // Pre-filter obvious non-article URLs (Reddit listings, live-update tickers,
          // homepages, tag/category indexes, …). The article fetcher would either return
          // empty or feed the LLM noise; failing fast with a useful message is the right
          // user experience.
          var rejected = urlPrefilter.reject(url);
          if (rejected.isPresent()) {
            coverage.markFailed(inserted.get().id(), "Not a single article: " + rejected.get());
            created.add(QueuedItemDto.article(inserted.get().id(), url, "failed"));
          } else {
            jobs.enqueue(inserted.get().id());
            created.add(
                QueuedItemDto.article(inserted.get().id(), url, inserted.get().extractionStatus()));
          }
        }
      }
    }
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_URLS_ADDED,
        "report",
        report.id(),
        Map.of("count", created.size(), "social_count", socialCount));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AddCoverageResponse(created));
  }

  @DeleteMapping("/v1/reports/{reportId}/coverage/{itemId}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    var item =
        coverage
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Coverage item"));
    if (!item.reportId().equals(report.id())) {
      throw AppException.notFound("Coverage item");
    }
    ReportMutationGuard.assertEditable(report, "remove this item");
    coverage.delete(item.id());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_COVERAGE_DISMISSED,
        "coverage_item",
        item.id(),
        Map.of());
    return ResponseEntity.noContent().build();
  }

  public record EditCoverageRequest(
      String headline,
      String subheadline,
      String publish_date,
      String lede,
      String summary,
      String key_quote,
      @Pattern(regexp = "positive|neutral|negative|mixed") String sentiment,
      String sentiment_rationale,
      @Pattern(regexp = "feature|mention|passing|missing") String subject_prominence,
      List<String> topics) {}

  public record EditedCoverageDto(
      UUID id,
      String headline,
      String subheadline,
      String publish_date,
      String lede,
      String summary,
      String key_quote,
      String sentiment,
      String sentiment_rationale,
      String subject_prominence,
      List<String> topics,
      boolean is_user_edited,
      List<String> edited_fields) {
    static EditedCoverageDto from(CoverageItem c) {
      return new EditedCoverageDto(
          c.id(),
          c.headline(),
          c.subheadline(),
          c.publishDate() == null ? null : c.publishDate().toString(),
          c.lede(),
          c.summary(),
          c.keyQuote(),
          c.sentiment(),
          c.sentimentRationale(),
          c.subjectProminence(),
          c.topics(),
          c.isUserEdited(),
          c.editedFields());
    }
  }

  private static final Set<String> EDITABLE_FIELDS =
      Set.of(
          "headline",
          "subheadline",
          "publish_date",
          "lede",
          "summary",
          "key_quote",
          "sentiment",
          "sentiment_rationale",
          "subject_prominence",
          "topics");

  @PatchMapping("/v1/reports/{reportId}/coverage/{itemId}")
  public EditedCoverageDto edit(
      @PathVariable UUID reportId,
      @PathVariable UUID itemId,
      @Valid @RequestBody EditCoverageRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    var item =
        coverage
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Coverage item"));
    if (!item.reportId().equals(report.id())) {
      throw AppException.notFound("Coverage item");
    }
    ReportMutationGuard.assertEditable(report, "edit this item");
    Map<String, Object> edits = new LinkedHashMap<>();
    putIfPresent(edits, "headline", body.headline());
    putIfPresent(edits, "subheadline", body.subheadline());
    if (body.publish_date() != null) {
      try {
        edits.put("publish_date", LocalDate.parse(body.publish_date()));
      } catch (DateTimeParseException e) {
        throw AppException.badRequest(
            "/errors/invalid-date", "Invalid date", "publish_date must be YYYY-MM-DD.");
      }
    }
    putIfPresent(edits, "lede", body.lede());
    putIfPresent(edits, "summary", body.summary());
    putIfPresent(edits, "key_quote", body.key_quote());
    putIfPresent(edits, "sentiment", body.sentiment());
    putIfPresent(edits, "sentiment_rationale", body.sentiment_rationale());
    putIfPresent(edits, "subject_prominence", body.subject_prominence());
    if (body.topics() != null) {
      edits.put("topics", body.topics().toArray(new String[0]));
    }
    if (edits.isEmpty()) {
      throw AppException.badRequest(
          "/errors/no-edits", "No edits provided", "Provide at least one editable field.");
    }
    for (String k : edits.keySet()) {
      if (!EDITABLE_FIELDS.contains(k)) {
        throw AppException.badRequest(
            "/errors/not-editable", "Field not editable", k + " is not user-editable.");
      }
    }
    var updated = coverage.applyUserEdit(item.id(), edits);
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_COVERAGE_EDITED,
        "coverage_item",
        item.id(),
        Map.of("fields_edited", edits.keySet()));
    return EditedCoverageDto.from(updated);
  }

  private static void putIfPresent(Map<String, Object> m, String k, String v) {
    if (v != null) m.put(k, v);
  }

  @PostMapping("/v1/reports/{reportId}/coverage/{itemId}/retry")
  public ResponseEntity<Void> retry(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    var item =
        coverage
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Coverage item"));
    if (!item.reportId().equals(report.id())) {
      throw AppException.notFound("Coverage item");
    }
    ReportMutationGuard.assertEditable(report, "retry this item");
    // Cell-level edits are preserved via edited_fields (CLAUDE.md guardrail #4), so re-extracting
    // a 'done' item is safe. 'queued' / 'running' are no-ops via the idempotent enqueue below.
    coverage.resetForRetry(item.id());
    jobs.enqueue(item.id());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_COVERAGE_RETRIED,
        "coverage_item",
        item.id(),
        Map.of());
    return ResponseEntity.accepted().build();
  }

  /**
   * Cancel an in-flight extraction. Marks the coverage item as failed with reason 'cancelled by
   * user' so the user-facing UI can surface Retry/Edit/Remove. Only meaningful for queued/running
   * items; cancelling a 'done' or already-'failed' row is rejected as a no-op.
   *
   * <p>Race note: a worker that's already mid-fetch may finish and write back 'done'/'failed' after
   * this point. Last-write-wins is acceptable for now — the user can hit Cancel again or Retry. A
   * stricter solution (cancelled_at timestamp checked by the worker before writeback) is deferred
   * until we see real misuse.
   */
  @PostMapping("/v1/reports/{reportId}/coverage/{itemId}/cancel")
  public ResponseEntity<Void> cancel(
      @PathVariable UUID reportId, @PathVariable UUID itemId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report report =
        reports
            .findInWorkspace(ctx.workspaceId(), reportId)
            .orElseThrow(() -> AppException.notFound("Report"));
    var item =
        coverage
            .findInWorkspace(ctx.workspaceId(), itemId)
            .orElseThrow(() -> AppException.notFound("Coverage item"));
    if (!item.reportId().equals(report.id())) {
      throw AppException.notFound("Coverage item");
    }
    ReportMutationGuard.assertEditable(report, "cancel this extraction");
    String s = item.extractionStatus();
    if (!"queued".equals(s) && !"running".equals(s)) {
      throw AppException.badRequest(
          "/errors/not-cancellable",
          "Item not in flight",
          "Only queued or running items can be cancelled; this one is " + s + ".");
    }
    coverage.markFailed(item.id(), "cancelled by user");
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_COVERAGE_CANCELLED,
        "coverage_item",
        item.id(),
        Map.of("prior_status", s));
    return ResponseEntity.accepted().build();
  }

  static List<String> normalize(List<String> raw) {
    List<String> out = new ArrayList<>();
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (String s : raw) {
      if (s == null) continue;
      // Allow newline/comma/space separators within a single string.
      for (String piece : s.split("[\\s,]+")) {
        String url = piece.trim();
        if (url.isEmpty()) continue;
        if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
        if (Domains.apexFromUrl(url).isEmpty()) continue;
        if (seen.add(url)) out.add(url);
      }
    }
    return out;
  }
}
