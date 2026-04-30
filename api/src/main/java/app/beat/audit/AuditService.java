package app.beat.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

  private final JdbcClient jdbc;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuditService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(
      UUID workspaceId,
      UUID userId,
      String action,
      String targetType,
      UUID targetId,
      Map<String, Object> metadata,
      HttpServletRequest req) {
    String ip = clientIp(req);
    String ua = req == null ? null : req.getHeader("User-Agent");
    String json;
    try {
      json = mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
    } catch (Exception e) {
      json = "{}";
    }
    jdbc.sql(
            """
            INSERT INTO audit_events (workspace_id, user_id, action, target_type, target_id, metadata, ip, user_agent)
            VALUES (:w, :u, :a, :tt, :ti, CAST(:m AS jsonb), CAST(:ip AS inet), :ua)
            """)
        .param("w", workspaceId)
        .param("u", userId)
        .param("a", action)
        .param("tt", targetType)
        .param("ti", targetId)
        .param("m", json)
        .param("ip", ip)
        .param("ua", ua)
        .update();
  }

  private static String clientIp(HttpServletRequest req) {
    if (req == null) return null;
    String forwarded = req.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return req.getRemoteAddr();
  }
}
