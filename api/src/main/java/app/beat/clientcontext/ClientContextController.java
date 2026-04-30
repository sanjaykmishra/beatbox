package app.beat.clientcontext;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-client "second brain". Stores key messages, style notes, etc. Surfaced into the LLM
 * extraction prompt at run time (see prompts/extraction-v1-1.md). Source: docs/15-additions.md
 * §15.1.
 */
@RestController
public class ClientContextController {

  private final ClientContextRepository contexts;
  private final ClientRepository clients;
  private final ActivityRecorder activity;

  public ClientContextController(
      ClientContextRepository contexts, ClientRepository clients, ActivityRecorder activity) {
    this.contexts = contexts;
    this.clients = clients;
    this.activity = activity;
  }

  public record ContextDto(
      UUID id,
      UUID client_id,
      String key_messages,
      String do_not_pitch,
      String competitive_set,
      String important_dates,
      String style_notes,
      String notes_markdown,
      int version,
      UUID last_edited_by_user_id,
      Instant updated_at) {
    static ContextDto from(ClientContext c) {
      return new ContextDto(
          c.id(),
          c.clientId(),
          c.keyMessages(),
          c.doNotPitch(),
          c.competitiveSet(),
          c.importantDates(),
          c.styleNotes(),
          c.notesMarkdown(),
          c.version(),
          c.lastEditedByUserId(),
          c.updatedAt());
    }
  }

  public record UpsertContextRequest(
      @Size(max = 8000) String key_messages,
      @Size(max = 8000) String do_not_pitch,
      @Size(max = 8000) String competitive_set,
      @Size(max = 8000) String important_dates,
      @Size(max = 8000) String style_notes,
      @Size(max = 50000) String notes_markdown) {}

  @GetMapping("/v1/clients/{clientId}/context")
  public ContextDto get(@PathVariable UUID clientId, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var client =
        clients
            .findInWorkspace(ctx.workspaceId(), clientId)
            .orElseThrow(() -> AppException.notFound("Client"));
    return contexts
        .findByClient(client.id())
        .map(ContextDto::from)
        .orElseThrow(() -> AppException.notFound("Client context"));
  }

  @PutMapping("/v1/clients/{clientId}/context")
  public ContextDto upsert(
      @PathVariable UUID clientId,
      @Valid @RequestBody UpsertContextRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var client =
        clients
            .findInWorkspace(ctx.workspaceId(), clientId)
            .orElseThrow(() -> AppException.notFound("Client"));
    var saved =
        contexts.upsert(
            client.id(),
            ctx.workspaceId(),
            ctx.userId(),
            body.key_messages(),
            body.do_not_pitch(),
            body.competitive_set(),
            body.important_dates(),
            body.style_notes(),
            body.notes_markdown());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.CLIENT_CONTEXT_UPDATED,
        "client",
        client.id(),
        Map.of("version", saved.version()));
    return ContextDto.from(saved);
  }
}
