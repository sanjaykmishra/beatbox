package app.beat.extraction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Local-disk screenshot store, used when R2 isn't configured (typical local Docker dev). Writes
 * PNGs under a workspace-scoped subdirectory and returns a relative URL that {@link
 * ScreenshotController} serves.
 *
 * <p>Only registered when {@code beat.screenshots.local-dir} is set. Production paths still go
 * through {@link ScreenshotClient}'s R2 path.
 */
@Component
@ConditionalOnExpression("'${beat.screenshots.local-dir:}'.length() > 0")
public class LocalScreenshotStore {

  private static final Logger log = LoggerFactory.getLogger(LocalScreenshotStore.class);

  private final Path root;

  public LocalScreenshotStore(@Value("${beat.screenshots.local-dir}") String dir) {
    this.root = Path.of(dir);
    try {
      Files.createDirectories(this.root);
      // Probe write access immediately so a misconfigured volume mount surfaces at startup
      // instead of silently failing on the first extraction.
      Path probe = this.root.resolve(".write-probe");
      Files.writeString(probe, "ok");
      Files.deleteIfExists(probe);
      log.info("LocalScreenshotStore: writable at {}", this.root.toAbsolutePath());
    } catch (IOException e) {
      log.error(
          "LocalScreenshotStore: root {} not writable — screenshots will fail to persist: {}",
          this.root.toAbsolutePath(),
          e.toString());
    }
  }

  /**
   * Persist {@code png} bytes for {@code workspaceId}. Returns a relative URL the SPA can load via
   * {@code <img src>} (the Vite dev proxy and prod reverse proxy both forward {@code /v1/*}).
   */
  public String put(UUID workspaceId, byte[] png) throws IOException {
    Path dir = root.resolve(workspaceId.toString());
    Files.createDirectories(dir);
    String filename = UUID.randomUUID() + ".png";
    Path file = dir.resolve(filename);
    Files.write(file, png);
    log.debug("local screenshot written: {} ({} bytes)", file, png.length);
    return "/v1/screenshots/" + workspaceId + "/" + filename;
  }

  /** Resolve a stored file for serving. Returns null if the path escapes the root. */
  public Path resolve(String workspaceId, String filename) {
    Path file = root.resolve(workspaceId).resolve(filename).normalize();
    if (!file.startsWith(root.normalize())) return null;
    return file;
  }
}
