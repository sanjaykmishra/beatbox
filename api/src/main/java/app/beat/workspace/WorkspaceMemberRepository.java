package app.beat.workspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceMemberRepository {

  private final JdbcClient jdbc;

  public WorkspaceMemberRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Membership(UUID workspaceId, UUID userId, String role) {}

  public void insert(UUID workspaceId, UUID userId, String role) {
    jdbc.sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (:w, :u, :r)")
        .param("w", workspaceId)
        .param("u", userId)
        .param("r", role)
        .update();
  }

  public Optional<Membership> findCurrentForUser(UUID userId) {
    return jdbc.sql(
            """
            SELECT wm.workspace_id, wm.user_id, wm.role
            FROM workspace_members wm
            JOIN workspaces w ON w.id = wm.workspace_id AND w.deleted_at IS NULL
            WHERE wm.user_id = :u
            ORDER BY wm.created_at DESC
            LIMIT 1
            """)
        .param("u", userId)
        .query(
            (rs, n) ->
                new Membership(
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("role")))
        .optional();
  }
}
