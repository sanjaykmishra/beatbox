package app.beat.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-side helpers for the dashboard timeline + full activity page. Writes go through {@link
 * ActivityRecorder}.
 */
@Repository
public class ActivityEventRepository {

  public record ActivityEventRow(
      UUID id,
      UUID workspaceId,
      UUID userId,
      String actorType,
      String kind,
      String targetType,
      UUID targetId,
      Integer durationMs,
      Map<String, Object> metadata,
      Instant occurredAt) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json = new ObjectMapper();

  public ActivityEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<ActivityEventRow> mapper =
      (ResultSet rs, int n) -> {
        Map<String, Object> meta;
        try {
          String raw = rs.getString("metadata");
          @SuppressWarnings("unchecked")
          Map<String, Object> m =
              raw == null ? Map.of() : (Map<String, Object>) json.readValue(raw, Map.class);
          meta = m;
        } catch (Exception e) {
          meta = Map.of();
        }
        return new ActivityEventRow(
            rs.getObject("id", UUID.class),
            rs.getObject("workspace_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("actor_type"),
            rs.getString("kind"),
            rs.getString("target_type"),
            rs.getObject("target_id", UUID.class),
            (Integer) rs.getObject("duration_ms"),
            meta,
            rs.getTimestamp("occurred_at").toInstant());
      };

  /**
   * Activity touching this client — directly (target=client) or transitively via a report or
   * coverage_item belonging to a report owned by the client.
   */
  public List<ActivityEventRow> findByClient(UUID clientId, int limit) {
    return jdbc.sql(
            """
            SELECT id, workspace_id, user_id, actor_type, kind, target_type, target_id,
                   duration_ms, metadata::text AS metadata, occurred_at
            FROM activity_events
            WHERE
              (target_type = 'client' AND target_id = :c)
              OR (target_type = 'report' AND target_id IN (
                    SELECT id FROM reports WHERE client_id = :c))
              OR (target_type = 'coverage_item' AND target_id IN (
                    SELECT id FROM coverage_items WHERE report_id IN (
                      SELECT id FROM reports WHERE client_id = :c)))
            ORDER BY occurred_at DESC
            LIMIT :l
            """)
        .param("c", clientId)
        .param("l", limit)
        .query(mapper)
        .list();
  }
}
