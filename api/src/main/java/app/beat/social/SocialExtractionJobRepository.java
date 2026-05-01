package app.beat.social;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Queue table driver for {@code social_extraction_jobs}. Drained by {@link SocialExtractionWorker}.
 * Mirrors the article-side {@code app.beat.extraction.ExtractionJobRepository} shape — claim batch
 * with {@code FOR UPDATE SKIP LOCKED}, retry on transient failure, mark done.
 */
@Repository
public class SocialExtractionJobRepository {

  private final JdbcClient jdbc;

  public SocialExtractionJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record QueuedJob(UUID id, UUID socialMentionId, int attemptCount) {}

  /**
   * Enqueue (or re-enqueue) a job. The {@code social_mention_id} column is unique, so we UPSERT: a
   * fresh paste creates a new row in {@code queued}; a Retry on a previously-{@code failed} row
   * resets it to {@code queued} with last_error cleared and started/completed_at NULL. Returns true
   * on insert, false on update (kept for backward-compat with prior callers that checked the
   * boolean).
   */
  public boolean enqueue(UUID socialMentionId) {
    try {
      jdbc.sql(
              """
              INSERT INTO social_extraction_jobs (social_mention_id)
              VALUES (:m)
              ON CONFLICT (social_mention_id) DO UPDATE SET
                status = 'queued',
                last_error = NULL,
                started_at = NULL,
                completed_at = NULL,
                queued_at = now()
              """)
          .param("m", socialMentionId)
          .update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public List<QueuedJob> claimBatch(int n) {
    return jdbc.sql(
            """
            UPDATE social_extraction_jobs SET
              status = 'running',
              attempt_count = attempt_count + 1,
              started_at = now()
            WHERE id IN (
              SELECT id FROM social_extraction_jobs
              WHERE status = 'queued'
              ORDER BY queued_at
              FOR UPDATE SKIP LOCKED
              LIMIT :n
            )
            RETURNING id, social_mention_id, attempt_count
            """)
        .param("n", n)
        .query(
            (rs, i) ->
                new QueuedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("social_mention_id", UUID.class),
                    rs.getInt("attempt_count")))
        .list();
  }

  public void markDone(UUID id) {
    jdbc.sql(
            "UPDATE social_extraction_jobs SET status='done', completed_at=now(),"
                + " last_error=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            "UPDATE social_extraction_jobs SET status='failed', completed_at=now(),"
                + " last_error=:e WHERE id=:id")
        .param("id", id)
        .param("e", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)))
        .update();
  }

  public void requeue(UUID id) {
    jdbc.sql("UPDATE social_extraction_jobs SET status='queued', started_at=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }
}
