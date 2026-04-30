package app.beat.calendar;

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
public class CalendarEventRepository {

  private final JdbcClient jdbc;

  public CalendarEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<CalendarEvent> MAPPER =
      (ResultSet rs, int n) ->
          new CalendarEvent(
              rs.getObject("id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getObject("client_id", UUID.class),
              rs.getString("event_type"),
              rs.getString("title"),
              rs.getString("description"),
              ts(rs, "occurs_at"),
              ts(rs, "ends_at"),
              rs.getBoolean("all_day"),
              rs.getString("url"),
              rs.getString("color"),
              rs.getObject("created_by_user_id", UUID.class),
              ts(rs, "created_at"),
              ts(rs, "updated_at"),
              ts(rs, "deleted_at"));

  private static Instant ts(ResultSet rs, String col) throws java.sql.SQLException {
    var t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  /** Postgres JDBC can't infer types for Instant. */
  private static Timestamp tsParam(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  public CalendarEvent insert(
      UUID workspaceId,
      UUID clientId,
      String eventType,
      String title,
      String description,
      Instant occursAt,
      Instant endsAt,
      boolean allDay,
      String url,
      String color,
      UUID createdByUserId) {
    return jdbc.sql(
            """
            INSERT INTO calendar_events (
              workspace_id, client_id, event_type, title, description,
              occurs_at, ends_at, all_day, url, color, created_by_user_id
            )
            VALUES (:w, :c, :t, :title, :desc, :occ, :ends, :allDay, :url, :color, :u)
            RETURNING *
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("t", eventType)
        .param("title", title)
        .param("desc", description)
        .param("occ", tsParam(occursAt))
        .param("ends", tsParam(endsAt))
        .param("allDay", allDay)
        .param("url", url)
        .param("color", color)
        .param("u", createdByUserId)
        .query(MAPPER)
        .single();
  }

  public Optional<CalendarEvent> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql(
            "SELECT * FROM calendar_events "
                + "WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL")
        .param("id", id)
        .param("w", workspaceId)
        .query(MAPPER)
        .optional();
  }

  /**
   * List events overlapping the {@code [from, to)} window. An event overlaps if its occurs_at &lt;
   * to AND COALESCE(ends_at, occurs_at) &gt;= from. Filtered by event type list when non-empty; by
   * client_id when non-null.
   */
  public List<CalendarEvent> listInWindow(
      UUID workspaceId, UUID clientId, List<String> types, Instant from, Instant to) {
    String[] typesArr = types == null || types.isEmpty() ? null : types.toArray(new String[0]);
    return jdbc.sql(
            """
            SELECT * FROM calendar_events
            WHERE workspace_id = :w AND deleted_at IS NULL
              AND (:c::uuid IS NULL OR client_id = :c)
              AND (CAST(:types AS text[]) IS NULL OR event_type = ANY(CAST(:types AS text[])))
              AND (CAST(:from AS timestamptz) IS NULL OR COALESCE(ends_at, occurs_at) >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR occurs_at < :to)
            ORDER BY occurs_at ASC
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("types", typesArr)
        .param("from", tsParam(from))
        .param("to", tsParam(to))
        .query(MAPPER)
        .list();
  }

  public CalendarEvent update(
      UUID id,
      UUID clientId,
      String eventType,
      String title,
      String description,
      Instant occursAt,
      Instant endsAt,
      Boolean allDay,
      String url,
      String color) {
    return jdbc.sql(
            """
            UPDATE calendar_events SET
              client_id = COALESCE(CAST(:c AS uuid), client_id),
              event_type = COALESCE(CAST(:t AS text), event_type),
              title = COALESCE(CAST(:title AS text), title),
              description = COALESCE(CAST(:desc AS text), description),
              occurs_at = COALESCE(CAST(:occ AS timestamptz), occurs_at),
              ends_at = COALESCE(CAST(:ends AS timestamptz), ends_at),
              all_day = COALESCE(CAST(:allDay AS boolean), all_day),
              url = COALESCE(CAST(:url AS text), url),
              color = COALESCE(CAST(:color AS text), color),
              updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            RETURNING *
            """)
        .param("id", id)
        .param("c", clientId)
        .param("t", eventType)
        .param("title", title)
        .param("desc", description)
        .param("occ", tsParam(occursAt))
        .param("ends", tsParam(endsAt))
        .param("allDay", allDay)
        .param("url", url)
        .param("color", color)
        .query(MAPPER)
        .single();
  }

  public void softDelete(UUID id) {
    jdbc.sql(
            "UPDATE calendar_events SET deleted_at = now(), updated_at = now() "
                + "WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .update();
  }
}
