package app.beat.render;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RenderJobRepository {

  private final JdbcClient jdbc;

  public RenderJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record QueuedJob(UUID id, UUID reportId, int attemptCount) {}

  /**
   * Enqueue or re-queue a render job for the given report. There's at most one row per report
   * (UNIQUE on report_id from V005), so this is an UPSERT: if no row exists, insert a fresh
   * 'queued' job; if a row exists from a previous generation cycle, flip it back to 'queued' and
   * clear its terminal fields so the worker picks it up again. Returns true in both cases.
   *
   * <p>Without the upsert, re-Generate (after a successful first run) would silently no-op: the old
   * 'done' row from the previous cycle would block the new INSERT, the worker would never see
   * anything new, and the report would hang in 'processing' forever.
   */
  public boolean enqueue(UUID reportId) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO render_jobs (report_id) VALUES (:r)
                ON CONFLICT (report_id) DO UPDATE SET
                  status = 'queued',
                  started_at = NULL,
                  completed_at = NULL,
                  last_error = NULL,
                  attempt_count = 0
                """)
            .param("r", reportId)
            .update();
    return rows > 0;
  }

  public List<QueuedJob> claimBatch(int n) {
    return jdbc.sql(
            """
            UPDATE render_jobs SET
              status = 'running',
              attempt_count = attempt_count + 1,
              started_at = now()
            WHERE id IN (
              SELECT id FROM render_jobs
              WHERE status = 'queued'
              ORDER BY queued_at
              FOR UPDATE SKIP LOCKED
              LIMIT :n
            )
            RETURNING id, report_id, attempt_count
            """)
        .param("n", n)
        .query(
            (rs, i) ->
                new QueuedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("report_id", UUID.class),
                    rs.getInt("attempt_count")))
        .list();
  }

  public void markDone(UUID id) {
    jdbc.sql(
            "UPDATE render_jobs SET status='done', completed_at=now(), last_error=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            "UPDATE render_jobs SET status='failed', completed_at=now(), last_error=:e WHERE id=:id")
        .param("id", id)
        .param("e", error)
        .update();
  }

  public void requeue(UUID id) {
    jdbc.sql("UPDATE render_jobs SET status='queued', started_at=NULL WHERE id=:id")
        .param("id", id)
        .update();
  }
}
