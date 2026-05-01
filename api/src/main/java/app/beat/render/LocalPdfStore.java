package app.beat.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Local-disk PDF store, used when R2 isn't configured (typical local Docker dev). Writes to a
 * workspace-scoped subdirectory and returns a {@code local:} sentinel URL that {@link
 * app.beat.report.ReportController#downloadPdf} streams directly instead of 302-redirecting.
 *
 * <p>Only registered when {@code beat.pdfs.local-dir} is set. Production paths still go through
 * {@link PdfStorage}'s R2 path.
 */
@Component
@ConditionalOnExpression("'${beat.pdfs.local-dir:}'.length() > 0")
public class LocalPdfStore {

  private static final Logger log = LoggerFactory.getLogger(LocalPdfStore.class);

  /** Sentinel prefix written to {@code reports.pdf_url} so the controller can route correctly. */
  public static final String URL_PREFIX = "local:";

  private final Path root;

  public LocalPdfStore(@Value("${beat.pdfs.local-dir}") String dir) {
    this.root = Path.of(dir);
  }

  public String put(UUID workspaceId, UUID reportId, byte[] pdf) throws IOException {
    Path dir = root.resolve(workspaceId.toString());
    Files.createDirectories(dir);
    Path file = dir.resolve(reportId + ".pdf");
    Files.write(file, pdf);
    log.debug("local PDF written: {} ({} bytes)", file, pdf.length);
    return URL_PREFIX + workspaceId + "/" + reportId + ".pdf";
  }

  /** Read bytes for a stored PDF given the {@code local:} URL written to {@code pdf_url}. */
  public Optional<byte[]> read(String url) {
    if (url == null || !url.startsWith(URL_PREFIX)) return Optional.empty();
    String key = url.substring(URL_PREFIX.length());
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root.normalize())) return Optional.empty();
    if (!Files.isRegularFile(file)) return Optional.empty();
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException e) {
      log.warn("local PDF read failed for {}: {}", url, e.toString());
      return Optional.empty();
    }
  }
}
