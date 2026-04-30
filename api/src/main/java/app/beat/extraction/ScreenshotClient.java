package app.beat.extraction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Calls the render service to capture a PNG screenshot of an article URL, then uploads it to R2 and
 * returns the public URL. Returns empty if either side isn't configured.
 */
@Component
public class ScreenshotClient {

  private static final Logger log = LoggerFactory.getLogger(ScreenshotClient.class);

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String renderUrl;
  private final String renderToken;
  private final S3Client s3;
  private final String bucket;
  private final String publicBaseUrl;

  public ScreenshotClient(
      @Value("${RENDER_SERVICE_URL:}") String renderUrl,
      @Value("${RENDER_SERVICE_TOKEN:}") String renderToken,
      @Autowired(required = false) S3Client s3,
      @Value("${beat.r2.bucket:}") String bucket,
      @Value("${beat.r2.public-base-url:}") String publicBaseUrl) {
    this.renderUrl = renderUrl;
    this.renderToken = renderToken;
    this.s3 = s3;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
  }

  public Optional<String> capture(UUID workspaceId, String pageUrl) {
    if (renderUrl == null || renderUrl.isBlank()) return Optional.empty();
    if (s3 == null || bucket == null || bucket.isBlank()) {
      // No object storage — skip capture rather than persist bytes locally.
      return Optional.empty();
    }
    try {
      String body = "{\"url\":" + jsonString(pageUrl) + "}";
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(renderUrl + "/screenshot"))
              .timeout(Duration.ofSeconds(45))
              .header("Content-Type", "application/json")
              .header("X-Render-Token", renderToken == null ? "" : renderToken)
              .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() / 100 != 2) {
        log.warn("screenshot: render returned {} for {}", res.statusCode(), pageUrl);
        return Optional.empty();
      }
      byte[] png = res.body();
      String key = "screenshots/" + workspaceId + "/" + UUID.randomUUID() + ".png";
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png").build(),
          RequestBody.fromBytes(png));
      String publicUrl =
          publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
      return Optional.of(publicUrl);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("screenshot: capture failed for {}: {}", pageUrl, e.toString());
      return Optional.empty();
    }
  }

  private static String jsonString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
