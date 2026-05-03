package app.beat.workspace;

import java.time.Instant;
import java.util.List;
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

  public record MemberListItem(
      UUID userId,
      String email,
      String name,
      String role,
      Instant memberSince,
      Instant lastLoginAt) {}

  public List<MemberListItem> listForWorkspace(UUID workspaceId) {
    return jdbc.sql(
            """
            SELECT u.id AS user_id, u.email, u.name, wm.role,
                   wm.created_at AS member_since, u.last_login_at
            FROM workspace_members wm
            JOIN users u ON u.id = wm.user_id AND u.deleted_at IS NULL
            WHERE wm.workspace_id = :w
            ORDER BY
              CASE wm.role WHEN 'owner' THEN 0 WHEN 'member' THEN 1 ELSE 2 END,
              u.name
            """)
        .param("w", workspaceId)
        .query(
            (rs, n) ->
                new MemberListItem(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getString("role"),
                    rs.getTimestamp("member_since").toInstant(),
                    rs.getTimestamp("last_login_at") == null
                        ? null
                        : rs.getTimestamp("last_login_at").toInstant()))
        .list();
  }

  /**
   * Returns the email addresses of every member of the workspace (excluding viewers, who shouldn't
   * get review notifications) optionally excluding one user — typically the actor.
   */
  public List<String> notificationEmailsForWorkspace(UUID workspaceId, UUID excludeUserId) {
    return jdbc.sql(
            """
            SELECT u.email
            FROM workspace_members wm
            JOIN users u ON u.id = wm.user_id AND u.deleted_at IS NULL
            WHERE wm.workspace_id = :w
              AND wm.role IN ('owner','member')
              AND (:exclude::uuid IS NULL OR u.id <> :exclude)
            """)
        .param("w", workspaceId)
        .param("exclude", excludeUserId)
        .query((rs, n) -> rs.getString("email"))
        .list();
  }

  /**
   * Number of members eligible to act on workspace content. Viewers are excluded — they can read
   * but can't review or publish, so they don't satisfy the "another team member" half of the 4-eyes
   * publish gate. Used by ReportController.publish to decide whether the creator may self-publish.
   */
  public int countActiveMembers(UUID workspaceId) {
    Integer n =
        jdbc.sql(
                """
                SELECT count(*) FROM workspace_members wm
                JOIN users u ON u.id = wm.user_id AND u.deleted_at IS NULL
                WHERE wm.workspace_id = :w AND wm.role IN ('owner','member')
                """)
            .param("w", workspaceId)
            .query(Integer.class)
            .single();
    return n == null ? 0 : n;
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
