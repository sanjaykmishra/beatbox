package app.beat.extraction;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.alerts.AlertService;
import app.beat.author.Author;
import app.beat.author.AuthorRepository;
import app.beat.client.ClientRepository;
import app.beat.clientcontext.ClientContext;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.coverage.CoverageItem;
import app.beat.coverage.CoverageItemRepository;
import app.beat.llm.ExtractionService;
import app.beat.llm.OutletTierClassifier;
import app.beat.outlet.Domains;
import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Drains extraction_jobs end-to-end: claim → fetch HTML → outlet upsert + tier classify → Anthropic
 * Sonnet for sentiment/summary/quote/topics → author upsert → screenshot → write coverage_item →
 * mark done. Retries on failure (3 attempts, exponential at the API client).
 */
@Component
public class ExtractionWorker {

  private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final int BATCH_SIZE = 4;

  private final ExtractionJobRepository jobs;
  private final CoverageItemRepository coverage;
  private final ReportRepository reports;
  private final ClientRepository clients;
  private final ClientContextRepository clientContexts;
  private final OutletRepository outlets;
  private final AuthorRepository authors;
  private final LayeredArticleFetcher fetcher;
  private final ScreenshotClient screenshots;
  private final ExtractionService extraction;
  private final OutletTierClassifier tierClassifier;
  private final ActivityRecorder activity;
  private final AlertService alertService;
  private final ObjectMapper json = new ObjectMapper();
  private final boolean enabled;

