package app.beat.calendar.feed;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Projects {@code reports} rows into the unified calendar feed as {@code type='report_due'}. Event
 * time = period_end at midnight UTC. Skips already-generated reports (status='ready') unless
 * they're inside the window — we still want to show "Dec report (delivered)" alongside the upcoming
 * "Jan report" so the timeline reads continuously.
 */
@Component
public class ReportsFeedSource implements FeedSource {

  private final JdbcClient jdbc;

  public ReportsFeedSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> types() {
    return List.of("report_due");
  }

  @Override
  public List<FeedItem> fetch(UUID workspaceId, UUID clientId, Instant from, Instant to) {
    LocalDate fromDate = from == null ? null : from.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate toDate = to == null ? null : to.atZone(ZoneOffset.UTC).toLocalDate();
    return jdbc.sql(
            """
            SELECT id, client_id, title, period_start, period_end, status, generated_at
            FROM reports
            WHERE workspace_id = :w AND deleted_at IS NULL
              AND (CAST(:c AS uuid) IS NULL OR client_id = :c)
              AND (CAST(:from AS date) IS NULL OR period_end >= :from)
              AND (CAST(:to AS date) IS NULL OR period_end < :to)
            """)
        .param("w", workspaceId)
        .param("c", clientId)
        .param("from", fromDate == null ? null : Date.valueOf(fromDate))
        .param("to", toDate == null ? null : Date.valueOf(toDate))
        .query((ResultSet rs, int n) -> mapRow(rs))
        .list();
  }

  private static FeedItem mapRow(ResultSet rs) throws java.sql.SQLException {
    UUID id = rs.getObject("id", UUID.class);
    UUID clientId = rs.getObject("client_id", UUID.class);
    String title = rs.getString("title");
    LocalDate periodEnd = rs.getDate("period_end").toLocalDate();
    LocalDate periodStart = rs.getDate("period_start").toLocalDate();
    String status = rs.getString("status");
    boolean delivered = "ready".equals(status);
    Instant occursAt = periodEnd.atStartOfDay(ZoneOffset.UTC).toInstant();
    Map<String, Object> payload = new HashMap<>();
    payload.put("status", status);
    payload.put("period_start", periodStart.toString());
    payload.put("period_end", periodEnd.toString());
    String subtitle = delivered ? "Delivered" : status.equals("draft") ? "Period ends" : status;
    return new FeedItem(
        FeedItem.compose("report_due", id),
        "report_due",
        id,
        clientId,
        title,
        subtitle,
        occursAt,
        null,
        true,
        "/reports/" + id,
        null,
        payload);
  }
}
