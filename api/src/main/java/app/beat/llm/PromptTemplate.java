package app.beat.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A versioned prompt loaded from prompts/ — frontmatter + Mustache-style body. */
public record PromptTemplate(
    String version, String model, double temperature, int maxTokens, String body) {

  private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

  /** Inverted-section also accepted ({{^name}}...{{/name}}) for completeness. */
  private static final Pattern IF_BLOCK =
      Pattern.compile(
          "\\{\\{\\s*#if\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}(.*?)\\{\\{\\s*/if\\s*}}",
          Pattern.DOTALL);

  /**
   * Substitute placeholders. Supports:
   *
   * <ul>
   *   <li>{@code {{name}}} — replaced with the value or empty string when missing.
   *   <li>{@code {{#if name}}...{{/if}}} — content kept when {@code vars.get(name)} is non-null and
   *       non-empty; otherwise the entire block (including the surrounding markers) is removed.
   *       Truthiness deliberately ignores the literal string {@code "false"} so callers can hide a
   *       section by passing the empty string.
   * </ul>
   */
  public String render(Map<String, String> vars) {
    String afterIf = renderIfBlocks(body, vars);
    return renderVars(afterIf, vars);
  }

  private static String renderIfBlocks(String input, Map<String, String> vars) {
    Matcher m = IF_BLOCK.matcher(input);
    StringBuilder out = new StringBuilder(input.length());
    while (m.find()) {
      String key = m.group(1);
      String inner = m.group(2);
      String value = vars.get(key);
      boolean truthy = value != null && !value.isEmpty() && !"false".equalsIgnoreCase(value.trim());
      m.appendReplacement(out, Matcher.quoteReplacement(truthy ? inner : ""));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String renderVars(String input, Map<String, String> vars) {
    Matcher m = VAR.matcher(input);
    StringBuilder out = new StringBuilder(input.length() + 64);
    while (m.find()) {
      String key = m.group(1);
      String value = vars.getOrDefault(key, "");
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
