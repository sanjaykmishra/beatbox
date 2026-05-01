package app.beat.render;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Uploads a generated PDF to R2 and returns the public URL. When R2 isn't configured, falls back to
 * {@link LocalPdfStore} (local Docker dev). When neither is configured, returns empty and the
 * worker marks the report failed.
 */
@Component
public class PdfStorage {

  private static final Logger log = LoggerFactory.getLogger(PdfStorage.class);

  private final S3Client s3;
  private final String bucket;
  private final String publicBaseUrl;
  private final LocalPdfStore localStore;

  public PdfStorage(
      @Autowired(required = false) S3Client s3,
      @Value("${beat.r2.bucket:}") String bucket,
      @Value("${beat.r2.public-base-url:}") String publicBaseUrl,
      @Autowired(required = false) LocalPdfStore localStore) {
    this.s3 = s3;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
    this.localStore = localStore;
  }

  public boolean isConfigured() {
    return (s3 != null && bucket != null && !bucket.isBlank()) || localStore != null;
  }

  public Optional<String> upload(UUID workspaceId, UUID reportId, byte[] pdf) {
    boolean r2Configured = s3 != null && bucket != null && !bucket.isBlank();
    if (r2Configured) {
      String key = "reports/" + workspaceId + "/" + reportId + ".pdf";
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/pdf").build(),
          RequestBody.fromBytes(pdf));
      String publicUrl =
          publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
      return Optional.of(publicUrl);
    }
    if (localStore != null) {
      try {
        return Optional.of(localStore.put(workspaceId, reportId, pdf));
      } catch (IOException e) {
        log.warn("local PDF write failed for report {}: {}", reportId, e.toString());
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
