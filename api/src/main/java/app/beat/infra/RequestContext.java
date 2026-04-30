package app.beat.infra;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

public record RequestContext(UUID userId, UUID workspaceId, String role) {

  public static final String ATTRIBUTE = "request.context";

  public static Optional<RequestContext> from(HttpServletRequest req) {
    Object v = req.getAttribute(ATTRIBUTE);
    return v instanceof RequestContext rc ? Optional.of(rc) : Optional.empty();
  }

  public static RequestContext require(HttpServletRequest req) {
    return from(req).orElseThrow(() -> AppException.unauthorized("Authentication required"));
  }
}
