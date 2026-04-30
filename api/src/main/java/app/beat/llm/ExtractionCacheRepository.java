package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExtractionCacheRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper json = new ObjectMapper();

  public ExtractionCacheRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** SHA-256 of the article text as a hex string. */
  public static String hashContent(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  public record CachedExtraction(
      String contentHash, String promptVersion, String model, JsonNode jsonResult) {}

  public Optional<CachedExtraction> find(String contentHash, String promptVersion) {
    return jdbc.sql(
            """
            UPDATE extraction_cache SET hit_count = hit_count + 1, last_used_at = now()
            WHERE content_hash = :h AND prompt_version = :v
            RETURNING content_hash, prompt_version, model, json_result::text AS json_text
            """)
        .param("h", contentHash)
        .param("v", promptVersion)
        .query(
            (rs, i) -> {
              try {
                return new CachedExtraction(
                    rs.getString("content_hash"),
                    rs.getString("prompt_version"),
                    rs.getString("model"),
                    json.readTree(rs.getString("json_text")));
              } catch (Exception e) {
                throw new IllegalStateException("bad cache row", e);
              }
            })
        .optional();
  }

  public void save(
      String contentHash,
      String promptVersion,
      String model,
      String jsonText,
      int inputTokens,
      int outputTokens,
      BigDecimal costUsd) {
    jdbc.sql(
            """
            INSERT INTO extraction_cache (
              content_hash, prompt_version, model, json_result,
              input_tokens, output_tokens, cost_usd
            )
            VALUES (:h, :v, :m, CAST(:j AS jsonb), :i, :o, :c)
            ON CONFLICT (content_hash, prompt_version) DO NOTHING
            """)
        .param("h", contentHash)
        .param("v", promptVersion)
        .param("m", model)
        .param("j", jsonText)
        .param("i", inputTokens)
        .param("o", outputTokens)
        .param("c", costUsd)
        .update();
  }
}
