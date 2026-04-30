package app.beat.report;

import app.beat.auth.SessionTokens;
import app.beat.client.ClientRepository;
import app.beat.coverage.CoverageItemRepository;
import app.beat.render.RenderClient;
import app.beat.render.RenderPayloadBuilder;
import app.beat.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public share view per docs/04 §Public share endpoint. Returns a generic 404 page
 * if the token is missing/expired/revoked — never reveals whether a token ever existed.
 */
@RestController
public class PublicReportController {

  private static final String NOT_FOUND_HTML =
      "<!doctype html><html><head><title>Not available</title>"
          + "<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
          + "<style>body{font-family:-apple-system,sans-serif;color:#111;background:#f9fafb;"
          + "margin:0;padding:48px;text-align:center}h1{font-weight:600;font-size:18px;margin:0 0 8px}"
          + "p{color:#6b7280;margin:0}</style></head>"
          + "<body><h1>This link is no longer available.</h1>"
          + "<p>If you were expecting to see a report, ask the sender for an updated link.</p></body></html>";

  private final ReportRepository reports;
  private final WorkspaceRepository workspaces;
  private final ClientRepository clients;
  private final CoverageItemRepository coverage;
  private final RenderPayloadBuilder payloads;
  private final RenderClient renderClient;

  public PublicReportController(
      ReportRepository reports,
      WorkspaceRepository workspaces,
      ClientRepository clients,
      CoverageItemRepository coverage,
      RenderPayloadBuilder payloads,
      RenderClient renderClient) {
    this.reports = reports;
    this.workspaces = workspaces;
    this.clients = clients;
    this.coverage = coverage;
    this.payloads = payloads;
    this.renderClient = renderClient;
  }

  @GetMapping(value = "/v1/public/reports/{token}", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> view(@PathVariable String token) {
    if (token == null || token.isBlank()) return notFound();
    Report r = reports.findActiveByShareToken(SessionTokens.hash(token)).orElse(null);
    if (r == null) return notFound();
    var ws = workspaces.findById(r.workspaceId()).orElse(null);
    if (ws == null) return notFound();
    var client = clients.findInWorkspace(r.workspaceId(), r.clientId()).orElse(null);
    if (client == null) return notFound();
    var items = coverage.listByReport(r.id());
    var payload = payloads.build(ws, client, r, items);
    String html = renderClient.renderHtml(payload);
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
  }

  private static ResponseEntity<String> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.TEXT_HTML)
        .body(NOT_FOUND_HTML);
  }
}
