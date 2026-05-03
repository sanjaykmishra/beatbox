package app.beat.extraction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves PNGs persisted by {@link LocalScreenshotStore}. Only registered when local storage is in
 * use; production R2 URLs are served directly by Cloudflare. Path is unguessable (UUID-keyed) and
 * intended for local dev where R2 isn't configured — no auth check, mirrors the unauthenticated R2
 * public URL behavior.
 */
@RestController
@RequestMapping("/v1/screenshots")
public class ScreenshotController {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ScreenshotController.class);

  private final LocalScreenshotStore store;

  public ScreenshotController(@Autowired(required = false) LocalScreenshotStore store) {
    this.store = store;
  }

  @GetMapping("/{workspaceId}/{filename}")
  public ResponseEntity<byte[]> get(
      @PathVariable String workspaceId, @PathVariable String filename) {
    if (store == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    // Validate inputs to prevent path traversal regardless of resolve()'s own check.
    UUID parsedWorkspace;
    try {
      parsedWorkspace = UUID.fromString(workspaceId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (!filename.matches("[a-f0-9-]+\\.png")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    Path file = store.resolve(parsedWorkspace.toString(), filename);
    if (file == null || !Files.isRegularFile(file)) {
      log.warn(
          "screenshot 404: workspace={} filename={} resolved={} exists={}",
          workspaceId,
          filename,
          file == null ? "null" : file.toAbsolutePath(),
          file != null && Files.exists(file));
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      return ResponseEntity.ok()
          .contentType(MediaType.IMAGE_PNG)
          .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
          // CORP — primary unblocker for Chrome's Opaque Response Blocking when the render
          // container's puppeteer (origin: about:blank) loads /v1/screenshots/... cross-origin.
          .header("Cross-Origin-Resource-Policy", "cross-origin")
          // Belt + suspenders: some Chromium versions still ORB-reject when CORP is set but
          // there's no CORS header. * is correct for an unauthenticated public-readable image
          // (the path is UUID-keyed and unguessable; we already accept anonymous reads).
          .header("Access-Control-Allow-Origin", "*")
          // Force the declared Content-Type. Without nosniff, Chrome may sniff the body and
          // disagree with our image/png declaration on partial / weird PNGs, which trips ORB.
          .header("X-Content-Type-Options", "nosniff")
          .body(bytes);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "read failed", e);
    }
  }
}
