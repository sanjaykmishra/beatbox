package app.beat.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A versioned prompt loaded from prompts/ — frontmatter + Mustache-style body. */
public record PromptTemplate(
    String version, String model, double temperature, int maxTokens, String body) {

  private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

  /** Substitute {{name}} placeholders. Missing keys become empty strings. */
  public String render(Map<String, String> vars) {
    Matcher m = VAR.matcher(body);
    StringBuilder out = new StringBuilder(body.length() + 64);
    while (m.find()) {
      String key = m.group(1);
      String value = vars.getOrDefault(key, "");
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
