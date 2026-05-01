package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wrapper over Anthropic's Message Batches API ({@code /v1/messages/batches}). Per
 * docs/18-cost-engineering.md: user-async features (journalist ranking, pitch drafting) submit
 * their work as a batch for a 50% discount and surface a progress indicator while it processes.
 *
 * <p>Phase 1 / 1.5 don't have any consumer yet — this client and {@link BatchPoller} ship as the
 * abstraction so Phase 3 Part 2 features can drop in. The implementation is real (it speaks the
 * Anthropic batches HTTP API directly) but unused; the integration tests cover only the request
 * shape, not the live API.
 */
@Component
public class AnthropicBatchClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicBatchClient.class);
  private static final String SUBMIT_URL = "https://api.anthropic.com/v1/messages/batches";
  private static final String API_VERSION = "2023-06-01";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final String apiKey;

  public AnthropicBatchClient(@Value("${ANTHROPIC_API_KEY:}") String apiKey) {
    this.apiKey = apiKey;
  }

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  /** A single message-create request inside a batch. */
  public record BatchRequest(
      String customId, String model, double temperature, int maxTokens, String userMessage) {}

  /** Status snapshot returned by polling: counts and a top-level processing status. */
  public record BatchStatus(
      String id, String processingStatus, int succeeded, int errored, int total) {}

  /** Submits a list of requests as one batch. Returns the Anthropic batch id on success. */
  public String submit(List<BatchRequest> requests) {
    if (!isConfigured()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
    if (requests == null || requests.isEmpty()) {
      throw new IllegalArgumentException("batch must contain at least one request");
    }
    ObjectNode body = json.createObjectNode();
    ArrayNode arr = body.putArray("requests");
    for (BatchRequest r : requests) {
      ObjectNode item = arr.addObject();
      item.put("custom_id", r.customId());
      ObjectNode params = item.putObject("params");
      params.put("model", r.model());
      params.put("max_tokens", r.maxTokens());
      params.put("temperature", r.temperature());
      ArrayNode messages = params.putArray("messages");
      ObjectNode msg = messages.addObject();
      msg.put("role", "user");
      msg.put("content", r.userMessage());
    }
    HttpResponse<String> res = post(SUBMIT_URL, body.toString());
    int status = res.statusCode();
    if (status / 100 != 2) {
      throw new RuntimeException("anthropic batches submit " + status + ": " + res.body());
    }
    try {
      JsonNode root = json.readTree(res.body());
      String id = root.path("id").asText();
      log.info("anthropic_batch submitted id={} requests={}", id, requests.size());
      return id;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("anthropic batches submit: bad response JSON", e);
    }
  }

  /** Polls the current status of a batch. */
  public BatchStatus poll(String batchId) {
    if (!isConfigured()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
    HttpResponse<String> res = get(SUBMIT_URL + "/" + batchId);
    int status = res.statusCode();
    if (status / 100 != 2) {
      throw new RuntimeException("anthropic batches poll " + status + ": " + res.body());
    }
    try {
      JsonNode root = json.readTree(res.body());
      JsonNode counts = root.path("request_counts");
      int succ = counts.path("succeeded").asInt();
      int err = counts.path("errored").asInt() + counts.path("expired").asInt();
      int proc = counts.path("processing").asInt();
      return new BatchStatus(
          root.path("id").asText(),
          root.path("processing_status").asText(),
          succ,
          err,
          succ + err + proc);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("anthropic batches poll: bad response JSON", e);
    }
  }

  /**
   * Fetches results for an ended batch. Returns one parsed JSON node per request line. Caller is
   * responsible for matching {@code custom_id} back to the originating row.
   */
  public List<JsonNode> fetchResults(String batchId) {
    if (!isConfigured()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
    HttpResponse<String> res = get(SUBMIT_URL + "/" + batchId + "/results");
    int status = res.statusCode();
    if (status / 100 != 2) {
      throw new RuntimeException("anthropic batches results " + status + ": " + res.body());
    }
    List<JsonNode> out = new ArrayList<>();
    for (String line : res.body().split("\n")) {
      if (line.isBlank()) continue;
      try {
        out.add(json.readTree(line));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        log.warn("anthropic_batch results: skipping malformed line: {}", e.getMessage());
      }
    }
    return out;
  }

  private HttpResponse<String> post(String url, String body) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return send(req);
  }

  private HttpResponse<String> get(String url) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .GET()
            .build();
    return send(req);
  }

  private HttpResponse<String> send(HttpRequest req) {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("anthropic batches: interrupted", e);
    } catch (java.io.IOException e) {
      throw new RuntimeException("anthropic batches: transport: " + e.getMessage(), e);
    }
  }
}
