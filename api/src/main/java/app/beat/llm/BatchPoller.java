package app.beat.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains finished Anthropic batches and dispatches results to feature-specific {@link
 * BatchResultHandler}s. Runs on a slow cadence — batches typically take 5–30 minutes, so polling
 * every 60 seconds is plenty.
 *
 * <p>No-op when no API key is configured or no batches are open. Phase 1 / 1.5 currently submit
 * zero batches; the poller is wired so Phase 3 Part 2 features (ranking, drafting) can drop in.
 */
@Component
public class BatchPoller {

  private static final Logger log = LoggerFactory.getLogger(BatchPoller.class);

  private final AnthropicBatchClient batches;
  private final LlmBatchJobRepository repo;
  private final Map<String, BatchResultHandler> handlersByFeature;

  public BatchPoller(
      AnthropicBatchClient batches, LlmBatchJobRepository repo, List<BatchResultHandler> handlers) {
    this.batches = batches;
    this.repo = repo;
    this.handlersByFeature = new HashMap<>();
    for (BatchResultHandler h : handlers) {
      handlersByFeature.put(h.feature(), h);
    }
  }

  @Scheduled(fixedDelayString = "${beat.llm.batch.poll-ms:60000}")
  public void drain() {
    if (!batches.isConfigured()) return;
    var open = repo.findOpen(20);
    if (open.isEmpty()) return;
    for (LlmBatchJob job : open) {
      if (job.anthropicBatchId() == null || job.anthropicBatchId().isBlank()) continue;
      try {
        var status = batches.poll(job.anthropicBatchId());
        repo.updateProgress(
            job.id(), status.succeeded(), status.errored(), status.processingStatus());
        if ("ended".equals(status.processingStatus())) {
          var results = batches.fetchResults(job.anthropicBatchId());
          BatchResultHandler handler = handlersByFeature.get(job.feature());
          if (handler == null) {
            log.warn(
                "batch_poller: no handler registered for feature={}, results dropped (id={})",
                job.feature(),
                job.id());
            continue;
          }
          handler.handle(job, results);
          log.info(
              "batch_poller: drained id={} feature={} succeeded={} errored={}",
              job.id(),
              job.feature(),
              status.succeeded(),
              status.errored());
        }
      } catch (RuntimeException e) {
        log.warn("batch_poller: poll failed for id={}: {}", job.id(), e.toString());
        repo.markFailed(job.id(), e.getMessage());
      }
    }
  }
}
