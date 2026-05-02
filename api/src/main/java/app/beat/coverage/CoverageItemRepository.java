package app.beat.coverage;

import java.sql.Array;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoverageItemRepository {

  private final JdbcClient jdbc;

  public CoverageItemRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<CoverageItem> MAPPER =
      (ResultSet rs, int n) ->
          new CoverageItem(
              rs.getObject("id", UUID.class),
              rs.getObject("report_id", UUID.class),
              rs.getString("source_url"),
              rs.getObject("outlet_id", UUID.class),
              rs.getObject("author_id", UUID.class),
              rs.getString("headline"),
              rs.getString("subheadline"),
              rs.getObject("publish_date", LocalDate.class),
              rs.getString("lede"),
              rs.getString("summary"),
              rs.getString("key_quote"),
              rs.getString("sentiment"),
              rs.getString("sentiment_rationale"),
              rs.getString("subject_prominence"),
              toStringList(rs.getArray("topics")),
              (Long) rs.getObject("estimated_reach"),
              (Integer) rs.getObject("tier_at_extraction"),
              rs.getString("screenshot_url"),
              rs.getString("extraction_status"),
              rs.getString("extraction_error"),
              rs.getString("extraction_prompt_version"),
              rs.getBoolean("is_user_edited"),
              toStringList(rs.getArray("edited_fields")),
              rs.getInt("sort_order"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  private static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static List<String> toStringList(Array a) throws java.sql.SQLException {
    if (a == null) return List.of();
    Object o = a.getArray();
    if (o instanceof String[] s) return List.of(s);
    return List.of();
  }

  /** Insert a queued coverage_item; ignore if (report_id, source_url) already exists. */
  public Optional<CoverageItem> insertQueued(UUID reportId, String sourceUrl, int sortOrder) {
    try {
      return Optional.of(
          jdbc.sql(
                  """
                  INSERT INTO coverage_items (report_id, source_url, sort_order)
                  VALUES (:r, :u, :s)
                  RETURNING *
                  """)
              .param("r", reportId)
              .param("u", sourceUrl)
              .param("s", sortOrder)
              .query(MAPPER)
              .single());
    } catch (DuplicateKeyException e) {
      return Optional.empty();
    }
  }

  public List<CoverageItem> listByReport(UUID reportId) {
    return jdbc.sql(
            "SELECT * FROM coverage_items WHERE report_id = :r ORDER BY sort_order, created_at")
        .param("r", reportId)
        .query(MAPPER)
        .list();
  }

  public Optional<CoverageItem> findById(UUID id) {
    return jdbc.sql("SELECT * FROM coverage_items WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public Optional<CoverageItem> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql(
            """
            SELECT ci.* FROM coverage_items ci
            JOIN reports r ON r.id = ci.report_id
            WHERE ci.id = :id AND r.workspace_id = :w AND r.deleted_at IS NULL
            """)
        .param("id", id)
        .param("w", workspaceId)
        .query(MAPPER)
        .optional();
  }

  public boolean delete(UUID id) {
    int n = jdbc.sql("DELETE FROM coverage_items WHERE id = :id").param("id", id).update();
    return n > 0;
  }

  /** Apply the fetcher's output to a coverage item. Skips fields the user has edited. */
  public void applyFetched(
      UUID id,
      UUID outletId,
      String headline,
      LocalDate publishDate,
      String lede,
      Integer tierAtExtraction,
      Long estimatedReach,
      String screenshotUrl) {
    jdbc.sql(
            """
            UPDATE coverage_items SET
              outlet_id = COALESCE(outlet_id, :outlet),
              headline = CASE WHEN 'headline' = ANY(edited_fields) THEN headline ELSE COALESCE(:h, headline) END,
              publish_date = CASE WHEN 'publish_date' = ANY(edited_fields) THEN publish_date ELSE COALESCE(:pd, publish_date) END,
              lede = CASE WHEN 'lede' = ANY(edited_fields) THEN lede ELSE COALESCE(:lede, lede) END,
              tier_at_extraction = COALESCE(tier_at_extraction, :tier),
              estimated_reach = COALESCE(estimated_reach, :reach),
              -- Prefer the new capture: re-extract should refresh the thumbnail. If the new
              -- capture failed (Optional.empty → null param), keep the previous URL rather than
              -- nulling the field, so we don't downgrade from "stale screenshot" to "no
              -- screenshot." This is the opposite direction from headline/lede where the LLM is
              -- the source of truth and we don't want a re-extract to overwrite user edits.
              screenshot_url = COALESCE(:ss, screenshot_url),
              extraction_status = 'done',
              extraction_error = NULL
            WHERE id = :id
            """)
        .param("id", id)
        .param("outlet", outletId)
        .param("h", headline)
        .param("pd", publishDate)
        .param("lede", lede)
        .param("tier", tierAtExtraction)
        .param("reach", estimatedReach)
        .param("ss", screenshotUrl)
        .update();
  }

  /** Apply the LLM extraction (sentiment, summary, key_quote, prominence, topics, author). */
  public void applyExtracted(
      UUID id,
      UUID authorId,
      String subheadline,
      String summary,
      String keyQuote,
      String sentiment,
      String sentimentRationale,
      String subjectProminence,
      java.util.List<String> topics,
      String promptVersion,
      String rawJson) {
    jdbc.sql(
            """
            UPDATE coverage_items SET
              author_id = COALESCE(author_id, :author),
              subheadline = CASE WHEN 'subheadline' = ANY(edited_fields) THEN subheadline ELSE COALESCE(:sh, subheadline) END,
              summary = CASE WHEN 'summary' = ANY(edited_fields) THEN summary ELSE COALESCE(:sum, summary) END,
              key_quote = CASE WHEN 'key_quote' = ANY(edited_fields) THEN key_quote ELSE COALESCE(:kq, key_quote) END,
              sentiment = CASE WHEN 'sentiment' = ANY(edited_fields) THEN sentiment ELSE COALESCE(:sent, sentiment) END,
              sentiment_rationale = CASE WHEN 'sentiment_rationale' = ANY(edited_fields) THEN sentiment_rationale ELSE COALESCE(:sr, sentiment_rationale) END,
              subject_prominence = CASE WHEN 'subject_prominence' = ANY(edited_fields) THEN subject_prominence ELSE COALESCE(:prom, subject_prominence) END,
              topics = CASE WHEN 'topics' = ANY(edited_fields) THEN topics ELSE :topics END,
              extraction_prompt_version = :ver,
              raw_extracted = CAST(:raw AS jsonb)
            WHERE id = :id
            """)
        .param("id", id)
        .param("author", authorId)
        .param("sh", subheadline)
        .param("sum", summary)
        .param("kq", keyQuote)
        .param("sent", sentiment)
        .param("sr", sentimentRationale)
        .param("prom", subjectProminence)
        .param("topics", topics == null ? new String[0] : topics.toArray(new String[0]))
        .param("ver", promptVersion)
        .param("raw", rawJson)
        .update();
  }

  public void markFailed(UUID id, String reason) {
    jdbc.sql(
            """
            UPDATE coverage_items SET extraction_status = 'failed', extraction_error = :e
            WHERE id = :id
            """)
        .param("id", id)
        .param("e", reason)
        .update();
  }

  public void resetForRetry(UUID id) {
    jdbc.sql(
            """
            UPDATE coverage_items SET extraction_status = 'queued', extraction_error = NULL
            WHERE id = :id
            """)
        .param("id", id)
        .update();
  }

  /**
   * Apply user-driven edits. The columns that have a non-null value in {@code edits} are written
   * verbatim and added to the {@code edited_fields} array; future re-runs will respect those pins.
   * Returns the updated row.
   */
  public CoverageItem applyUserEdit(UUID id, java.util.Map<String, Object> edits) {
    java.util.List<String> touched = new java.util.ArrayList<>(edits.keySet());
    java.util.List<String> setClauses = new java.util.ArrayList<>();
    for (String f : touched) {
      setClauses.add(f + " = :" + f);
    }
    setClauses.add("is_user_edited = true");
    setClauses.add(
        "edited_fields = (SELECT array_agg(DISTINCT x) FROM unnest(edited_fields || :touched_arr) AS x)");
    String sql =
        "UPDATE coverage_items SET "
            + String.join(", ", setClauses)
            + " WHERE id = :id RETURNING *";

    var spec = jdbc.sql(sql).param("id", id).param("touched_arr", touched.toArray(new String[0]));
    for (var e : edits.entrySet()) {
      spec = spec.param(e.getKey(), e.getValue());
    }
    return spec.query(MAPPER).single();
  }
}
