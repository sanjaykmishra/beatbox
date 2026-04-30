package app.beat.render;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RenderJobRepository {

  private final JdbcClient jdbc;

  public RenderJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record QueuedJob(UUID id, UUID reportId, int attemptCount) {}

  /** Enqueue (one job per report; second enqueue while a job exists is a no-op). */
  public boolean enqueue(UUID reportId) {
    try {
      jdbc.sql("INSERT INTO render_jobs (report_id) VALUES (:r)").param("r", reportId).update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
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
