package app.beat.llm;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LlmBatchJobRepository {

  private final JdbcClient jdbc;

  public LlmBatchJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<LlmBatchJob> MAPPER =
      (ResultSet rs, int n) ->
          new LlmBatchJob(
              rs.getObject("id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getString("feature"),
              rs.getObject("target_id", UUID.class),
              rs.getString("anthropic_batch_id"),
              rs.getString("status"),
              rs.getInt("request_count"),
              rs.getInt("succeeded_count"),
              rs.getInt("errored_count"),
              rs.getString("metadata"),
              rs.getString("last_error"),
              ts(rs, "submitted_at"),
              ts(rs, "completed_at"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public LlmBatchJob insert(
      UUID workspaceId, String feature, UUID targetId, int requestCount, String metadataJson) {
    return jdbc.sql(
            """
            INSERT INTO llm_batch_jobs
              (workspace_id, feature, target_id, request_count, metadata)
            VALUES
              (:ws, :f, :t, :rc, CAST(:m AS jsonb))
            RETURNING *
            """)
        .param("ws", workspaceId)
        .param("f", feature)
        .param("t", targetId)
        .param("rc", requestCount)
        .param("m", metadataJson == null ? "{}" : metadataJson)
        .query(MAPPER)
        .single();
  }

  public Optional<LlmBatchJob> findById(UUID id) {
    return jdbc.sql("SELECT * FROM llm_batch_jobs WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /** Open batches (queued/in_progress) — drained by {@link BatchPoller}. */
  public List<LlmBatchJob> findOpen(int limit) {
    return jdbc.sql(
            "SELECT * FROM llm_batch_jobs WHERE status IN ('queued','in_progress') ORDER BY created_at LIMIT :n")
        .param("n", limit)
        .query(MAPPER)
        .list();
  }

  public void markSubmitted(UUID id, String anthropicBatchId) {
    jdbc.sql(
            "UPDATE llm_batch_jobs SET anthropic_batch_id=:a, status='in_progress', submitted_at=now(), updated_at=now() WHERE id=:id")
        .param("id", id)
        .param("a", anthropicBatchId)
        .update();
  }

  public void updateProgress(UUID id, int succeeded, int errored, String anthropicStatus) {
    jdbc.sql(
            """
            UPDATE llm_batch_jobs SET
              succeeded_count = :s,
              errored_count = :e,
              status = :st,
              updated_at = now(),
              completed_at = CASE WHEN :st IN ('ended','failed','cancelled') THEN now() ELSE completed_at END
            WHERE id = :id
            """)
        .param("id", id)
        .param("s", succeeded)
        .param("e", errored)
        .param("st", anthropicStatus)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            "UPDATE llm_batch_jobs SET status='failed', last_error=:e, completed_at=now(), updated_at=now() WHERE id=:id")
        .param("id", id)
        .param("e", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)))
        .update();
  }

  /** Test/maintenance helper; not on the controller surface. */
  public void touch(UUID id, Timestamp now) {
    jdbc.sql("UPDATE llm_batch_jobs SET updated_at = :n WHERE id = :id")
        .param("id", id)
        .param("n", now)
        .update();
  }
}
