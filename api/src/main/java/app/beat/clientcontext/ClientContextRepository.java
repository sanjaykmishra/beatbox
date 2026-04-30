package app.beat.clientcontext;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientContextRepository {

  private final JdbcClient jdbc;

  public ClientContextRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<ClientContext> MAPPER =
      (ResultSet rs, int n) ->
          new ClientContext(
              rs.getObject("id", UUID.class),
              rs.getObject("client_id", UUID.class),
              rs.getObject("workspace_id", UUID.class),
              rs.getString("key_messages"),
              rs.getString("do_not_pitch"),
              rs.getString("competitive_set"),
              rs.getString("important_dates"),
              rs.getString("style_notes"),
              rs.getString("notes_markdown"),
              rs.getInt("version"),
              rs.getObject("last_edited_by_user_id", UUID.class),
              ts(rs, "created_at"),
              ts(rs, "updated_at"));

  private static Instant ts(ResultSet rs, String c) throws java.sql.SQLException {
    var t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  public Optional<ClientContext> findByClient(UUID clientId) {
    return jdbc.sql("SELECT * FROM client_context WHERE client_id = :c")
        .param("c", clientId)
        .query(MAPPER)
        .optional();
  }

  /** Upsert by client_id; bumps version on update; records last editor. */
  public ClientContext upsert(
      UUID clientId,
      UUID workspaceId,
      UUID lastEditedByUserId,
      String keyMessages,
      String doNotPitch,
      String competitiveSet,
      String importantDates,
      String styleNotes,
      String notesMarkdown) {
    return jdbc.sql(
            """
            INSERT INTO client_context (
              client_id, workspace_id, key_messages, do_not_pitch, competitive_set,
              important_dates, style_notes, notes_markdown, last_edited_by_user_id
            )
            VALUES (:c, :w, :km, :dnp, :cs, :id_, :sn, :nm, :u)
            ON CONFLICT (client_id) DO UPDATE SET
              key_messages = EXCLUDED.key_messages,
              do_not_pitch = EXCLUDED.do_not_pitch,
              competitive_set = EXCLUDED.competitive_set,
              important_dates = EXCLUDED.important_dates,
              style_notes = EXCLUDED.style_notes,
              notes_markdown = EXCLUDED.notes_markdown,
              last_edited_by_user_id = EXCLUDED.last_edited_by_user_id,
              version = client_context.version + 1
            RETURNING *
            """)
        .param("c", clientId)
        .param("w", workspaceId)
        .param("u", lastEditedByUserId)
        .param("km", keyMessages)
        .param("dnp", doNotPitch)
        .param("cs", competitiveSet)
        .param("id_", importantDates)
        .param("sn", styleNotes)
        .param("nm", notesMarkdown)
        .query(MAPPER)
        .single();
  }
}
