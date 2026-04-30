package app.beat.upload;

import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@RestController
@RequestMapping("/v1/uploads")
public class UploadController {

  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/png", "image/jpeg", "image/svg+xml", "image/webp");

  private final S3Presigner presigner;
  private final String bucket;
  private final String publicBaseUrl;

  public UploadController(
      @Autowired(required = false) S3Presigner presigner,
      @Value("${beat.r2.bucket:}") String bucket,
      @Value("${beat.r2.public-base-url:}") String publicBaseUrl) {
    this.presigner = presigner;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
  }

  public record PresignRequest(
      @NotBlank @Pattern(regexp = "logo|client_logo") String purpose,
      @NotBlank String content_type) {}

  public record PresignResponse(String url, String key, String public_url, long expires_in) {}

  @PostMapping("/presign")
  public PresignResponse presign(@Valid @RequestBody PresignRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (presigner == null || bucket.isBlank() || publicBaseUrl.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Object storage not configured");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(body.content_type())) {
      throw AppException.badRequest(
          "/errors/invalid-content-type",
          "Unsupported content type",
          "Allowed: " + String.join(", ", ALLOWED_CONTENT_TYPES));
    }
    String ext = extensionFor(body.content_type());
    String key =
        "uploads/" + ctx.workspaceId() + "/" + body.purpose() + "/" + UUID.randomUUID() + ext;
    var result =
        ObjectStorage.presignPut(
            presigner, bucket, publicBaseUrl, key, body.content_type(), Duration.ofMinutes(5));
    return new PresignResponse(result.url(), result.key(), result.public_url(), 300);
  }

  private static String extensionFor(String contentType) {
    return switch (contentType) {
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      case "image/svg+xml" -> ".svg";
      case "image/webp" -> ".webp";
      default -> "";
    };
  }
}
