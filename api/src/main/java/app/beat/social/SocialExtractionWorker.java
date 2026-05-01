package app.beat.social;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.clientcontext.ClientContext;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.social.fetchers.ReachEstimator;
import app.beat.social.fetchers.SocialPostFetcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code social_extraction_jobs} end-to-end: claim → platform fetcher → upsert
 * social_authors → applyFetched → social-extraction LLM → applyExtracted → mark done. Mirrors the
 * article-side {@link app.beat.extraction.ExtractionWorker} shape; both workers run concurrently
 * and share no state.
 */
@Component
public class SocialExtractionWorker {

  private static final Logger log = LoggerFactory.getLogger(SocialExtractionWorker.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final int BATCH_SIZE = 4;

  private final SocialExtractionJobRepository jobs;
  private final SocialMentionRepository mentions;
  private final SocialAuthorRepository authors;
  private final ClientRepository clients;
  private final ClientContextRepository clientContexts;
  private final SocialExtractionService extraction;
  private final ActivityRecorder activity;
  private final Map<String, SocialPostFetcher> fetchersByPlatform;
  private final boolean enabled;

  private final ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);

  public SocialExtractionWorker(
      SocialExtractionJobRepository jobs,
      SocialMentionRepository mentions,
      SocialAuthorRepository authors,
      ClientRepository clients,
      ClientContextRepository clientContexts,
      SocialExtractionService extraction,
      ActivityRecorder activity,
      List<SocialPostFetcher> fetchers,
      @Value("${beat.social.extraction.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.mentions = mentions;
    this.authors = authors;
    this.clients = clients;
    this.clientContexts = clientContexts;
    this.extraction = extraction;
    this.activity = activity;
    this.fetchersByPlatform = new HashMap<>();
    for (SocialPostFetcher f : fetchers) {
      this.fetchersByPlatform.put(f.platform(), f);
    }
    this.enabled = enabled;
  }

  @PostConstruct
  void start() {
    log.info(
        "social extraction worker enabled={} llm={} fetchers={} batch_size={}",
        enabled,
        extraction.isEnabled() ? "anthropic" : "disabled",
        fetchersByPlatform.keySet(),
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

  @Scheduled(fixedDelayString = "${beat.social.extraction.poll-ms:1000}")
  public void drain() {
    if (!enabled) return;
    List<SocialExtractionJobRepository.QueuedJob> claimed = jobs.claimBatch(BATCH_SIZE);
    if (claimed.isEmpty()) return;
    log.debug("social extraction: claimed {} job(s)", claimed.size());
    for (var job : claimed) {
      pool.submit(() -> process(job));
    }
  }

  void process(SocialExtractionJobRepository.QueuedJob job) {
    long startedAt = System.currentTimeMillis();
    try {
      SocialMention mention =
          mentions
              .findById(job.socialMentionId())
              .orElseThrow(() -> new IllegalStateException("social_mention missing"));

      SocialPostFetcher fetcher = fetchersByPlatform.get(mention.platform());
      if (fetcher == null) {
        // No fetcher wired for this platform yet — fail explicitly so the user sees a useful
        // message and can paste the content manually once that surface ships.
        mentions.markFailed(
            mention.id(),
            "Beat doesn't yet support automatic extraction for "
                + mention.platform()
                + ". Paste the post text manually from the edit drawer.");
        jobs.markFailed(job.id(), "no_fetcher_for_" + mention.platform());
        recordFailure(mention, "no_fetcher_for_" + mention.platform());
        return;
      }

      var fetched = fetcher.fetch(mention.sourceUrl()).orElse(null);
      if (fetched == null) {
        retryOrFail(job, mention, "fetch_returned_empty");
        return;
      }

      Long estimatedReach =
          ReachEstimator.estimate(
              fetched.platform(), fetched.authorFollowerCount(), fetched.viewsCount());

      UUID authorId =
          authors
              .upsert(
                  fetched.platform(),
                  fetched.authorHandle(),
                  fetched.authorDisplayName(),
                  fetched.authorBio(),
                  fetched.authorFollowerCount(),
                  fetched.authorProfileUrl(),
                  fetched.authorAvatarUrl(),
                  fetched.authorVerified())
              .id();

      mentions.applyFetched(
          mention.id(),
          authorId,
          fetched.postedAt(),
          fetched.contentText(),
          fetched.contentLang(),
          fetched.likesCount(),
          fetched.repostsCount(),
          fetched.repliesCount(),
          fetched.viewsCount(),
          estimatedReach,
          fetched.authorFollowerCount(),
          fetched.isReply(),
          fetched.isQuote(),
          fetched.parentPostUrl(),
          fetched.threadRootUrl(),
          fetched.externalPostId(),
          fetched.hasMedia(),
          fetched.mediaUrls());

      // LLM extraction. Skipped quietly when ANTHROPIC_API_KEY isn't set so local dev keeps
      // working — the row is left with the fetched fields and no LLM summary.
      if (extraction.isEnabled()) {
        String subjectName =
            clients
                .findInWorkspace(mention.workspaceId(), mention.clientId())
                .map(c -> c.name())
                .orElse("the subject");
        ClientContext context = clientContexts.findByClient(mention.clientId()).orElse(null);
        var outcome = extraction.extract(fetched, subjectName, context).orElse(null);
        if (outcome != null) {
          mentions.applyExtracted(
              mention.id(),
              outcome.result().summary(),
              outcome.result().sentiment(),
              outcome.result().sentimentRationale(),
              outcome.result().subjectProminence(),
              outcome.result().topics(),
              outcome.result().mediaSummary(),
              outcome.promptVersion(),
              outcome.rawJson());
        }
      }

      jobs.markDone(job.id());
      activity.recordWorker(
          mention.workspaceId(),
          EventKinds.SOCIAL_MENTION_EXTRACTED,
          "social_mention",
          mention.id(),
          Duration.ofMillis(System.currentTimeMillis() - startedAt),
          Map.of(
              "platform", mention.platform(),
              "llm", extraction.isEnabled()));
      log.info(
          "social extraction: done id={} platform={} llm={}",
          mention.id(),
          mention.platform(),
          extraction.isEnabled());
    } catch (Exception e) {
      log.warn("social extraction: job {} failed: {}", job.id(), e.toString());
      retryOrFail(
          job,
          mentions.findById(job.socialMentionId()).orElse(null),
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private void retryOrFail(
      SocialExtractionJobRepository.QueuedJob job, SocialMention mention, String reason) {
    if (job.attemptCount() < MAX_ATTEMPTS) {
      jobs.requeue(job.id());
      log.info("social extraction: requeueing job {} (attempt {})", job.id(), job.attemptCount());
    } else {
      jobs.markFailed(job.id(), reason);
      if (mention != null) mentions.markFailed(mention.id(), reason);
      log.warn(
          "social extraction: job {} permanently failed after {} attempts",
          job.id(),
          job.attemptCount());
      if (mention != null) recordFailure(mention, reason);
    }
  }

  private void recordFailure(SocialMention mention, String reason) {
    activity.recordSystem(
        EventKinds.SOCIAL_EXTRACTION_FAILED,
        "social_mention",
        mention.id(),
        Map.of(
            "platform",
            mention.platform() == null ? "" : mention.platform(),
            "error_class",
            reason));
  }
}
