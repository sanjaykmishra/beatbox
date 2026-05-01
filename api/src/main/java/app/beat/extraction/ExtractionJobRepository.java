package app.beat.extraction;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExtractionJobRepository {

  private final JdbcClient jdbc;

  public ExtractionJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record QueuedJob(UUID id, UUID coverageItemId, int attemptCount) {}

  /**
   * Enqueue (or re-enqueue) a job. The {@code coverage_item_id} column is unique, so we UPSERT: a
   * fresh paste creates a new row in {@code queued}; a Retry on a previously-{@code failed} row
   * resets it to {@code queued} with last_error cleared and started/completed_at NULL. Without the
   * UPSERT, Retry threw a duplicate-key constraint violation because the failed row from the
   * original attempt was still in the table.
   */
  public void enqueue(UUID coverageItemId) {
    jdbc.sql(
            """
            INSERT INTO extraction_jobs (coverage_item_id)
            VALUES (:c)
            ON CONFLICT (coverage_item_id) DO UPDATE SET
              status = 'queued',
              last_error = NULL,
              started_at = NULL,
              completed_at = NULL,
              queued_at = now(),
              updated_at = now()
            """)
        .param("c", coverageItemId)
        .update();
  }

  /**
   * Atomically claim up to `n` queued jobs and mark them running. Uses SELECT FOR UPDATE SKIP
   * LOCKED so multiple workers can drain the queue without colliding.
   */
  public List<QueuedJob> claimBatch(int n) {
    return jdbc.sql(
            """
            UPDATE extraction_jobs SET
              status = 'running',
              attempt_count = attempt_count + 1,
              started_at = now()
            WHERE id IN (
              SELECT id FROM extraction_jobs
              WHERE status = 'queued'
              ORDER BY queued_at
              FOR UPDATE SKIP LOCKED
              LIMIT :n
            )
            RETURNING id, coverage_item_id, attempt_count
            """)
        .param("n", n)
        .query(
            (rs, i) ->
                new QueuedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("coverage_item_id", UUID.class),
                    rs.getInt("attempt_count")))
        .list();
  }

  public void markDone(UUID id) {
    jdbc.sql(
            "UPDATE extraction_jobs SET status='done', completed_at=now(), last_error=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            """
            UPDATE extraction_jobs SET
              status='failed', completed_at=now(), last_error=:e
            WHERE id=:id
            """)
        .param("id", id)
        .param("e", error)
        .update();
  }

  /** Mark a previously-running job back to queued for another attempt. */
  public void requeue(UUID id) {
    jdbc.sql("UPDATE extraction_jobs SET status='queued', started_at=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }
}
