package app.beat.llm;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regex check for the forbidden-word list per docs/05-llm-prompts.md and docs/06-evals.md. The exec
 * summary prompt explicitly bans hype words; if any slip through, the eval gate fails.
 */
public final class HyperboleDetector {

  public static final List<String> FORBIDDEN =
      List.of(
          "groundbreaking",
          "revolutionary",
          "tremendous",
          "unprecedented",
          "game-changer",
          "game changer",
          "amazing",
          "incredible",
          "outstanding",
          "phenomenal");

  private static final Pattern PATTERN =
      Pattern.compile(
          "\\b(" + String.join("|", FORBIDDEN.stream().map(Pattern::quote).toList()) + ")\\b",
          Pattern.CASE_INSENSITIVE);

  private HyperboleDetector() {}

  /** Returns the matched forbidden words found in {@code text}, in order. Empty list if clean. */
  public static List<String> findViolations(String text) {
    if (text == null || text.isBlank()) return List.of();
    var matcher = PATTERN.matcher(text);
    var hits = new java.util.ArrayList<String>();
    while (matcher.find()) hits.add(matcher.group(1));
    return hits;
  }
}
