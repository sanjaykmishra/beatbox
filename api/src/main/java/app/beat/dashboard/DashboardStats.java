package app.beat.dashboard;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 30-day rolling stats for the client dashboard. Computed on-the-fly per docs/16 §Computing the
 * stats row; promoted to the materialized {@code client_metrics_monthly} in Phase 2.
 */
@Component
public class DashboardStats {

  private final JdbcClient jdbc;

  public DashboardStats(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Window(int coverage, int tier1, int sentimentPts, long reach) {}

  /** Stats for the last {@code days} days, ending today. */
  public Window window(UUID clientId, int days, int offsetDays) {
    return jdbc.sql(
            """
            SELECT
              COUNT(*)::int AS coverage,
              SUM(CASE WHEN ci.tier_at_extraction = 1 THEN 1 ELSE 0 END)::int AS tier1,
              COALESCE(ROUND(AVG(
                CASE ci.sentiment
                  WHEN 'positive' THEN 1.0
                  WHEN 'negative' THEN -1.0
                  ELSE 0.0
                END
              ) * 100)::int, 0) AS sentiment_pts,
              COALESCE(SUM(ci.estimated_reach), 0)::bigint AS reach
            FROM coverage_items ci
            JOIN reports r ON r.id = ci.report_id
            WHERE r.client_id = :c
              AND r.deleted_at IS NULL
              AND ci.publish_date IS NOT NULL
              AND ci.publish_date >= (current_date - :end_offset - :days)::date
              AND ci.publish_date <  (current_date - :end_offset)::date
            """)
        .param("c", clientId)
        .param("days", days)
        .param("end_offset", offsetDays)
        .query(
            (rs, n) ->
                new Window(
                    rs.getInt("coverage"),
                    rs.getInt("tier1"),
                    rs.getInt("sentiment_pts"),
                    rs.getLong("reach")))
        .single();
  }
}
