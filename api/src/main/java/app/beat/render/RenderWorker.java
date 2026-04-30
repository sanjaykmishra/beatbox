package app.beat.render;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.coverage.CoverageItem;
import app.beat.coverage.CoverageItemRepository;
import app.beat.llm.SummaryService;
import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import app.beat.workspace.Workspace;
import app.beat.workspace.WorkspaceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains render_jobs. For each: load report + items → build payload → call render → upload PDF to
 * R2 → mark report ready. Falls back to "failed" with a recorded reason on any unrecoverable error.
 * Per docs/02 §Render worker.
 */
@Component
public class RenderWorker {

  private static final Logger log = LoggerFactory.getLogger(RenderWorker.class);
  private static final int MAX_ATTEMPTS = 2;
  private static final int BATCH_SIZE = 2;

  private final RenderJobRepository jobs;
  private final ReportRepository reports;
  private final ClientRepository clients;
  private final ClientContextRepository clientContexts;
  private final WorkspaceRepository workspaces;
  private final CoverageItemRepository coverage;
  private final OutletRepository outlets;
  private final RenderPayloadBuilder payloads;
  private final RenderClient render;
  private final PdfStorage pdfs;
  private final SummaryService summary;
  private final ActivityRecorder activity;
  private final boolean enabled;

  private final ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);

  public RenderWorker(
      RenderJobRepository jobs,
      ReportRepository reports,
      ClientRepository clients,
      ClientContextRepository clientContexts,
      WorkspaceRepository workspaces,
      CoverageItemRepository coverage,
      OutletRepository outlets,
      RenderPayloadBuilder payloads,
      RenderClient render,
      PdfStorage pdfs,
      SummaryService summary,
      ActivityRecorder activity,
      @Value("${beat.render.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.reports = reports;
    this.clients = clients;
    this.clientContexts = clientContexts;
    this.workspaces = workspaces;
    this.coverage = coverage;
    this.outlets = outlets;
    this.payloads = payloads;
    this.render = render;
    this.pdfs = pdfs;
    this.summary = summary;
    this.activity = activity;
    this.enabled = enabled;
  }

  @PostConstruct
  void start() {
    log.info(
        "render worker enabled={} render_url_set={} pdf_storage_set={}",
        enabled,
        render.isConfigured(),
        pdfs.isConfigured());
  }

  @PreDestroy
  void stop() {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }

  @Scheduled(fixedDelayString = "${beat.render.poll-ms:1000}")
  public void drain() {
    if (!enabled) return;
    List<RenderJobRepository.QueuedJob> claimed = jobs.claimBatch(BATCH_SIZE);
    if (claimed.isEmpty()) return;
    for (var job : claimed) {
      pool.submit(() -> process(job));
    }
  }

  void process(RenderJobRepository.QueuedJob job) {
    long startedAt = System.currentTimeMillis();
    Report report = null;
    try {
      report =
          reports
              .findById(job.reportId())
              .orElseThrow(() -> new IllegalStateException("report missing"));
      Workspace ws =
          workspaces
              .findById(report.workspaceId())
              .orElseThrow(() -> new IllegalStateException("workspace missing"));
      var client =
          clients
              .findInWorkspace(report.workspaceId(), report.clientId())
              .orElseThrow(() -> new IllegalStateException("client missing"));
      var items = coverage.listByReport(report.id());

      // Generate executive summary first (so it lands in the PDF and the preview HTML).
      // Skip if the user has pinned an edit, or if Anthropic isn't configured.
      if (summary.isEnabled()
          && !report.executiveSummaryEdited()
          && (report.executiveSummary() == null || report.executiveSummary().isBlank())) {
        try {
          var styleNotes =
              clientContexts.findByClient(client.id()).map(c -> c.styleNotes()).orElse(null);
          java.util.Map<java.util.UUID, Outlet> outletCache = new java.util.HashMap<>();
          for (CoverageItem c : items) {
            if (c.outletId() != null && !outletCache.containsKey(c.outletId())) {
              outlets.findById(c.outletId()).ifPresent(o -> outletCache.put(o.id(), o));
            }
          }
          var outcome = summary.generate(report, client.name(), items, outletCache, styleNotes);
          reports.setGeneratedSummary(report.id(), outcome.text());
          activity.recordWorker(
              ws.id(),
              EventKinds.REPORT_SUMMARY_GENERATED,
              "report",
              report.id(),
              null,
              Map.of(
                  "prompt_version",
                  outcome.promptVersion(),
                  "hyperbole_hits",
                  outcome.hyperboleHits().size()));
          report =
              reports
                  .findById(report.id())
                  .orElseThrow(() -> new IllegalStateException("report missing after summary"));
        } catch (Exception se) {
          log.warn(
              "render: summary generation failed for report {}; continuing with no summary: {}",
              report.id(),
              se.toString());
        }
      }

      var payload = payloads.build(ws, client, report, items);
      byte[] pdf = render.renderPdf(payload);
      var pdfUrl = pdfs.upload(ws.id(), report.id(), pdf).orElse(null);

      if (pdfUrl == null) {
        log.warn("render: PDF generated but no R2 configured; marking report failed");
        reports.markFailed(report.id(), "object_storage_not_configured");
        jobs.markFailed(job.id(), "object_storage_not_configured");
        return;
      }
      reports.markReady(report.id(), pdfUrl);
      jobs.markDone(job.id());
      activity.recordWorker(
          ws.id(),
          EventKinds.REPORT_GENERATED,
          "report",
          report.id(),
          Duration.ofMillis(System.currentTimeMillis() - startedAt),
          Map.of("total_items", items.size()));
      log.info("render: done report={} bytes={}", report.id(), pdf.length);
    } catch (Exception e) {
      log.warn("render: job {} failed: {}", job.id(), e.toString());
      activity.recordSystem(
          EventKinds.RENDER_FAILED,
          "render_job",
          job.id(),
          Map.of("error_class", e.getClass().getSimpleName()));
      retryOrFail(job, report, e);
    }
  }

  private void retryOrFail(RenderJobRepository.QueuedJob job, Report report, Exception cause) {
    String reason =
        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    if (job.attemptCount() < MAX_ATTEMPTS) {
      jobs.requeue(job.id());
    } else {
      jobs.markFailed(job.id(), reason);
      if (report != null) reports.markFailed(report.id(), reason);
    }
  }
}
