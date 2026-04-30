package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Anthropic's /v1/messages. Uses java.net.http to avoid pulling in the SDK.
 *
 * <p>Per docs/05-llm-prompts.md: structured per-call log, retry on 429/5xx with exponential
 * backoff, no logging of prompt body or article text.
 */
@Component
public class AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
  private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
  private static final String API_VERSION = "2023-06-01";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final String apiKey;

  // Approx Sonnet-class pricing per million tokens. Update when contract changes.
  private static final BigDecimal SONNET_INPUT_PER_MTOK = new BigDecimal("3.00");
  private static final BigDecimal SONNET_OUTPUT_PER_MTOK = new BigDecimal("15.00");
  private static final BigDecimal OPUS_INPUT_PER_MTOK = new BigDecimal("15.00");
  private static final BigDecimal OPUS_OUTPUT_PER_MTOK = new BigDecimal("75.00");

  public AnthropicClient(@Value("${ANTHROPIC_API_KEY:}") String apiKey) {
    this.apiKey = apiKey;
  }

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public record Result(String text, int inputTokens, int outputTokens, BigDecimal costUsd) {}

  /**
   * Single message call with up to 3 retries on 429/5xx (2s, 8s, 30s).
   *
   * @throws IllegalStateException if API key isn't configured
   * @throws RuntimeException on non-retryable HTTP errors or transport failure
   */
  public Result call(String model, double temperature, int maxTokens, String userMessage) {
    if (!isConfigured()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");

    ObjectNode body = json.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", maxTokens);
    body.put("temperature", temperature);
    var messages = body.putArray("messages");
    var msg = messages.addObject();
    msg.put("role", "user");
    msg.put("content", userMessage);

    long started = System.currentTimeMillis();
    int[] backoffMs = {2_000, 8_000, 30_000};
    for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
      try {
        HttpRequest req =
            HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        if (status / 100 == 2) {
          Result r = parseResult(model, res.body());
          long durMs = System.currentTimeMillis() - started;
          logCall(model, r, durMs, "success");
          return r;
        }
        boolean retryable = status == 429 || status / 100 == 5;
        if (retryable && attempt < backoffMs.length) {
          Thread.sleep(backoffMs[attempt]);
          continue;
        }
        throw new RuntimeException(
            "anthropic " + status + (res.body().length() > 200 ? "" : ": " + res.body()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("anthropic interrupted", e);
      } catch (java.io.IOException e) {
        if (attempt < backoffMs.length) {
          try {
            Thread.sleep(backoffMs[attempt]);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("anthropic interrupted", ie);
          }
          continue;
        }
        throw new RuntimeException("anthropic transport: " + e.getMessage(), e);
      }
    }
    throw new RuntimeException("anthropic: exhausted retries");
  }

  private Result parseResult(String model, String responseBody) {
    try {
      JsonNode root = json.readTree(responseBody);
      StringBuilder text = new StringBuilder();
      JsonNode content = root.path("content");
      for (JsonNode block : content) {
        if ("text".equals(block.path("type").asText())) {
          text.append(block.path("text").asText());
        }
      }
      int inTok = root.path("usage").path("input_tokens").asInt();
      int outTok = root.path("usage").path("output_tokens").asInt();
      BigDecimal cost = costFor(model, inTok, outTok);
      return new Result(text.toString(), inTok, outTok, cost);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("anthropic: bad response JSON", e);
    }
  }

  static BigDecimal costFor(String model, int inputTokens, int outputTokens) {
    String m = model == null ? "" : model.toLowerCase();
    BigDecimal inRate;
    BigDecimal outRate;
    if (m.contains("opus")) {
      inRate = OPUS_INPUT_PER_MTOK;
      outRate = OPUS_OUTPUT_PER_MTOK;
    } else {
      inRate = SONNET_INPUT_PER_MTOK;
      outRate = SONNET_OUTPUT_PER_MTOK;
    }
    BigDecimal mtok = new BigDecimal("1000000");
    BigDecimal in =
        inRate.multiply(BigDecimal.valueOf(inputTokens)).divide(mtok, 6, RoundingMode.HALF_UP);
    BigDecimal out =
        outRate.multiply(BigDecimal.valueOf(outputTokens)).divide(mtok, 6, RoundingMode.HALF_UP);
    return in.add(out);
  }

  private static void logCall(String model, Result r, long durMs, String outcome) {
    log.info(
        "anthropic_call model={} input_tokens={} output_tokens={} cost_usd={} duration_ms={} outcome={}",
        model,
        r.inputTokens(),
        r.outputTokens(),
        r.costUsd().toPlainString(),
        durMs,
        outcome);
  }

  /** Truncates a text to roughly {@code maxTokens} tokens, using a 4-chars-per-token heuristic. */
  public static String truncateForTokenBudget(String text, int maxTokens) {
    if (text == null) return "";
    int maxChars = Math.max(0, maxTokens * 4);
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  /** Test helper. */
  public static AnthropicClient withKey(String key) {
    AnthropicClient c = new AnthropicClient(key);
    return c;
  }

  /** Visible for ObjectMapper sharing in eval harness. */
  ObjectMapper json() {
    return json;
  }

  /** Allow tests to peek the raw JSON returned. */
  static Map<String, Object> _testParseResponse(String body) throws Exception {
    return new ObjectMapper().readValue(body, Map.class);
  }
}
