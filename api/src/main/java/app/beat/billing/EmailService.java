package app.beat.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Transactional email via Resend (https://resend.com). No-op when RESEND_API_KEY is empty so local
 * dev doesn't fail. Failures are logged, never propagated — billing webhooks must remain idempotent
 * and resilient.
 */
@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);
  private static final String ENDPOINT = "https://api.resend.com/emails";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper();

  private final String apiKey;
  private final String fromAddress;

  public EmailService(
      @Value("${RESEND_API_KEY:}") String apiKey,
      @Value("${beat.email.from:Beat <noreply@beat.app>}") String fromAddress) {
    this.apiKey = apiKey;
    this.fromAddress = fromAddress;
  }

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public void sendTransactional(String to, String subject, String html) {
    if (!isConfigured()) {
      log.info("email skipped (no key): to={} subject=\"{}\"", to, subject);
      return;
    }
    if (to == null || to.isBlank()) return;
    try {
      ObjectNode body = json.createObjectNode();
      body.put("from", fromAddress);
      body.putArray("to").add(to);
      body.put("subject", subject);
      body.put("html", html);
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(ENDPOINT))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        log.warn("resend: send failed status={} body={}", res.statusCode(), shorten(res.body()));
      } else {
        log.info("resend: sent to={} subject=\"{}\"", to, subject);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("resend: send threw {}", e.toString());
    }
  }

  private static String shorten(String s) {
    if (s == null) return null;
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}
