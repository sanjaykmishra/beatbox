package app.beat.client;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientRepository {

  private final JdbcClient jdbc;

  public ClientRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<Client> MAPPER =
      (ResultSet rs, int n) ->
          new Client(
              rs.getObject("id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getString("name"),
              rs.getString("logo_url"),
              rs.getString("primary_color"),
              rs.getString("notes"),
              rs.getString("default_cadence"),
              ts(rs, "setup_dismissed_at"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public List<Client> listByWorkspace(UUID workspaceId, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM clients
            WHERE workspace_id = :w AND deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT :l
            """)
        .param("w", workspaceId)
        .param("l", limit)
        .query(MAPPER)
        .list();
  }

  /** Workspace-agnostic lookup. Use sparingly; tenant-scoped queries are preferred. */
  public Optional<Client> findById(UUID id) {
    return jdbc.sql("SELECT * FROM clients WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public Optional<Client> findInWorkspace(UUID workspaceId, UUID id) {
    return jdbc.sql(
            "SELECT * FROM clients WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL")
        .param("id", id)
        .param("w", workspaceId)
        .query(MAPPER)
        .optional();
  }

  public Client insert(
      UUID workspaceId,
      String name,
      String logoUrl,
      String primaryColor,
      String notes,
      String defaultCadence) {
    return jdbc.sql(
            """
            INSERT INTO clients (workspace_id, name, logo_url, primary_color, notes, default_cadence)
            VALUES (:w, :n, :l, :p, :nt, :c)
            RETURNING *
            """)
        .param("w", workspaceId)
        .param("n", name)
        .param("l", logoUrl)
        .param("p", primaryColor)
        .param("nt", notes)
        .param("c", defaultCadence)
        .query(MAPPER)
        .single();
  }

  public Optional<Client> update(
      UUID workspaceId,
      UUID id,
      String name,
      String logoUrl,
      String primaryColor,
      String notes,
      String defaultCadence) {
    return jdbc.sql(
            """
            UPDATE clients SET
              name = COALESCE(:n, name),
              logo_url = COALESCE(:l, logo_url),
              primary_color = COALESCE(:p, primary_color),
              notes = COALESCE(:nt, notes),
              default_cadence = COALESCE(:c, default_cadence)
            WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL
            RETURNING *
            """)
        .param("id", id)
        .param("w", workspaceId)
        .param("n", name)
        .param("l", logoUrl)
        .param("p", primaryColor)
        .param("nt", notes)
        .param("c", defaultCadence)
        .query(MAPPER)
        .optional();
  }

  public boolean softDelete(UUID workspaceId, UUID id) {
    int rows =
        jdbc.sql(
                "UPDATE clients SET deleted_at = now() WHERE id = :id AND workspace_id = :w AND deleted_at IS NULL")
            .param("id", id)
            .param("w", workspaceId)
            .update();
    return rows > 0;
  }

  public int countActive(UUID workspaceId) {
    Integer n =
        jdbc.sql("SELECT count(*) FROM clients WHERE workspace_id = :w AND deleted_at IS NULL")
            .param("w", workspaceId)
            .query(Integer.class)
            .single();
    return n == null ? 0 : n;
  }

  /** Pin "I'll do it later" on the new-client setup checklist. */
  public void dismissSetup(UUID id) {
    jdbc.sql("UPDATE clients SET setup_dismissed_at = now() WHERE id = :id")
        .param("id", id)
        .update();
  }

  /** All non-deleted clients across the workspace. Used by the scheduled alert refresh. */
  public List<Client> listAllActive() {
    return jdbc.sql("SELECT * FROM clients WHERE deleted_at IS NULL").query(MAPPER).list();
  }
}
