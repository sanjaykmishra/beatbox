package app.beat.social;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * DAO for {@code social_authors}. Upsert keyed by (platform, handle); subsequent updates refresh
 * follower count and snapshot timestamps.
 */
@Repository
public class SocialAuthorRepository {

  private final JdbcClient jdbc;

  public SocialAuthorRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<SocialAuthor> MAPPER =
      (ResultSet rs, int n) ->
          new SocialAuthor(
              rs.getObject("id", UUID.class),
              rs.getString("platform"),
              rs.getString("handle"),
              rs.getString("display_name"),
              rs.getString("bio"),
              rs.getObject("follower_count") == null ? null : rs.getLong("follower_count"),
              rs.getString("profile_url"),
              rs.getString("avatar_url"),
              rs.getBoolean("is_verified"),
              rs.getObject("linked_author_id", UUID.class),
              toList(rs.getArray("topic_tags")),
              ts(rs, "last_seen_at"),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  static List<String> toList(java.sql.Array a) throws java.sql.SQLException {
    if (a == null) return List.of();
    Object raw = a.getArray();
    if (raw instanceof String[] s) return Arrays.asList(s);
    return List.of();
  }

  /**
   * Upsert by (platform, handle). When the row already exists, refresh display name / bio /
   * follower count / profile + avatar URLs / last_seen_at; otherwise insert. Returns the row.
   */
  public SocialAuthor upsert(
      String platform,
      String handle,
      String displayName,
      String bio,
      Long followerCount,
      String profileUrl,
      String avatarUrl,
      boolean isVerified) {
    return jdbc.sql(
            """
            INSERT INTO social_authors (
              platform, handle, display_name, bio, follower_count, profile_url, avatar_url,
              is_verified, last_seen_at
            ) VALUES (
              :p, :h, :dn, :bio, :fc, :pu, :av, :v, now()
            )
            ON CONFLICT (platform, handle) DO UPDATE SET
              display_name   = COALESCE(EXCLUDED.display_name, social_authors.display_name),
              bio            = COALESCE(EXCLUDED.bio, social_authors.bio),
              follower_count = COALESCE(EXCLUDED.follower_count, social_authors.follower_count),
              profile_url    = COALESCE(EXCLUDED.profile_url, social_authors.profile_url),
              avatar_url     = COALESCE(EXCLUDED.avatar_url, social_authors.avatar_url),
              is_verified    = EXCLUDED.is_verified,
              last_seen_at   = now(),
              updated_at     = now()
            RETURNING *
            """)
        .param("p", platform)
        .param("h", handle)
        .param("dn", displayName)
        .param("bio", bio)
        .param("fc", followerCount)
        .param("pu", profileUrl)
        .param("av", avatarUrl)
        .param("v", isVerified)
        .query(MAPPER)
        .single();
  }

  public Optional<SocialAuthor> findById(UUID id) {
    return jdbc.sql("SELECT * FROM social_authors WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public Optional<SocialAuthor> findByHandle(String platform, String handle) {
    return jdbc.sql("SELECT * FROM social_authors WHERE platform = :p AND handle = :h")
        .param("p", platform)
        .param("h", handle)
        .query(MAPPER)
        .optional();
  }
}
