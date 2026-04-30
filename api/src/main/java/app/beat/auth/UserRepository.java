package app.beat.auth;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<User> MAPPER =
      (ResultSet rs, int n) ->
          new User(
              rs.getObject("id", UUID.class),
              rs.getString("email"),
              rs.getString("password_hash"),
              rs.getString("name"),
              ts(rs, "email_verified_at"),
              ts(rs, "last_login_at"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  private static Instant ts(ResultSet rs, String col) throws java.sql.SQLException {
    var t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  public User insert(String email, String passwordHash, String name) {
    try {
      return jdbc.sql(
              """
              INSERT INTO users (email, password_hash, name)
              VALUES (:email, :ph, :name)
              RETURNING *
              """)
          .param("email", email)
          .param("ph", passwordHash)
          .param("name", name)
          .query(MAPPER)
          .single();
    } catch (DuplicateKeyException e) {
      throw app.beat.infra.AppException.conflict(
          "/errors/email-in-use", "Email already in use", "An account with this email exists.");
    }
  }

  public Optional<User> findByEmail(String email) {
    return jdbc.sql("SELECT * FROM users WHERE email = :email AND deleted_at IS NULL")
        .param("email", email)
        .query(MAPPER)
        .optional();
  }

  public Optional<User> findById(UUID id) {
    return jdbc.sql("SELECT * FROM users WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public void touchLastLogin(UUID id) {
    jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", id).update();
  }

  /** First owner of the workspace (created earliest). Used by billing emails. */
  public Optional<User> findOwnerOfWorkspace(UUID workspaceId) {
    return jdbc.sql(
            """
            SELECT u.* FROM users u
            JOIN workspace_members wm ON wm.user_id = u.id
            WHERE wm.workspace_id = :w AND wm.role = 'owner' AND u.deleted_at IS NULL
            ORDER BY wm.created_at
            LIMIT 1
            """)
        .param("w", workspaceId)
        .query(MAPPER)
        .optional();
  }
}
