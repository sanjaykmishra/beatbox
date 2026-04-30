package app.beat.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Async, fire-and-forget recorder for {@code activity_events}. A failure in here MUST NOT break the
 * user's request, per docs/15-additions.md §15.2.
 *
 * <p>Buffer flushes every 2 seconds (or when the queue fills). Drops oldest events under sustained
 * pressure rather than blocking the request thread.
 */
@Service
public class ActivityRecorder {

  private static final Logger log = LoggerFactory.getLogger(ActivityRecorder.class);
  private static final int CAPACITY = 2048;

  private final JdbcClient jdbc;
  private final ObjectMapper json = new ObjectMapper();
  private final BlockingQueue<PendingEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
  private final ScheduledExecutorService flusher =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "activity-flusher");
            t.setDaemon(true);
            return t;
          });

  public ActivityRecorder(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @PostConstruct
  void start() {
    flusher.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    log.info("ActivityRecorder started (capacity={})", CAPACITY);
  }

  @PreDestroy
  void stop() {
    flusher.shutdown();
    flush(); // best-effort drain on shutdown
    try {
      flusher.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** User-actor convenience. */
  public void recordUser(
      UUID workspaceId,
      UUID userId,
      String kind,
      String targetType,
      UUID targetId,
      Map<String, Object> metadata) {
    enqueue(
        new PendingEvent("user", workspaceId, userId, kind, targetType, targetId, null, metadata));
  }

  /** Worker-actor convenience (no user). */
  public void recordWorker(
      UUID workspaceId,
      String kind,
      String targetType,
      UUID targetId,
      Duration duration,
      Map<String, Object> metadata) {
    Integer durationMs =
        duration == null ? null : (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
    enqueue(
        new PendingEvent(
            "worker", workspaceId, null, kind, targetType, targetId, durationMs, metadata));
  }

  /** System-actor convenience for events with no user / workspace context. */
  public void recordSystem(
      String kind, String targetType, UUID targetId, Map<String, Object> metadata) {
    enqueue(new PendingEvent("system", null, null, kind, targetType, targetId, null, metadata));
  }

  private void enqueue(PendingEvent e) {
    if (!queue.offer(e)) {
      // Drop the oldest, then offer again. Don't block the caller.
      queue.poll();
      queue.offer(e);
      log.debug("activity_events buffer full; dropped oldest");
    }
  }

  void flush() {
    if (queue.isEmpty()) return;
    int drained = 0;
    PendingEvent e;
    while ((e = queue.poll()) != null) {
      try {
        String md = e.metadata == null ? "{}" : json.writeValueAsString(e.metadata);
        jdbc.sql(
                """
                INSERT INTO activity_events (
                  workspace_id, user_id, actor_type, kind, target_type, target_id,
                  duration_ms, metadata
                )
                VALUES (:w, :u, :a, :k, :tt, :ti, :d, CAST(:m AS jsonb))
                """)
            .param("w", e.workspaceId)
            .param("u", e.userId)
            .param("a", e.actorType)
            .param("k", e.kind)
            .param("tt", e.targetType)
            .param("ti", e.targetId)
            .param("d", e.durationMs)
            .param("m", md)
            .update();
        drained++;
      } catch (Exception ex) {
        log.warn("activity_events insert failed (event={}): {}", e.kind, ex.toString());
      }
    }
    if (drained > 0) log.debug("ActivityRecorder flushed {} event(s)", drained);
  }

  private record PendingEvent(
      String actorType,
      UUID workspaceId,
      UUID userId,
      String kind,
      String targetType,
      UUID targetId,
      Integer durationMs,
      Map<String, Object> metadata) {}
}
