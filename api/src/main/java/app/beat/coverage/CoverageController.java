package app.beat.coverage;

import app.beat.extraction.ExtractionJobRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.outlet.Domains;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CoverageController {

  private final ReportRepository reports;
  private final CoverageItemRepository coverage;
  private final ExtractionJobRepository jobs;

  public CoverageController(
      ReportRepository reports, CoverageItemRepository coverage, ExtractionJobRepository jobs) {
    this.reports = reports;
    this.coverage = coverage;
    this.jobs = jobs;
  }

  public record AddCoverageRequest(@NotEmpty List<String> urls) {}

  public record QueuedItemDto(UUID id, String source_url, String extraction_status) {}

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
    if (!"draft".equals(report.status())) {
      throw AppException.badRequest(
          "/errors/report-not-draft",
          "Report not editable",
          "Only draft reports accept new coverage items.");
    }
    List<String> normalized = normalize(body.urls());
    if (normalized.isEmpty()) {
      throw AppException.badRequest(
          "/errors/no-urls", "No URLs provided", "Provide at least one valid http(s) URL.");
    }
    List<QueuedItemDto> created = new ArrayList<>();
    int sortOrder = 0;
    for (String url : normalized) {
      var inserted = coverage.insertQueued(report.id(), url, sortOrder++);
      if (inserted.isPresent()) {
        jobs.enqueue(inserted.get().id());
        created.add(new QueuedItemDto(inserted.get().id(), url, inserted.get().extractionStatus()));
      }
    }
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
    coverage.delete(item.id());
    return ResponseEntity.noContent().build();
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
    if (!"failed".equals(item.extractionStatus())) {
      throw AppException.badRequest(
          "/errors/not-failed",
          "Item not in failed state",
          "Only failed coverage items can be retried.");
    }
    coverage.resetForRetry(item.id());
    jobs.enqueue(item.id());
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
