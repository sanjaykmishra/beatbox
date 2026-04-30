package app.beat.calendar.feed;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Projects {@code owned_posts} rows into the unified calendar feed as {@code type='post'}. Uses the
 * post's scheduled_for as the event time. Skips deleted and archived posts.
 */
@Component
public class PostsFeedSource implements FeedSource {

  private final JdbcClient jdbc;

  public PostsFeedSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> types() {
    return List.of("post");
  }

  @Override
  public List<FeedItem> fetch(UUID workspaceId, UUID clientId, Instant from, Instant to) {
    return jdbc.sql(
            """
            SELECT id, client_id, title, primary_content_text, scheduled_for, status,
                   target_platforms
            FROM owned_posts
            WHERE workspace_id = :w AND deleted_at IS NULL AND status <> 'archived'
              AND scheduled_for IS NOT NULL
              AND (CAST(:c AS uuid) IS NULL OR client_id = :c)
              AND (CAST(:from AS timestamptz) IS NULL OR scheduled_for >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR scheduled_for < :to)
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("from", from == null ? null : Timestamp.from(from))
        .param("to", to == null ? null : Timestamp.from(to))
        .query((ResultSet rs, int n) -> mapRow(rs))
        .list();
  }

  private static FeedItem mapRow(ResultSet rs) throws java.sql.SQLException {
    UUID id = rs.getObject("id", UUID.class);
    UUID clientId = rs.getObject("client_id", UUID.class);
    String title = rs.getString("title");
    String content = rs.getString("primary_content_text");
    Instant scheduled = rs.getTimestamp("scheduled_for").toInstant();
    String status = rs.getString("status");
    String[] platforms = (String[]) rs.getArray("target_platforms").getArray();
    String displayTitle =
        title != null && !title.isBlank()
            ? title
            : content != null && !content.isBlank()
                ? content.length() > 60 ? content.substring(0, 60) + "…" : content
                : "(empty draft)";
    String subtitle =
        platforms.length == 0
            ? status.replace('_', ' ')
            : status.replace('_', ' ') + " · " + String.join(", ", platforms);
    Map<String, Object> payload = new HashMap<>();
    payload.put("status", status);
    payload.put("target_platforms", List.of(platforms));
    return new FeedItem(
        FeedItem.compose("post", id),
        "post",
        id,
        clientId,
        displayTitle,
        subtitle,
        scheduled,
        null,
        false,
        "/calendar?post_id=" + id,
        null,
        payload);
  }
}