  private final ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);

  public ExtractionWorker(
      ExtractionJobRepository jobs,
      CoverageItemRepository coverage,
      ReportRepository reports,
      ClientRepository clients,
      ClientContextRepository clientContexts,
      OutletRepository outlets,
      AuthorRepository authors,
      LayeredArticleFetcher fetcher,
      ScreenshotClient screenshots,
      ExtractionService extraction,
      OutletTierClassifier tierClassifier,
      ActivityRecorder activity,
      AlertService alertService,
      @Value("${beat.extraction.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.coverage = coverage;
    this.reports = reports;
    this.clients = clients;
    this.clientContexts = clientContexts;
    this.outlets = outlets;
    this.authors = authors;
    this.fetcher = fetcher;
    this.screenshots = screenshots;
    this.extraction = extraction;
    this.tierClassifier = tierClassifier;
    this.activity = activity;
    this.alertService = alertService;
    this.enabled = enabled;
  }

  @PostConstruct
  void start() {
    log.info(
        "extraction worker enabled={} llm={} batch_size={}",
        enabled,
        extraction.isEnabled() ? "anthropic" : "disabled",
        BATCH_SIZE);
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

  @Scheduled(fixedDelayString = "${beat.extraction.poll-ms:1000}")
  public void drain() {
    if (!enabled) return;
    List<ExtractionJobRepository.QueuedJob> claimed = jobs.claimBatch(BATCH_SIZE);
    if (claimed.isEmpty()) return;
    log.debug("extraction: claimed {} job(s)", claimed.size());
    for (var job : claimed) {
      pool.submit(() -> process(job));
    }
  }

  void process(ExtractionJobRepository.QueuedJob job) {
    long startedAt = System.currentTimeMillis();
    try {
      CoverageItem item =
          coverage
              .findById(job.coverageItemId())
              .orElseThrow(() -> new IllegalStateException("coverage_item missing"));
      Report report =
          reports
              .findById(item.reportId())
              .orElseThrow(() -> new IllegalStateException("report missing"));
      var workspaceId = report.workspaceId();
      String subjectName =
          clients
              .findInWorkspace(workspaceId, report.clientId())
              .map(c -> c.name())
              .orElse("the subject");

      var fetched = fetcher.fetch(item.sourceUrl());
      if (fetched.isEmpty()) {
        retryOrFail(job, "fetch_returned_empty");
        return;
      }
      var article = fetched.get();

      // Outlet: upsert by domain, then classify tier if it's still default.
      String domain = Domains.apexFromUrl(item.sourceUrl()).orElse("");
      Outlet outlet =
          domain.isBlank()
              ? null
              : outlets.upsertByDomain(
                  domain,
                  article.outletName() != null
                      ? article.outletName()
                      : Domains.outletNameFromDomain(domain));
      int tier = outlet == null ? 3 : tierClassifier.classifyIfNeeded(outlet);

      // Screenshot (best-effort).
      String screenshotUrl =
          workspaceId == null
              ? null
              : screenshots.capture(workspaceId, item.sourceUrl()).orElse(null);

      // Persist fetcher-derived fields first (so the user sees something even if LLM fails).
      coverage.applyFetched(
          item.id(),
          outlet == null ? null : outlet.id(),
          article.headline(),
          article.publishDate(),
          firstSentenceOf(article.cleanText()),
          tier,
          /* estimatedReach */ null,
          screenshotUrl);

      // Now the LLM extraction (sentiment, summary, quote, topics, prominence). Skipped quietly
      // when ANTHROPIC_API_KEY isn't set so local dev keeps working.
      if (extraction.isEnabled()) {
        ClientContext context = clientContexts.findByClient(report.clientId()).orElse(null);
        var outcome =
            extraction
                .extract(
                    item.sourceUrl(),
                    outlet == null ? null : outlet.name(),
                    subjectName,
                    article.cleanText(),
                    context)
                .orElse(null);
        if (outcome != null) {
          var r = outcome.result();
          Author author =
              r.author() == null
                  ? null
                  : authors.upsert(r.author(), outlet == null ? null : outlet.id());
          coverage.applyExtracted(
              item.id(),
              author == null ? null : author.id(),
              r.subheadline(),
              r.summary(),
              r.keyQuote(),
              r.sentiment(),
              r.sentimentRationale(),
              r.subjectProminence(),
              r.topics(),
              outcome.promptVersion(),
              json.writeValueAsString(r));
        }
      }

      jobs.markDone(job.id());
      alertService.recomputeFor(report.clientId());
      activity.recordWorker(
          workspaceId,
          EventKinds.REPORT_COVERAGE_EXTRACTED,
          "coverage_item",
          item.id(),
          Duration.ofMillis(System.currentTimeMillis() - startedAt),
          Map.of(
              "fetcher",
              article.fetcher(),
              "llm",
              extraction.isEnabled(),
              "outlet",
              outlet == null ? "" : outlet.domain()));
      log.info(
          "extraction: done item={} url={} fetcher={} llm={}",
          item.id(),
          item.sourceUrl(),
          article.fetcher(),
          extraction.isEnabled());
    } catch (Exception e) {
      log.warn("extraction: job {} failed: {}", job.id(), e.toString());
      activity.recordSystem(
          EventKinds.EXTRACTION_FAILED,
          "extraction_job",
          job.id(),
          Map.of("error_class", e.getClass().getSimpleName()));
      // Best-effort: re-derive client_id via the coverage row for the failure case.
      try {
        coverage
            .findById(job.coverageItemId())
            .flatMap(ci -> reports.findById(ci.reportId()))
            .ifPresent(r -> alertService.recomputeFor(r.clientId()));
      } catch (Exception ignored) {
        /* fire-and-forget */
      }
      retryOrFail(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private void retryOrFail(ExtractionJobRepository.QueuedJob job, String reason) {
    if (job.attemptCount() < MAX_ATTEMPTS) {
      jobs.requeue(job.id());
      log.info("extraction: requeueing job {} (attempt {})", job.id(), job.attemptCount());
    } else {
      jobs.markFailed(job.id(), reason);
      coverage.markFailed(job.coverageItemId(), reason);
      log.warn(
          "extraction: job {} permanently failed after {} attempts", job.id(), job.attemptCount());
    }
  }

  /** Returns the first ~280 chars of text, ending on a sentence boundary if possible. */
  static String firstSentenceOf(String text) {
    if (text == null || text.isBlank()) return null;
    int max = Math.min(text.length(), 280);
    String window = text.substring(0, max);
    int period = window.lastIndexOf('.');
    return period > 80 ? window.substring(0, period + 1) : window;
  }
}
