package app.beat.report;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.coverage.CoverageItem;
import app.beat.coverage.CoverageItemRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.outlet.OutletRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final ActivityRecorder activity;

  public ReportController(
      ReportRepository reports,
      ClientRepository clients,
      CoverageItemRepository coverage,
      OutletRepository outlets,
      ActivityRecorder activity) {
    this.reports = reports;
    this.clients = clients;
    this.coverage = coverage;
    this.outlets = outlets;
    this.activity = activity;
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
      String screenshot_url,
      Integer tier_at_extraction,
      Long estimated_reach,
      boolean is_user_edited,
      List<String> edited_fields) {}

  public record ReportDto(
      UUID id,
      UUID client_id,
      UUID workspace_id,
      UUID template_id,
      String title,
      LocalDate period_start,
      LocalDate period_end,
      String status,
      String executive_summary,
      String pdf_url,
      String share_token,
      Instant generated_at,
      Instant created_at,
      List<CoverageItemDto> coverage_items) {}

  // ---------- POST /v1/clients/:client_id/reports ----------

  @PostMapping("/v1/clients/{clientId}/reports")
  public ResponseEntity<ReportDto> create(
      @PathVariable UUID clientId,
      @Valid @RequestBody CreateReportRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
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

  @GetMapping("/v1/reports/{id}")
  public ReportDto get(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Report r =
        reports
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Report"));
    var items = coverage.listByReport(r.id()).stream().map(this::toCoverageDto).toList();
    return toDto(r, items);
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
    return new ReportDto(
        r.id(),
        r.clientId(),
        r.workspaceId(),
        r.templateId(),
        r.title(),
        r.periodStart(),
        r.periodEnd(),
        r.status(),
        r.executiveSummary(),
        r.pdfUrl(),
        r.shareToken(),
        r.generatedAt(),
        r.createdAt(),
        items);
  }

  private static String defaultTitle(CreateReportRequest body) {
    return body.period_end()
            .getMonth()
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ROOT)
        + " "
        + body.period_end().getYear();
  }
}
