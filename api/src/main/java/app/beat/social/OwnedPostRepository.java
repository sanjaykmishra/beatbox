package app.beat.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
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
public class OwnedPostRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final RowMapper<OwnedPost> mapper;

  public OwnedPostRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.mapper =
        (ResultSet rs, int n) ->
            new OwnedPost(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getString("title"),
                rs.getString("primary_content_text"),
                OwnedPost.deserializeVariants(rs.getString("platform_variants"), json),
                textArray(rs, "target_platforms"),
                ts(rs, "scheduled_for"),
                rs.getString("timezone"),
                rs.getString("status"),
                rs.getString("series_tag"),
                rs.getObject("drafted_by_user_id", UUID.class),
                ts(rs, "submitted_for_review_at"),
                ts(rs, "approved_at"),
                ts(rs, "posted_at"),
                uuidArray(rs, "asset_ids"),
                ts(rs, "created_at"),
                ts(rs, "updated_at"),
                ts(rs, "deleted_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws java.sql.SQLException {
    var t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static List<String> textArray(ResultSet rs, String col) throws java.sql.SQLException {
    Array a = rs.getArray(col);
    if (a == null) return List.of();
    String[] out = (String[]) a.getArray();
    return out == null ? List.of() : List.of(out);
  }

  private static List<UUID> uuidArray(ResultSet rs, String col) throws java.sql.SQLException {
    Array a = rs.getArray(col);
    if (a == null) return List.of();
    UUID[] out = (UUID[]) a.getArray();
    return out == null ? List.of() : List.of(out);
  }

  /** Postgres JDBC can't infer types for {@code java.time.Instant}; convert explicitly. */
  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  public OwnedPost insert(
      UUID workspaceId,
      UUID clientId,
      String title,
      String primaryContentText,
      List<String> targetPlatforms,
      Instant scheduledFor,
      String timezone,
      String seriesTag,
      UUID draftedByUserId) {
    return jdbc.sql(
            """
            INSERT INTO owned_posts (
              workspace_id, client_id, title, primary_content_text,
              target_platforms, scheduled_for, timezone, series_tag, drafted_by_user_id
            )
            VALUES (:w, :c, :title, :content, :tp, :sched, :tz, :series, :u)
            RETURNING *
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("title", title)
        .param("content", primaryContentText)
        .param(
            "tp", targetPlatforms == null ? new String[0] : targetPlatforms.toArray(new String[0]))
        .param("sched", ts(scheduledFor))
        .param("tz", timezone == null ? "America/Los_Angeles" : timezone)
        .param("series", seriesTag)
        .param("u", draftedByUserId)
        .query(mapper)
        .single();
  }

  public Optional<OwnedPost> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql(
            "SELECT * FROM owned_posts "
                + "WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL")
        .param("id", id)
        .param("w", workspaceId)
        .query(mapper)
        .optional();
  }

  /**
   * List with optional filters. {@code clientId}, {@code status}, {@code seriesTag}, {@code
   * platform}, {@code from}, {@code to} may all be null. Sorts by scheduled_for ascending (nulls
   * last) so calendar views render naturally.
   */
  public List<OwnedPost> list(
      UUID workspaceId,
      UUID clientId,
      String status,
      String seriesTag,
      String platform,
      Instant from,
      Instant to,
      int limit) {
    return jdbc.sql(
            """
            SELECT * FROM owned_posts
            WHERE workspace_id = :w AND deleted_at IS NULL
              AND (:c::uuid IS NULL OR client_id = :c)
              AND (:status::text IS NULL OR status = :status)
              AND (:series::text IS NULL OR series_tag = :series)
              AND (:platform::text IS NULL OR :platform = ANY (target_platforms))
              AND (:from::timestamptz IS NULL OR scheduled_for >= :from)
              AND (:to::timestamptz IS NULL OR scheduled_for < :to)
            ORDER BY scheduled_for ASC NULLS LAST, created_at DESC
            LIMIT :limit
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("status", status)
        .param("series", seriesTag)
        .param("platform", platform)
        .param("from", ts(from))
        .param("to", ts(to))
        .param("limit", Math.min(Math.max(limit, 1), 500))
        .query(mapper)
        .list();
  }

  /**
   * Patch with optional fields. Null values mean "leave alone"; empty values for nullable string
   * columns are treated as "set NULL". Variants are passed in already-resolved form.
   */
  public OwnedPost update(
      UUID id,
      String title,
      String primaryContentText,
      java.util.Map<String, OwnedPost.PlatformVariant> platformVariants,
      List<String> targetPlatforms,
      Instant scheduledFor,
      String timezone,
      String seriesTag,
      List<UUID> assetIds) {
    String variantsJson =
        platformVariants == null ? null : OwnedPost.serializeVariants(platformVariants, json);
    return jdbc.sql(
            """
            UPDATE owned_posts SET
              title = COALESCE(:title, title),
              primary_content_text = COALESCE(:content, primary_content_text),
              platform_variants = COALESCE(CAST(:variants AS jsonb), platform_variants),
              target_platforms = COALESCE(CAST(:tp AS text[]), target_platforms),
              scheduled_for = COALESCE(:sched, scheduled_for),
              timezone = COALESCE(:tz, timezone),
              series_tag = COALESCE(:series, series_tag),
              asset_ids = COALESCE(CAST(:assets AS uuid[]), asset_ids),
              updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            RETURNING *
            """)
        .param("id", id)
        .param("title", title)
        .param("content", primaryContentText)
        .param("variants", variantsJson)
        .param("tp", targetPlatforms == null ? null : targetPlatforms.toArray(new String[0]))
        .param("sched", ts(scheduledFor))
        .param("tz", timezone)
        .param("series", seriesTag)
        .param("assets", assetIds == null ? null : assetIds.toArray(new UUID[0]))
        .query(mapper)
        .single();
  }

  /**
   * Persist a state transition with the appropriate timestamp side-effects. Caller has already
   * validated the transition is allowed.
   */
  public OwnedPost transition(UUID id, String newStatus, Instant occurredAt) {
    boolean setSubmitted = "internal_review".equals(newStatus) || "client_review".equals(newStatus);
    boolean setApproved = "approved".equals(newStatus);
    boolean setPosted = "posted".equals(newStatus);
    return jdbc.sql(
            """
            UPDATE owned_posts SET
              status = :s,
              submitted_for_review_at = CASE
                WHEN :setSubmitted AND submitted_for_review_at IS NULL THEN :now
                ELSE submitted_for_review_at END,
              approved_at = CASE WHEN :setApproved THEN :now ELSE approved_at END,
              posted_at = CASE WHEN :setPosted THEN :now ELSE posted_at END,
              updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            RETURNING *
            """)
        .param("id", id)
        .param("s", newStatus)
        .param("setSubmitted", setSubmitted)
        .param("setApproved", setApproved)
        .param("setPosted", setPosted)
        .param("now", ts(occurredAt))
        .query(mapper)
        .single();
  }

  public void softDelete(UUID id) {
    jdbc.sql(
            "UPDATE owned_posts SET deleted_at = now(), updated_at = now() "
                + "WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .update();
  }
}
