package app.beat.outlet;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutletRepository {

  private final JdbcClient jdbc;

  public OutletRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<Outlet> MAPPER =
      (ResultSet rs, int n) ->
          new Outlet(
              rs.getObject("id", UUID.class),
              rs.getString("domain"),
              rs.getString("name"),
              rs.getInt("tier"),
              rs.getString("tier_source"),
              (Integer) rs.getObject("domain_authority"),
              (Long) rs.getObject("estimated_monthly_visits"),
              rs.getString("country"),
              rs.getString("language"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  private static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public java.util.Optional<Outlet> findById(UUID id) {
    return jdbc.sql("SELECT * FROM outlets WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /** Persist an LLM-classified tier (sets tier_source='llm'). */
  public void setLlmTier(UUID id, int tier) {
    jdbc.sql("UPDATE outlets SET tier = :t, tier_source = 'llm' WHERE id = :id")
        .param("id", id)
        .param("t", tier)
        .update();
  }

  /** Upsert by domain. Tier defaults to 3 with tier_source='default' on insert. */
  public Outlet upsertByDomain(String domain, String name) {
    return jdbc.sql(
            """
            INSERT INTO outlets (domain, name)
            VALUES (:d, :n)
            ON CONFLICT (domain) DO UPDATE SET
              name = COALESCE(NULLIF(EXCLUDED.name, ''), outlets.name)
            RETURNING *
            """)
        .param("d", domain)
        .param("n", name == null ? Domains.outletNameFromDomain(domain) : name)
        .query(MAPPER)
        .single();
  }
}
