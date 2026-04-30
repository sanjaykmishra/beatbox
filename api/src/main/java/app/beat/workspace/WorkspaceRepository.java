package app.beat.workspace;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {

  public static final int TRIAL_DAYS = 14;

  private final JdbcClient jdbc;

  public WorkspaceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<Workspace> MAPPER =
      (ResultSet rs, int n) ->
          new Workspace(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              rs.getString("slug"),
              rs.getString("logo_url"),
              rs.getString("primary_color"),
              rs.getString("plan"),
              rs.getInt("plan_limit_clients"),
              rs.getInt("plan_limit_reports_monthly"),
              rs.getString("stripe_customer_id"),
              rs.getString("stripe_subscription_id"),
              ts(rs, "trial_ends_at"),
              rs.getObject("default_template_id", UUID.class),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public Workspace insert(String name, String slug) {
    Instant trialEnds = Instant.now().plus(TRIAL_DAYS, ChronoUnit.DAYS);
    try {
      return jdbc.sql(
              """
              INSERT INTO workspaces (name, slug, plan, trial_ends_at)
              VALUES (:n, :s, 'trial', :te)
              RETURNING *
              """)
          .param("n", name)
          .param("s", slug)
          .param("te", java.sql.Timestamp.from(trialEnds))
          .query(MAPPER)
          .single();
    } catch (DuplicateKeyException e) {
      throw app.beat.infra.AppException.conflict(
          "/errors/workspace-slug-taken",
          "Workspace slug taken",
          "Try a different workspace name.");
    }
  }

  public Optional<Workspace> findById(UUID id) {
    return jdbc.sql("SELECT * FROM workspaces WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public boolean slugExists(String slug) {
    Integer count =
        jdbc.sql("SELECT count(*) FROM workspaces WHERE slug = :s")
            .param("s", slug)
            .query(Integer.class)
            .single();
    return count != null && count > 0;
  }

  public Workspace update(
      UUID id, String name, String logoUrl, String primaryColor, UUID defaultTemplateId) {
    return jdbc.sql(
            """
            UPDATE workspaces SET
              name = COALESCE(:n, name),
              logo_url = COALESCE(:l, logo_url),
              primary_color = COALESCE(:p, primary_color),
              default_template_id = COALESCE(:t, default_template_id)
            WHERE id = :id AND deleted_at IS NULL
            RETURNING *
            """)
        .param("id", id)
        .param("n", name)
        .param("l", logoUrl)
        .param("p", primaryColor)
        .param("t", defaultTemplateId)
        .query(MAPPER)
        .single();
  }
}
