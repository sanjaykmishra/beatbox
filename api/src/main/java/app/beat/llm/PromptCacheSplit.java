package app.beat.llm;

import java.util.regex.Pattern;

/**
 * Splits a rendered prompt body at its {@code [NOT CACHED — …]} marker into a stable system portion
 * (cached server-side via {@code cache_control: ephemeral}) and a per-call user portion.
 *
 * <p>Prompts authored under docs/18-cost-engineering.md document their cache structure inline:
 *
 * <pre>
 *   [CACHED — instructions_block]
 *   ...stable instructions...
 *   [/CACHED]
 *
 *   [NOT CACHED — per-article]
 *   Source URL: {{url}}
 *   ...
 * </pre>
 *
 * The bracketed annotations are documentation, not API syntax — they're stripped before sending to
 * Anthropic. The split point is the {@code [NOT CACHED]} marker; everything before it goes in the
 * cached system block, everything after in the user message.
 *
 * <p>If no {@code [NOT CACHED]} marker is found, the entire body is returned as the system block
 * with an empty user message — which means the prompt isn't structured for caching, and callers
 * should fall back to the un-cached call shape rather than send an empty user message (Anthropic
 * rejects that).
 */
public final class PromptCacheSplit {

  /**
   * Marker that splits a structured prompt into cached + per-call portions. Matches any suffix in
   * brackets so prompts can document the cache key (e.g. {@code per-article}, {@code per-report}).
   */
  private static final Pattern USER_MARKER =
      Pattern.compile("\\[NOT CACHED[^\\]]*]", Pattern.CASE_INSENSITIVE);

  /** Stripped from both halves before sending — see class doc. */
  private static final Pattern ALL_MARKERS =
      Pattern.compile("\\[(?:/?CACHED|NOT CACHED)[^\\]]*]\\s*", Pattern.CASE_INSENSITIVE);

  private PromptCacheSplit() {}

  public record SystemAndUser(String system, String user) {
    /**
     * True when the rendered prompt was structured for caching (had a {@code [NOT CACHED]} marker).
     * Callers use this to decide whether to send a cached two-block call vs. the un-cached single
     * user-message call.
     */
    public boolean cacheable() {
      return !user.isEmpty();
    }
  }

  public static SystemAndUser split(String rendered) {
    var matcher = USER_MARKER.matcher(rendered);
    if (!matcher.find()) {
      return new SystemAndUser(stripMarkers(rendered), "");
    }
    String systemRaw = rendered.substring(0, matcher.start());
    String userRaw = rendered.substring(matcher.end());
    return new SystemAndUser(stripMarkers(systemRaw), stripMarkers(userRaw));
  }

  private static String stripMarkers(String s) {
    return ALL_MARKERS.matcher(s).replaceAll("").trim();
  }
}
