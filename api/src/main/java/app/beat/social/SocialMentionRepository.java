package app.beat.social;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * DAO for {@code social_mentions}. All read/write methods take {@code workspaceId} per
 * docs/14-multi-tenancy.md §Repository / DAO layer.
 */
@Repository
public class SocialMentionRepository {

  private final JdbcClient jdbc;

  public SocialMentionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<SocialMention> MAPPER =
      (ResultSet rs, int n) ->
          new SocialMention(
              rs.getObject("id", UUID.class),
              rs.getObject("report_id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getObject("client_id", UUID.class),
              rs.getString("platform"),
              rs.getString("source_url"),
              rs.getString("external_post_id"),
              rs.getObject("author_id", UUID.class),
              ts(rs, "posted_at"),
              rs.getString("content_text"),
              rs.getString("content_lang"),
              rs.getBoolean("has_media"),
              rs.getString("media_summary"),
              toList(rs.getArray("media_urls")),
              rs.getBoolean("is_reply"),
              rs.getBoolean("is_quote"),
              rs.getString("parent_post_url"),
              rs.getString("thread_root_url"),
              nullableLong(rs, "likes_count"),
              nullableLong(rs, "reposts_count"),
              nullableLong(rs, "replies_count"),
              nullableLong(rs, "views_count"),
              nullableLong(rs, "estimated_reach"),
              rs.getString("summary"),
              rs.getString("sentiment"),
              rs.getString("sentiment_rationale"),
              rs.getString("subject_prominence"),
              toList(rs.getArray("topics")),
              nullableLong(rs, "follower_count_at_post"),
              rs.getString("extraction_status"),
              rs.getString("extraction_error"),
              rs.getString("extraction_prompt_version"),
              rs.getString("raw_extracted"),
              rs.getBoolean("is_user_edited"),
              toList(rs.getArray("edited_fields")),
              rs.getInt("sort_order"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  static Long nullableLong(ResultSet rs, String c) throws java.sql.SQLException {
    long v = rs.getLong(c);
    return rs.wasNull() ? null : v;
  }

  static List<String> toList(java.sql.Array a) throws java.sql.SQLException {
    if (a == null) return List.of();
    Object raw = a.getArray();
    if (raw instanceof String[] s) return Arrays.asList(s);
    return List.of();
  }

  /**
   * Insert a queued social_mention. Caller is responsible for enqueuing a row in {@code
   * social_extraction_jobs} after this returns. Idempotent across (report_id, source_url): a
   * duplicate URL on the same report returns {@code Optional.empty()}.
   */
  public Optional<SocialMention> insertQueued(
      UUID workspaceId,
      UUID reportId,
      UUID clientId,
      String platform,
      String sourceUrl,
      int sortOrder) {
    try {
      SocialMention m =
          jdbc.sql(
                  """
                  INSERT INTO social_mentions (
                    workspace_id, report_id, client_id, platform, source_url,
                    extraction_status, sort_order
                  ) VALUES (
                    :ws, :r, :c, :p, :u, 'queued', :so
                  )
                  RETURNING *
                  """)
              .param("ws", workspaceId)
              .param("r", reportId)
              .param("c", clientId)
              .param("p", platform)
              .param("u", sourceUrl)
              .param("so", sortOrder)
              .query(MAPPER)
              .single();
      return Optional.of(m);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      return Optional.empty();
    }
  }

  public Optional<SocialMention> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql("SELECT * FROM social_mentions WHERE id = :id AND workspace_id = :ws")
        .param("id", id)
        .param("ws", workspaceId)
        .query(MAPPER)
        .optional();
  }

  /** Worker lookup — queries by id alone (workspace already verified at enqueue time). */
  public Optional<SocialMention> findById(UUID id) {
    return jdbc.sql("SELECT * FROM social_mentions WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /** All mentions for a report, ordered for the report builder UI. */
  public List<SocialMention> listByReport(UUID workspaceId, UUID reportId) {
    return jdbc.sql(
            """
            SELECT * FROM social_mentions
            WHERE workspace_id = :ws AND report_id = :r
            ORDER BY sort_order ASC, created_at ASC
            """)
        .param("ws", workspaceId)
        .param("r", reportId)
        .query(MAPPER)
        .list();
  }

  /**
   * Apply fetched-but-pre-LLM data: posted_at, author, engagement, content, thread context. The
   * worker calls this after the platform fetcher returns and before the LLM call so the row is
   * partially-populated even if extraction later fails.
   */
  public void applyFetched(
      UUID id,
      UUID authorId,
      Instant postedAt,
      String contentText,
      String contentLang,
      Long likes,
      Long reposts,
      Long replies,
      Long views,
      Long estimatedReach,
      Long followerCountAtPost,
      boolean isReply,
      boolean isQuote,
      String parentPostUrl,
      String threadRootUrl,
      String externalPostId,
      boolean hasMedia,
      List<String> mediaUrls) {
    jdbc.sql(
            """
            UPDATE social_mentions SET
              author_id = :a,
              posted_at = :pa,
              content_text = :ct,
              content_lang = :cl,
              likes_count = :lk,
              reposts_count = :rp,
              replies_count = :rl,
              views_count = :vw,
              estimated_reach = :er,
              follower_count_at_post = :fc,
              is_reply = :ir,
              is_quote = :iq,
              parent_post_url = :pu,
              thread_root_url = :tu,
              external_post_id = :ep,
              has_media = :hm,
              media_urls = CAST(:mu AS text[]),
              extraction_status = 'running',
              updated_at = now()
            WHERE id = :id
            """)
        .param("id", id)
        .param("a", authorId)
        .param("pa", postedAt == null ? null : Timestamp.from(postedAt))
        .param("ct", contentText)
        .param("cl", contentLang)
        .param("lk", likes)
        .param("rp", reposts)
        .param("rl", replies)
        .param("vw", views)
        .param("er", estimatedReach)
        .param("fc", followerCountAtPost)
        .param("ir", isReply)
        .param("iq", isQuote)
        .param("pu", parentPostUrl)
        .param("tu", threadRootUrl)
        .param("ep", externalPostId)
        .param("hm", hasMedia)
        .param("mu", mediaUrls == null ? new String[0] : mediaUrls.toArray(new String[0]))
        .update();
  }

  /** Persist the LLM extraction output and mark the row done. */
  public void applyExtracted(
      UUID id,
      String summary,
      String sentiment,
      String sentimentRationale,
      String subjectProminence,
      List<String> topics,
      String mediaSummary,
      String promptVersion,
      String rawJson) {
    jdbc.sql(
            """
            UPDATE social_mentions SET
              summary = :s,
              sentiment = :se,
              sentiment_rationale = :sr,
              subject_prominence = :sp,
              topics = CAST(:tp AS text[]),
              media_summary = COALESCE(:ms, media_summary),
              extraction_prompt_version = :v,
              raw_extracted = CAST(:raw AS jsonb),
              extraction_status = 'done',
              extraction_error = NULL,
              updated_at = now()
            WHERE id = :id
            """)
        .param("id", id)
        .param("s", summary)
        .param("se", sentiment)
        .param("sr", sentimentRationale)
        .param("sp", subjectProminence)
        .param("tp", topics == null ? new String[0] : topics.toArray(new String[0]))
        .param("ms", mediaSummary)
        .param("v", promptVersion)
        .param("raw", rawJson)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            "UPDATE social_mentions SET extraction_status = 'failed', extraction_error = :e,"
                + " updated_at = now() WHERE id = :id")
        .param("id", id)
        .param("e", error)
        .update();
  }

  public void requeue(UUID id) {
    jdbc.sql(
            "UPDATE social_mentions SET extraction_status = 'queued', extraction_error = NULL,"
                + " updated_at = now() WHERE id = :id")
        .param("id", id)
        .update();
  }

  /**
   * Hard delete — no soft-delete column on this table per V007. Caller verifies workspace ownership
   * before invoking.
   */
  public void delete(UUID workspaceId, UUID id) {
    jdbc.sql("DELETE FROM social_mentions WHERE id = :id AND workspace_id = :ws")
        .param("id", id)
        .param("ws", workspaceId)
        .update();
  }

  /** User edit — sticky across re-runs per the {@code is_user_edited} discipline. */
  public Optional<SocialMention> userPatch(
      UUID workspaceId,
      UUID id,
      String summary,
      String sentiment,
      String sentimentRationale,
      String subjectProminence,
      List<String> topics) {
    return jdbc.sql(
            """
            UPDATE social_mentions SET
              summary = COALESCE(CAST(:s AS text), summary),
              sentiment = COALESCE(CAST(:se AS text), sentiment),
              sentiment_rationale = COALESCE(CAST(:sr AS text), sentiment_rationale),
              subject_prominence = COALESCE(CAST(:sp AS text), subject_prominence),
              topics = COALESCE(CAST(:tp AS text[]), topics),
              is_user_edited = true,
              updated_at = now()
            WHERE id = :id AND workspace_id = :ws
            RETURNING *
            """)
        .param("id", id)
        .param("ws", workspaceId)
        .param("s", summary)
        .param("se", sentiment)
        .param("sr", sentimentRationale)
        .param("sp", subjectProminence)
        .param("tp", topics == null ? null : topics.toArray(new String[0]))
        .query(MAPPER)
        .optional();
  }
}
