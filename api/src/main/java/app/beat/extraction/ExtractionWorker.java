package app.beat.extraction;

import app.beat.coverage.CoverageItem;
import app.beat.coverage.CoverageItemRepository;
import app.beat.outlet.Domains;
import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import app.beat.report.Report;
import app.beat.report.ReportRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains extraction_jobs. For each queued job: claim atomically, fetch the article via the layered
 * fetcher, upsert the outlet, capture a screenshot, write back to coverage_items, mark the job done
 * — or failed with a reason. Per-job concurrency is bounded by a fixed thread pool.
 *
 * <p>v1 dispatch is a periodic poll (every 1s). LISTEN/NOTIFY is a future optimization.
 */
@Component
public class ExtractionWorker {

  private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final int BATCH_SIZE = 4;

  private final ExtractionJobRepository jobs;
  private final CoverageItemRepository coverage;
  private final ReportRepository reports;
  private final OutletRepository outlets;
  private final LayeredArticleFetcher fetcher;
  private final ScreenshotClient screenshots;
  private final boolean enabled;

  private final ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);

  public ExtractionWorker(
      ExtractionJobRepository jobs,
      CoverageItemRepository coverage,
      ReportRepository reports,
      OutletRepository outlets,
      LayeredArticleFetcher fetcher,
      ScreenshotClient screenshots,
      @Value("${beat.extraction.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.coverage = coverage;
    this.reports = reports;
    this.outlets = outlets;
    this.fetcher = fetcher;
    this.screenshots = screenshots;
    this.enabled = enabled;
  }

  @PostConstruct
  void start() {
    log.info("extraction worker enabled={}, batch_size={}", enabled, BATCH_SIZE);
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

      var fetched = fetcher.fetch(item.sourceUrl());
      if (fetched.isEmpty()) {
        retryOrFail(job, "fetch_returned_empty");
        return;
      }
      var article = fetched.get();
      String domain = Domains.apexFromUrl(item.sourceUrl()).orElse("");
      Outlet outlet =
          domain.isBlank()
              ? null
              : outlets.upsertByDomain(
                  domain,
                  article.outletName() != null
                      ? article.outletName()
                      : Domains.outletNameFromDomain(domain));
      String screenshotUrl =
          workspaceId == null
              ? null
              : screenshots.capture(workspaceId, item.sourceUrl()).orElse(null);

      coverage.applyFetched(
          item.id(),
          outlet == null ? null : outlet.id(),
          article.headline(),
          article.publishDate(),
          firstSentenceOf(article.cleanText()),
          outlet == null ? null : outlet.tier(),
          /* estimatedReach */ null, // computed in week 4 from outlet stats
          screenshotUrl);
      jobs.markDone(job.id());
      log.info(
          "extraction: done item={} url={} fetcher={}",
          item.id(),
          item.sourceUrl(),
          article.fetcher());
    } catch (Exception e) {
      log.warn("extraction: job {} failed: {}", job.id(), e.toString());
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
