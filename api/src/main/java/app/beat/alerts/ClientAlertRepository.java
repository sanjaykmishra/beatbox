package app.beat.alerts;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientAlertRepository {

  private final JdbcClient jdbc;

  public ClientAlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<ClientAlert> MAPPER =
      (ResultSet rs, int n) ->
          new ClientAlert(
              rs.getObject("id", UUID.class),
              rs.getObject("client_id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getString("alert_type"),
              rs.getString("severity"),
              rs.getInt("count"),
              rs.getString("badge_label"),
              rs.getString("card_title"),
              rs.getString("card_subtitle"),
              rs.getString("card_action_label"),
              rs.getString("card_action_path"),
              ts(rs, "computed_at"));

  private static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public List<ClientAlert> findByClient(UUID clientId) {
    return jdbc.sql("SELECT * FROM client_alerts WHERE client_id = :c ORDER BY computed_at DESC")
        .param("c", clientId)
        .query(MAPPER)
        .list();
  }

  public List<ClientAlert> findByWorkspace(UUID workspaceId) {
    return jdbc.sql("SELECT * FROM client_alerts WHERE workspace_id = :w")
        .param("w", workspaceId)
        .query(MAPPER)
        .list();
  }

  /** Replace the alert set for a client atomically: delete missing, upsert provided. */
  public void replace(UUID clientId, UUID workspaceId, List<ComputedAlert> alerts) {
    java.util.Set<String> keep = new java.util.HashSet<>();
    for (ComputedAlert a : alerts) keep.add(a.alertType());
    if (keep.isEmpty()) {
      jdbc.sql("DELETE FROM client_alerts WHERE client_id = :c").param("c", clientId).update();
    } else {
      jdbc.sql(
              "DELETE FROM client_alerts WHERE client_id = :c AND alert_type NOT IN (SELECT unnest(CAST(:k AS TEXT[])))")
          .param("c", clientId)
          .param("k", keep.toArray(new String[0]))
          .update();
    }
    for (ComputedAlert a : alerts) {
      jdbc.sql(
              """
              INSERT INTO client_alerts (
                client_id, workspace_id, alert_type, severity, count,
                badge_label, card_title, card_subtitle, card_action_label, card_action_path,
                computed_at
              )
              VALUES (:c, :w, :at, :sev, :cnt, :bl, :ct, :cs, :cal, :cap, now())
              ON CONFLICT (client_id, alert_type) DO UPDATE SET
                severity = EXCLUDED.severity,
                count = EXCLUDED.count,
                badge_label = EXCLUDED.badge_label,
                card_title = EXCLUDED.card_title,
                card_subtitle = EXCLUDED.card_subtitle,
                card_action_label = EXCLUDED.card_action_label,
                card_action_path = EXCLUDED.card_action_path,
                computed_at = now()
              """)
          .param("c", clientId)
          .param("w", workspaceId)
          .param("at", a.alertType())
          .param("sev", a.severity())
          .param("cnt", a.count())
          .param("bl", a.badgeLabel())
          .param("ct", a.cardTitle())
          .param("cs", a.cardSubtitle())
          .param("cal", a.cardActionLabel())
          .param("cap", a.cardActionPath())
          .update();
    }
  }
}
