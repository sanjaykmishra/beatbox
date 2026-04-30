package app.beat.author;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthorRepository {

  private final JdbcClient jdbc;

  public AuthorRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Upsert by (name, primary_outlet_id). The unique constraint on the table makes this
   * deterministic per (byline, outlet) pair.
   */
  public Author upsert(String name, UUID primaryOutletId) {
    if (name == null || name.isBlank()) return null;
    Optional<Author> existing = findByNameAndOutlet(name, primaryOutletId);
    if (existing.isPresent()) {
      jdbc.sql("UPDATE authors SET last_seen_byline_at = now() WHERE id = :id")
          .param("id", existing.get().id())
          .update();
      return existing.get();
    }
    try {
      return jdbc.sql(
              """
              INSERT INTO authors (name, primary_outlet_id, last_seen_byline_at)
              VALUES (:n, :o, now())
              RETURNING id, name, primary_outlet_id
              """)
          .param("n", name.trim())
          .param("o", primaryOutletId)
          .query(
              (rs, i) ->
                  new Author(
                      rs.getObject("id", UUID.class),
                      rs.getString("name"),
                      rs.getObject("primary_outlet_id", UUID.class)))
          .single();
    } catch (DuplicateKeyException e) {
      // Concurrent insert raced us — re-read.
      return findByNameAndOutlet(name, primaryOutletId).orElseThrow();
    }
  }

  private Optional<Author> findByNameAndOutlet(String name, UUID outletId) {
    String sql =
        outletId == null
            ? "SELECT id, name, primary_outlet_id FROM authors WHERE name = :n AND primary_outlet_id IS NULL"
            : "SELECT id, name, primary_outlet_id FROM authors WHERE name = :n AND primary_outlet_id = :o";
    var spec = jdbc.sql(sql).param("n", name.trim());
    if (outletId != null) spec = spec.param("o", outletId);
    return spec.query(
            (rs, i) ->
                new Author(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("primary_outlet_id", UUID.class)))
        .optional();
  }
}
