package app.beat.auth;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

  public static final long SESSION_TTL_DAYS = 30;

  private final JdbcClient jdbc;

  public SessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record SessionRow(String tokenHash, UUID userId, Instant expiresAt) {}

  private static final RowMapper<SessionRow> MAPPER =
      (ResultSet rs, int n) ->
          new SessionRow(
              rs.getString("token_hash"),
              rs.getObject("user_id", UUID.class),
              rs.getTimestamp("expires_at").toInstant());

  public void insert(String tokenHash, UUID userId, String userAgent, String ip) {
    Instant expires = Instant.now().plus(SESSION_TTL_DAYS, ChronoUnit.DAYS);
    jdbc.sql(
            """
            INSERT INTO sessions (token_hash, user_id, expires_at, user_agent, ip)
            VALUES (:t, :u, :e, :ua, CAST(:ip AS inet))
            """)
        .param("t", tokenHash)
        .param("u", userId)
        .param("e", java.sql.Timestamp.from(expires))
        .param("ua", userAgent)
        .param("ip", ip)
        .update();
  }

  public Optional<SessionRow> findActive(String tokenHash) {
    return jdbc.sql("SELECT * FROM sessions WHERE token_hash = :t AND expires_at > now()")
        .param("t", tokenHash)
        .query(MAPPER)
        .optional();
  }

  public void touch(String tokenHash) {
    jdbc.sql("UPDATE sessions SET last_used_at = now() WHERE token_hash = :t")
        .param("t", tokenHash)
        .update();
  }

  public void delete(String tokenHash) {
    jdbc.sql("DELETE FROM sessions WHERE token_hash = :t").param("t", tokenHash).update();
  }
}
