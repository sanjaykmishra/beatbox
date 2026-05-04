package app.beat.report;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.auth.SessionTokens;
import app.beat.billing.PlanGuard;
import app.beat.client.ClientRepository;
import app.beat.coverage.CoverageItem;
import app.beat.coverage.CoverageItemRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.outlet.OutletRepository;
import app.beat.render.LocalPdfStore;
import app.beat.render.RenderClient;
import app.beat.render.RenderJobRepository;
import app.beat.render.RenderPayloadBuilder;
import app.beat.social.SocialAuthor;
import app.beat.social.SocialAuthorRepository;
import app.beat.social.SocialMention;
import app.beat.social.SocialMentionRepository;
import app.beat.workspace.WorkspaceMemberRepository;
import app.beat.workspace.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

  private static final UUID DEFAULT_TEMPLATE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final ReportRepository reports;
  private final ClientRepository clients;
  private final CoverageItemRepository coverage;
  private final OutletRepository outlets;
  private final SocialMentionRepository socialMentions;
  private final SocialAuthorRepository socialAuthors;
  private final ActivityRecorder activity;
  private final WorkspaceRepository workspaces;
  private final WorkspaceMemberRepository members;
  private final RenderJobRepository renderJobs;
  private final RenderClient renderClient;
  private final RenderPayloadBuilder renderPayloads;
  private final PlanGuard guard;
  private final LocalPdfStore localPdfs;
  private final String appBaseUrl;

  public ReportController(
      ReportRepository reports,
      ClientRepository clients,
      CoverageItemRepository coverage,
      OutletRepository outlets,
      SocialMentionRepository socialMentions,
      SocialAuthorRepository socialAuthors,
      ActivityRecorder activity,
      WorkspaceRepository workspaces,
      WorkspaceMemberRepository members,
      RenderJobRepository renderJobs,
      RenderClient renderClient,
      RenderPayloadBuilder renderPayloads,
      PlanGuard guard,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          LocalPdfStore localPdfs,
      @Value("${APP_BASE_URL:}") String appBaseUrl) {
    this.reports = reports;
    this.clients = clients;
    this.coverage = coverage;
    this.outlets = outlets;
    this.socialMentions = socialMentions;
    this.socialAuthors = socialAuthors;
    this.activity = activity;
    this.workspaces = workspaces;
    this.members = members;
    this.renderJobs = renderJobs;
    this.renderClient = renderClient;
    this.renderPayloads = renderPayloads;
    this.guard = guard;
    this.localPdfs = localPdfs;
    this.appBaseUrl = appBaseUrl;
  }

  public record CreateReportRequest(
      String title,
      @NotNull LocalDate period_start,
      @NotNull LocalDate period_end,
      UUID template_id) {}

  public record OutletDto(UUID id, String name, int tier) {}

  public record CoverageItemDto(
      UUID id,
      String source_url,
      String extraction_status,
      String extraction_error,
      OutletDto outlet,
      String headline,
      LocalDate publish_date,
      String lede,
      String sentiment,
      String subject_prominence,
      String screenshot_url,
      Integer tier_at_extraction,
      Long estimated_reach,
      boolean is_user_edited,
      List<String> edited_fields) {}

  /**
   * Summary the wireframe-31 header renders ("22 done · 3 extracting"). Counts unify across
   * articles and social mentions; clients filter client-side via the All / Articles / Social pills.
   */
  public record ReportStatusCountsDto(
      int total, int done, int extracting, int failed, int articles, int social) {}

  /**
   * Per-mention shape consumed by wireframe-31's social card. Author display fields are flattened
   * onto the row so the frontend doesn't need a second round-trip.
   */
  public record SocialMentionDto(
      UUID id,
      String source_url,
      String platform,
      String extraction_status,
      String extraction_error,
      String author_handle,
      String author_display_name,
      String author_avatar_url,
      String author_profile_url,
      Long author_follower_count,
      boolean author_verified,
      Instant posted_at,
      String content_text,
      String summary,
      String key_excerpt,
      String sentiment,
      String sentiment_rationale,
      String subject_prominence,
      List<String> topics,
      String media_summary,
      List<String> media_urls,
      Long likes_count,
      Long reposts_count,
      Long replies_count,
      Long views_count,
      Long estimated_reach,
      boolean is_user_edited,
      List<String> edited_fields) {}

  public record ReportDto(
      UUID id,
      UUID client_id,
      String client_name,
      UUID workspace_id,
      UUID template_id,
      String title,
      LocalDate period_start,
      LocalDate period_end,
      String status,
      String executive_summary,
      String pdf_url,
      String share_token,
      String failure_reason,
      Instant generated_at,
      Instant published_at,
      UUID published_by_user_id,
      UUID created_by_user_id,
      Instant created_at,
      List<CoverageItemDto> coverage_items,
      List<SocialMentionDto> social_mentions,
      ReportStatusCountsDto status_counts) {}

  // ---------- POST /v1/clients/:client_id/reports ----------

  @PostMapping("/v1/clients/{clientId}/reports")
  public ResponseEntity<ReportDto> create(
      @PathVariable UUID clientId,
      @Valid @RequestBody CreateReportRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    guard.requireReportSlot(ctx.workspaceId());
    var client =
        clients
            .findInWorkspace(ctx.workspaceId(), clientId)
            .orElseThrow(() -> AppException.notFound("Client"));
    if (body.period_end().isBefore(body.period_start())) {
      throw AppException.badRequest(
          "/errors/invalid-period",
          "Invalid period",
          "period_end must be on or after period_start.");
    }
    String title =
        (body.title() == null || body.title().isBlank()) ? defaultTitle(body) : body.title();
    UUID templateId = body.template_id() != null ? body.template_id() : DEFAULT_TEMPLATE_ID;
    Report r =
        reports.insert(
            client.id(),
            ctx.workspaceId(),
            templateId,
            title,
            body.period_start(),
            body.period_end(),
            ctx.userId());
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.REPORT_CREATED, "report", r.id(), Map.of());
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(r, List.of()));
  }

  // ---------- GET /v1/reports/:id ----------

  /** Compact summary for the client-dashboard "Past reports" list. */
  public record ReportSummaryDto(
      UUID id,
      String title,
      LocalDate period_start,
      LocalDate period_end,
      String status,
      Instant generated_at,
      Instant published_at,
      UUID created_by_user_id,
      Instant created_at) {}

  @GetMapping("/v1/clients/{clientId}/reports")
  public List<ReportSummaryDto> listForClient(@PathVariable UUID clientId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    clients
        .findInWorkspace(ctx.workspaceId(), clientId)
        .orElseThrow(() -> AppException.notFound("Client"));
    return reports.listForClient(ctx.workspaceId(), clientId).stream()
        .map(
            r ->
                new ReportSummaryDto(
                    r.id(),
                    r.title(),
                    r.periodStart(),
                    r.periodEnd(),
                    r.status(),
                    r.generatedAt(),
                    r.publishedAt(),
                    r.createdByUserId(),
                    r.createdAt()))
        .toList();
  }

  @GetMapping("/v1/reports/{id}")
  public ReportDto get(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    var coverageItems = coverage.listByReport(r.id());
    var coverageDtos = coverageItems.stream().map(this::toCoverageDto).toList();
    var socialItems = socialMentions.listByReport(ctx.workspaceId(), r.id());
    var socialDtos = socialItems.stream().map(this::toSocialDto).toList();
    var counts = computeStatusCounts(coverageItems, socialItems);
    return toDto(r, coverageDtos, socialDtos, counts);
  }

  private SocialMentionDto toSocialDto(SocialMention m) {
    SocialAuthor author =
        m.authorId() == null ? null : socialAuthors.findById(m.authorId()).orElse(null);
    return new SocialMentionDto(
        m.id(),
        m.sourceUrl(),
        m.platform(),
        m.extractionStatus(),
        m.extractionError(),
        author == null ? null : author.handle(),
        author == null ? null : author.displayName(),
        author == null ? null : author.avatarUrl(),
        author == null ? null : author.profileUrl(),
        m.followerCountAtPost() != null
            ? m.followerCountAtPost()
            : (author == null ? null : author.followerCount()),
        author != null && author.isVerified(),
        m.postedAt(),
        m.contentText(),
        m.summary(),
        /* key_excerpt is in raw_extracted but not on the row; surface via summary for now */ null,
        m.sentiment(),
        m.sentimentRationale(),
        m.subjectProminence(),
        m.topics(),
        m.mediaSummary(),
        m.mediaUrls(),
        m.likesCount(),
        m.repostsCount(),
        m.repliesCount(),
        m.viewsCount(),
        m.estimatedReach(),
        m.isUserEdited(),
        m.editedFields());
  }

  private static ReportStatusCountsDto computeStatusCounts(
      List<CoverageItem> articles, List<SocialMention> mentions) {
    int total = articles.size() + mentions.size();
    int extracting = 0;
    int done = 0;
    int failed = 0;
    for (CoverageItem c : articles) {
      switch (c.extractionStatus()) {
        case "queued", "running" -> extracting++;
        case "done" -> done++;
        case "failed" -> failed++;
        default -> {}
      }
    }
    for (SocialMention m : mentions) {
      switch (m.extractionStatus()) {
        case "queued", "running" -> extracting++;
        case "done" -> done++;
        case "failed" -> failed++;
        default -> {}
      }
    }
    return new ReportStatusCountsDto(
        total, done, extracting, failed, articles.size(), mentions.size());
  }

  private CoverageItemDto toCoverageDto(CoverageItem c) {
    OutletDto outletDto =
        Optional.ofNullable(c.outletId())
            .flatMap(id -> Optional.ofNullable(outletsLookup(id)))
            .orElse(null);
    return new CoverageItemDto(
        c.id(),
        c.sourceUrl(),
        c.extractionStatus(),
        c.extractionError(),
        outletDto,
        c.headline(),
        c.publishDate(),
        c.lede(),
        c.sentiment(),
        c.subjectProminence(),
        c.screenshotUrl(),
        c.tierAtExtraction(),
        c.estimatedReach(),
        c.isUserEdited(),
        c.editedFields());
  }

  private OutletDto outletsLookup(UUID id) {
    // simple read-through; could be cached, but tier-1 reports are bounded in size
    return outlets.findById(id).map(o -> new OutletDto(o.id(), o.name(), o.tier())).orElse(null);
  }

  private ReportDto toDto(Report r, List<CoverageItemDto> items) {
    return toDto(
        r, items, List.of(), new ReportStatusCountsDto(items.size(), 0, 0, 0, items.size(), 0));
  }

  private ReportDto toDto(
      Report r,
      List<CoverageItemDto> items,
      List<SocialMentionDto> socialMentions,
      ReportStatusCountsDto counts) {
    // Pull the client name so the SPA can render it in breadcrumbs and headings without a second
    // round-trip. Workspace-scoped lookup keeps us inside tenant boundaries (docs/14).
    String clientName =
        clients.findInWorkspace(r.workspaceId(), r.clientId()).map(c -> c.name()).orElse(null);
    return new ReportDto(
        r.id(),
        r.clientId(),
        clientName,
        r.workspaceId(),
        r.templateId(),
        r.title(),
        r.periodStart(),
        r.periodEnd(),
        r.status(),
        r.executiveSummary(),
        r.pdfUrl(),
        r.shareToken(),
        r.failureReason(),
        r.generatedAt(),
        r.publishedAt(),
        r.publishedByUserId(),
        r.createdByUserId(),
        r.createdAt(),
        items,
        socialMentions,
        counts);
  }

  private static String defaultTitle(CreateReportRequest body) {
    return body.period_end()
            .getMonth()
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ROOT)
        + " "
        + body.period_end().getYear();
  }

  // ---------- POST /v1/reports/:id/generate ----------

  public record GenerateResponse(UUID id, String status) {}

  @PostMapping("/v1/reports/{id}/generate")
  public ResponseEntity<GenerateResponse> generate(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    // Generate is allowed from every non-terminal status:
    //   draft     — first run.
    //   ready     — re-run after edits (replaces the previous PDF).
    //   failed    — retry after a render-pipeline failure.
    //   processing — explicit recovery from a stuck render. The render-jobs row is upserted
    //                back to 'queued' so a fresh worker pick-up replaces whatever (if anything)
    //                is still half-running. Last-write-wins on markReady; acceptable because
    //                the alternative is leaving the user with no escape.
    // Rejects 'published' (immutable — duplicate the report instead).
    if ("published".equals(r.status())) {
      throw AppException.conflict(
          "/errors/report-published",
          "Report is published",
          "Published reports are locked. Duplicate the report to start a new one.");
    }
    if (!"draft".equals(r.status())
        && !"ready".equals(r.status())
        && !"failed".equals(r.status())
        && !"processing".equals(r.status())) {
      throw AppException.badRequest(
          "/errors/report-not-generatable",
          "Report can't be generated",
          "Status " + r.status() + " is not generatable.");
    }
    // Per CLAUDE.md guardrail #8 ("social mentions are first-class"), the generate gate considers
    // both articles and social mentions when deciding whether anything is in-flight or has been
    // successfully extracted. A report with one Bluesky post and zero articles is still
    // generatable.
    var items = coverage.listByReport(r.id());
    var mentions = socialMentions.listByReport(ctx.workspaceId(), r.id());
    boolean anyInflight =
        items.stream()
                .anyMatch(
                    i ->
                        "queued".equals(i.extractionStatus())
                            || "running".equals(i.extractionStatus()))
            || mentions.stream()
                .anyMatch(
                    m ->
                        "queued".equals(m.extractionStatus())
                            || "running".equals(m.extractionStatus()));
    long doneCount =
        items.stream().filter(i -> "done".equals(i.extractionStatus())).count()
            + mentions.stream().filter(m -> "done".equals(m.extractionStatus())).count();
    if (anyInflight) {
      throw AppException.badRequest(
          "/errors/extraction-pending",
          "Extraction still running",
          "Wait for all extractions to complete.");
    }
    if (doneCount == 0) {
      throw AppException.badRequest(
          "/errors/no-done-items",
          "No done items",
          "At least one coverage item or social mention must be successfully extracted.");
    }
    reports.setStatus(r.id(), "processing");
    renderJobs.enqueue(r.id());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new GenerateResponse(r.id(), "processing"));
  }

  // ---------- GET /v1/reports/:id/pdf ----------

  @GetMapping("/v1/reports/{id}/pdf")
  public ResponseEntity<?> downloadPdf(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    if (r.pdfUrl() == null) throw AppException.notFound("PDF not yet ready");
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_PDF_DOWNLOADED,
        "report",
        r.id(),
        Map.of());
    // Local-disk fallback (R2 not configured): stream the PDF directly. The 302-redirect path used
    // for R2 public URLs would lose the auth header, so we read here instead.
    if (localPdfs != null && r.pdfUrl().startsWith(LocalPdfStore.URL_PREFIX)) {
      byte[] bytes =
          localPdfs.read(r.pdfUrl()).orElseThrow(() -> AppException.notFound("PDF file missing"));
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header(
              org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
              "inline; filename=\"report-" + r.id() + ".pdf\"")
          .body(bytes);
    }
    return ResponseEntity.status(HttpStatus.FOUND).header("Location", r.pdfUrl()).build();
  }

  // ---------- GET /v1/reports/:id/preview ----------

  @GetMapping(value = "/v1/reports/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> preview(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    var ws =
        workspaces.findById(r.workspaceId()).orElseThrow(() -> AppException.notFound("Workspace"));
    var client =
        clients
            .findInWorkspace(r.workspaceId(), r.clientId())
            .orElseThrow(() -> AppException.notFound("Client"));
    var items = coverage.listByReport(r.id());
    var mentions = socialMentions.listByReport(r.workspaceId(), r.id());
    var payload = renderPayloads.build(ws, client, r, items, mentions);
    String html = renderClient.renderHtml(payload);
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
  }

  // ---------- POST + DELETE /v1/reports/:id/share ----------

  public record ShareRequest(@Min(1) Integer expires_in_days) {}

  public record ShareResponse(String share_url, Instant expires_at) {}

  @PostMapping("/v1/reports/{id}/share")
  public ShareResponse share(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ShareRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    // Sharing is gated to 'published' — the whole point of the publish step is "this is the
    // client-ready, locked-in version." Sharing a draft or post-edit-but-pre-republish report
    // is exactly the failure mode publish exists to prevent.
    if (!"published".equals(r.status())) {
      throw AppException.badRequest(
          "/errors/report-not-published",
          "Report not published",
          "Publish the report before sharing it.");
    }
    int days = (body == null || body.expires_in_days() == null) ? 30 : body.expires_in_days();
    Instant expiresAt = Instant.now().plus(java.time.Duration.ofDays(days));
    String token = SessionTokens.generate();
    String tokenHash = SessionTokens.hash(token);
    reports.setShareToken(r.id(), tokenHash, expiresAt);
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_SHARED,
        "report",
        r.id(),
        Map.of("expires_in_days", days));
    String base = appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl;
    return new ShareResponse(base + "/r/" + token, expiresAt);
  }

  // ---------- PATCH /v1/reports/:id/summary ----------

  public record EditSummaryRequest(@NotBlank @Size(max = 8000) String summary) {}

  public record SummaryDto(UUID id, String executive_summary, boolean executive_summary_edited) {}

  @PatchMapping("/v1/reports/{id}/summary")
  public SummaryDto editSummary(
      @PathVariable UUID id, @Valid @RequestBody EditSummaryRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report current =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    ReportMutationGuard.assertEditable(current, "edit the executive summary");
    Report r =
        reports
            .setEditedSummary(ctx.workspaceId(), id, body.summary())
            .orElseThrow(() -> AppException.notFound("Report"));
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_SUMMARY_EDITED,
        "report",
        r.id(),
        Map.of());
    return new SummaryDto(r.id(), r.executiveSummary(), r.executiveSummaryEdited());
  }

  // ---------- POST /v1/reports/:id/publish ----------

  public record PublishResponse(UUID id, String status, Instant published_at) {}

  /**
   * Transition a {@code ready} report to {@code published}. Approval gate: in a multi-person
   * workspace the report's creator cannot self-publish — only another team member can. In a
   * single-person workspace the creator can self-publish (no other choice).
   */
  @PostMapping("/v1/reports/{id}/publish")
  public ResponseEntity<PublishResponse> publish(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    if (!"ready".equals(r.status())) {
      throw AppException.badRequest(
          "/errors/report-not-ready-to-publish",
          "Report not ready",
          "Generate the report before publishing it. Current status: " + r.status() + ".");
    }
    int memberCount = members.countActiveMembers(ctx.workspaceId());
    boolean isCreator = r.createdByUserId() != null && r.createdByUserId().equals(ctx.userId());
    if (memberCount > 1 && isCreator) {
      // Record the rejection for visibility — useful when a team is misusing the feature, and
      // small enough volume that the noise is acceptable.
      activity.recordUser(
          ctx.workspaceId(),
          ctx.userId(),
          EventKinds.REPORT_PUBLISH_REJECTED_SELF,
          "report",
          r.id(),
          Map.of());
      throw AppException.forbidden(
          "Only another team member can publish your report. Ask a teammate to review and"
              + " publish it.");
    }
    boolean published = reports.publish(r.id(), ctx.userId());
    if (!published) {
      // Race: someone else flipped status between our findInWorkspace and the UPDATE.
      throw AppException.conflict(
          "/errors/publish-race",
          "Couldn't publish",
          "The report is no longer in 'ready' state — refresh and try again.");
    }
    Report after = reports.findInWorkspace(ctx.workspaceId(), r.id()).orElse(r);
    long readyToPublishedSeconds =
        r.generatedAt() == null || after.publishedAt() == null
            ? 0
            : java.time.Duration.between(r.generatedAt(), after.publishedAt()).getSeconds();
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_PUBLISHED,
        "report",
        r.id(),
        Map.of(
            "published_by_user_id",
            ctx.userId().toString(),
            "ready_to_published_seconds",
            readyToPublishedSeconds,
            "creator_user_id",
            r.createdByUserId() == null ? "" : r.createdByUserId().toString(),
            "is_self_publish",
            isCreator));
    return ResponseEntity.ok(new PublishResponse(after.id(), after.status(), after.publishedAt()));
  }

  // ---------- DELETE /v1/reports/:id ----------

  /**
   * Soft-delete a report. Allowed on {@code ready}, {@code failed}, and {@code processing} — the
   * last is the escape hatch for the case where a render worker dies mid-job and leaves the report
   * stuck. Drafts can't be deleted (they're pre-generation work-in-progress that should be finished
   * or abandoned in place); published reports are immutable.
   */
  @DeleteMapping("/v1/reports/{id}")
  public ResponseEntity<Void> deleteReport(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    if (!"ready".equals(r.status())
        && !"failed".equals(r.status())
        && !"processing".equals(r.status())) {
      throw AppException.conflict(
          "/errors/report-not-deletable",
          "Report can't be deleted",
          "Only ready, failed, or processing reports can be deleted; this one is "
              + r.status()
              + ".");
    }
    boolean deleted = reports.softDeleteIfDeletable(r.id());
    if (!deleted) {
      throw AppException.conflict(
          "/errors/delete-race",
          "Couldn't delete",
          "The report's state changed underneath us — refresh and try again.");
    }
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_DELETED,
        "report",
        r.id(),
        Map.of("prior_status", r.status()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/v1/reports/{id}/share")
  public ResponseEntity<Void> revokeShare(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    reports.setShareToken(r.id(), null, null);
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.REPORT_SHARE_REVOKED,
        "report",
        r.id(),
        Map.of());
    return ResponseEntity.noContent().build();
  }
}
