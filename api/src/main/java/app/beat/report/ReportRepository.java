package app.beat.report;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {

  private final JdbcClient jdbc;

  public ReportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<Report> MAPPER =
      (ResultSet rs, int n) ->
          new Report(
              rs.getObject("id", UUID.class),
              rs.getObject("client_id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getObject("template_id", UUID.class),
              rs.getString("title"),
              rs.getObject("period_start", LocalDate.class),
              rs.getObject("period_end", LocalDate.class),
              rs.getString("status"),
              rs.getString("executive_summary"),
              rs.getBoolean("executive_summary_edited"),
              rs.getString("pdf_url"),
              rs.getString("share_token"),
              ts(rs, "share_token_expires_at"),
              ts(rs, "generated_at"),
              rs.getString("failure_reason"),
              rs.getObject("created_by_user_id", UUID.class),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  private static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public Report insert(
      UUID clientId,
      UUID workspaceId,
      UUID templateId,
      String title,
      LocalDate periodStart,
      LocalDate periodEnd,
      UUID createdByUserId) {
    return jdbc.sql(
            """
            INSERT INTO reports (client_id, workspace_id, template_id, title,
                                 period_start, period_end, created_by_user_id)
            VALUES (:c, :w, :t, :title, :ps, :pe, :u)
            RETURNING *
            """)
        .param("c", clientId)
        .param("w", workspaceId)
        .param("t", templateId)
        .param("title", title)
        .param("ps", periodStart)
        .param("pe", periodEnd)
        .param("u", createdByUserId)
        .query(MAPPER)
        .single();
  }

  public Optional<Report> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql(
            "SELECT * FROM reports WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL")
        .param("id", id)
        .param("w", workspaceId)
        .query(MAPPER)
        .optional();
  }

  public Optional<Report> findById(UUID id) {
    return jdbc.sql("SELECT * FROM reports WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /** Latest report for a client whose period_end is on or before the given date. */
  public Optional<Report> findLatestForClientUpTo(UUID clientId, java.time.LocalDate ceiling) {
    return jdbc.sql(
            """
            SELECT * FROM reports
            WHERE client_id = :c AND deleted_at IS NULL AND period_end <= :ceil
            ORDER BY period_end DESC
            LIMIT 1
            """)
        .param("c", clientId)
        .param("ceil", ceiling)
        .query(MAPPER)
        .optional();
  }

  public void setStatus(UUID id, String status) {
    jdbc.sql("UPDATE reports SET status = :s WHERE id = :id")
        .param("id", id)
        .param("s", status)
        .update();
  }

  public void markReady(UUID id, String pdfUrl) {
    jdbc.sql(
            """
            UPDATE reports SET status='ready', pdf_url=:p, generated_at=now(), failure_reason=NULL
            WHERE id=:id
            """)
        .param("id", id)
        .param("p", pdfUrl)
        .update();
  }

  public void markFailed(UUID id, String reason) {
    jdbc.sql("UPDATE reports SET status='failed', failure_reason=:r WHERE id=:id")
        .param("id", id)
        .param("r", reason)
        .update();
  }

  public void setShareToken(UUID id, String tokenHash, java.time.Instant expiresAt) {
    jdbc.sql("UPDATE reports SET share_token=:t, share_token_expires_at=:e WHERE id=:id")
        .param("id", id)
        .param("t", tokenHash)
        .param("e", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
        .update();
  }

  /** Count reports created in the current calendar month for a workspace. */
  public int countThisMonth(UUID workspaceId) {
    Integer n =
        jdbc.sql(
                """
                SELECT count(*) FROM reports
                WHERE workspace_id = :w
                  AND deleted_at IS NULL
                  AND created_at >= date_trunc('month', now())
                """)
            .param("w", workspaceId)
            .query(Integer.class)
            .single();
    return n == null ? 0 : n;
  }

  public Optional<Report> findActiveByShareToken(String tokenHash) {
    return jdbc.sql(
            """
            SELECT * FROM reports
            WHERE share_token = :t AND deleted_at IS NULL
              AND (share_token_expires_at IS NULL OR share_token_expires_at > now())
              AND status = 'ready'
            """)
        .param("t", tokenHash)
        .query(MAPPER)
        .optional();
  }

  /** Worker-side: persist an LLM-generated summary only if the user hasn't pinned an edit. */
  public void setGeneratedSummary(UUID id, String summary) {
    jdbc.sql(
            """
            UPDATE reports SET executive_summary = :s
            WHERE id = :id AND executive_summary_edited = false
            """)
        .param("id", id)
        .param("s", summary)
        .update();
  }

  /** User-side edit: pins executive_summary_edited=true so re-runs don't overwrite. */
  public Optional<Report> setEditedSummary(UUID workspaceId, UUID id, String summary) {
    int rows =
        jdbc.sql(
                """
                UPDATE reports SET executive_summary = :s, executive_summary_edited = true
                WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL
                """)
            .param("id", id)
            .param("w", workspaceId)
            .param("s", summary)
            .update();
    if (rows == 0) return Optional.empty();
    return findInWorkspace(workspaceId, id);
  }
}
