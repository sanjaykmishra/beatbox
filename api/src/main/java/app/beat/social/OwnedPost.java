package app.beat.social;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One row per "post" — the unit of editorial intent. Cross-platform variants live in {@link
 * #platformVariants()} (a JSONB blob) rather than separate rows so a single client approval applies
 * to the whole post. See docs/17-phase-1-5-social.md §17.2.
 */
public record OwnedPost(
    UUID id,
    UUID workspaceId,
    UUID clientId,
    String title,
    String primaryContentText,
    Map<String, PlatformVariant> platformVariants,
    List<String> targetPlatforms,
    Instant scheduledFor,
    String timezone,
    String status,
    String seriesTag,
    UUID draftedByUserId,
    Instant submittedForReviewAt,
    Instant approvedAt,
    Instant postedAt,
    List<UUID> assetIds,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public record PlatformVariant(String content, Integer charCount, Instant editedAt) {}

  /**
   * Allowed transitions out of each state. Off-path moves (e.g. straight from draft to archived)
   * are permitted to keep the API thin; the front-end shows the canonical happy path.
   *
   * <pre>
   *   draft ──→ internal_review ──→ client_review ──→ approved ──→ scheduled ──→ posted
   * </pre>
   */
  public static final Map<String, Set<String>> ALLOWED_TRANSITIONS = transitions();

  private static Map<String, Set<String>> transitions() {
    var m = new LinkedHashMap<String, Set<String>>();
    m.put("draft", Set.of("internal_review", "client_review", "approved", "rejected", "archived"));
    m.put("internal_review", Set.of("client_review", "approved", "rejected", "draft"));
    m.put("client_review", Set.of("approved", "rejected", "draft"));
    m.put("approved", Set.of("scheduled", "posted", "rejected"));
    m.put("scheduled", Set.of("posted", "approved", "rejected"));
    m.put("posted", Set.of("archived"));
    m.put("rejected", Set.of("draft", "archived"));
    m.put("archived", Set.of());
    return Map.copyOf(m);
  }

  private static final TypeReference<Map<String, Map<String, Object>>> VARIANTS_TYPE =
      new TypeReference<>() {};

  /** Deserializer for the platform_variants JSONB column. */
  public static Map<String, PlatformVariant> deserializeVariants(String json, ObjectMapper mapper) {
    if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
    try {
      Map<String, Map<String, Object>> raw = mapper.readValue(json, VARIANTS_TYPE);
      var out = new LinkedHashMap<String, PlatformVariant>();
      for (var e : raw.entrySet()) {
        Map<String, Object> v = e.getValue();
        String content = (String) v.get("content");
        Integer charCount = v.get("char_count") instanceof Number n ? n.intValue() : null;
        String editedAt = (String) v.get("edited_at");
        out.put(
            e.getKey(),
            new PlatformVariant(
                content, charCount, editedAt == null ? null : Instant.parse(editedAt)));
      }
      return out;
    } catch (Exception ex) {
      throw new RuntimeException("Failed to parse platform_variants JSONB", ex);
    }
  }

  /** Serializer back to JSON for INSERT/UPDATE. */
  public static String serializeVariants(
      Map<String, PlatformVariant> variants, ObjectMapper mapper) {
    if (variants == null || variants.isEmpty()) return "{}";
    var out = new LinkedHashMap<String, Map<String, Object>>();
    for (var e : variants.entrySet()) {
      var v = e.getValue();
      var m = new LinkedHashMap<String, Object>();
      m.put("content", v.content());
      if (v.charCount() != null) m.put("char_count", v.charCount());
      if (v.editedAt() != null) m.put("edited_at", v.editedAt().toString());
      out.put(e.getKey(), m);
    }
    try {
      return mapper.writeValueAsString(out);
    } catch (Exception ex) {
      throw new RuntimeException("Failed to serialize platform_variants", ex);
    }
  }
}
