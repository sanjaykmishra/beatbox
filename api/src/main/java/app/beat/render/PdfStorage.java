package app.beat.render;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Uploads a generated PDF to R2 and returns the public URL. Returns empty if R2 isn't configured
 * (local dev without object storage; the worker logs a warning and marks the report failed).
 */
@Component
public class PdfStorage {

  private final S3Client s3;
  private final String bucket;
  private final String publicBaseUrl;

  public PdfStorage(
      @Autowired(required = false) S3Client s3,
      @Value("${beat.r2.bucket:}") String bucket,
      @Value("${beat.r2.public-base-url:}") String publicBaseUrl) {
    this.s3 = s3;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
  }

  public boolean isConfigured() {
    return s3 != null && bucket != null && !bucket.isBlank();
  }

  public Optional<String> upload(UUID workspaceId, UUID reportId, byte[] pdf) {
    if (!isConfigured()) return Optional.empty();
    String key = "reports/" + workspaceId + "/" + reportId + ".pdf";
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/pdf").build(),
        RequestBody.fromBytes(pdf));
    String publicUrl =
        publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
    return Optional.of(publicUrl);
  }
}
