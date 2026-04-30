package app.beat.clientcontext;

import java.time.Instant;
import java.util.UUID;

public record ClientContext(
    UUID id,
    UUID clientId,
    UUID workspaceId,
    String keyMessages,
    String doNotPitch,
    String competitiveSet,
    String importantDates,
    String styleNotes,
    String notesMarkdown,
    int version,
    UUID lastEditedByUserId,
    Instant createdAt,
    Instant updatedAt) {

  public boolean isEmpty() {
    return blank(keyMessages)
        && blank(doNotPitch)
        && blank(competitiveSet)
        && blank(importantDates)
        && blank(styleNotes)
        && blank(notesMarkdown);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
