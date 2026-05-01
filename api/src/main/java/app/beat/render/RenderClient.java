package app.beat.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Calls the render service. Returns PDF bytes for /render or HTML text for /preview. */
@Component
public class RenderClient {

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final String baseUrl;
  private final String token;

  public RenderClient(
      @Value("${RENDER_SERVICE_URL:}") String baseUrl,
      @Value("${RENDER_SERVICE_TOKEN:}") String token) {
    this.baseUrl = baseUrl;
    this.token = token;
  }

  public boolean isConfigured() {
    return baseUrl != null && !baseUrl.isBlank();
  }

  public byte[] renderPdf(RenderPayload payload) {
    return post("/render", payload, HttpResponse.BodyHandlers.ofByteArray()).body();
  }

  public String renderHtml(RenderPayload payload) {
    return post("/preview", payload, HttpResponse.BodyHandlers.ofString()).body();
  }

  private <T> HttpResponse<T> post(
      String path, RenderPayload payload, HttpResponse.BodyHandler<T> bodyHandler) {
    if (!isConfigured()) throw new IllegalStateException("RENDER_SERVICE_URL not configured");
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (Exception e) {
      throw new RuntimeException("render: failed to serialize payload", e);
    }
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("X-Render-Token", token == null ? "" : token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<T> res = http.send(req, bodyHandler);
      if (res.statusCode() / 100 != 2) {
        throw new RuntimeException("render service " + res.statusCode());
      }
      return res;
    } catch (java.io.IOException e) {
      // ConnectException / HttpConnectTimeoutException / SocketException may have a null
      // message — fall back to the exception class name so the operator can tell
      // "service unreachable" from "service errored." Most common cause in dev: the render
      // container isn't running, or RENDER_SERVICE_URL points at the wrong host.
      String reason = e.getMessage();
      if (reason == null || reason.isBlank()) reason = e.getClass().getSimpleName();
      throw new RuntimeException(
          "render service unreachable at " + baseUrl + " (" + reason + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("render interrupted", e);
    }
  }
}
