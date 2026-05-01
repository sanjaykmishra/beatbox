package app.beat.llm;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads prompt templates from {@code prompts/*.md}. Each file has YAML frontmatter (between {@code
 * ---} markers) and a body whose first triple-backtick fenced block is the template.
 *
 * <p>Templates are cached by stem (e.g. {@code "extraction-v1"}). Mutating a prompt requires
 * dropping a new file with a new version per docs/05-llm-prompts.md — never edit in place.
 */
@Component
public class PromptLoader {

  private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

  private static final Pattern FRONTMATTER =
      Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$", Pattern.DOTALL);
  private static final Pattern FENCE =
      Pattern.compile("```[a-zA-Z]*\\s*\\R(.*?)\\R```", Pattern.DOTALL);

  private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

  @PostConstruct
  void loadAll() throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();
    Resource[] files;
    try {
      files = resolver.getResources("classpath*:prompts/*.md");
    } catch (IOException e) {
      log.warn(
          "PromptLoader: no prompts on classpath ({}). Falling back to filesystem.", e.toString());
      files = resolver.getResources("file:prompts/*.md");
    }
    for (Resource r : files) {
      String stem = stripExt(r.getFilename());
      try (InputStream in = r.getInputStream()) {
        String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        cache.put(stem, parse(raw));
        log.info("PromptLoader: loaded {} (v={})", stem, cache.get(stem).version());
      }
    }
  }

  public PromptTemplate get(String stem) {
    PromptTemplate t = cache.get(stem);
    if (t == null) throw new IllegalStateException("Prompt not loaded: " + stem);
    return t;
  }

  /** Visible for tests. */
  static PromptTemplate parse(String raw) {
    Matcher m = FRONTMATTER.matcher(raw.replace("\r\n", "\n"));
    if (!m.find()) throw new IllegalArgumentException("Prompt missing frontmatter");
    String yamlBlock = m.group(1);
    String afterFrontmatter = m.group(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) new Yaml().load(yamlBlock);
    if (meta == null) meta = Map.of();

    String version = required(meta, "version");
    String model = required(meta, "model");
    double temperature = ((Number) meta.getOrDefault("temperature", 0.0)).doubleValue();
    int maxTokens = parseMaxTokens(meta.getOrDefault("max_tokens", 1024));

    Matcher fm = FENCE.matcher(afterFrontmatter);
    if (!fm.find()) {
      throw new IllegalArgumentException("Prompt missing fenced template body");
    }
    String body = fm.group(1).trim();
    return new PromptTemplate(version, model, temperature, maxTokens, body);
  }

  /**
   * Parses {@code max_tokens} from frontmatter. Numeric values pass through. Any non-numeric value
   * (e.g. {@code "dynamic"} on pitch-draft, where the cap depends on candidate confidence) maps to
   * {@link PromptTemplate#DYNAMIC_MAX_TOKENS} — the caller is required to supply a value at
   * runtime.
   */
  static int parseMaxTokens(Object raw) {
    if (raw instanceof Number n) return n.intValue();
    return PromptTemplate.DYNAMIC_MAX_TOKENS;
  }

  private static String required(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null || v.toString().isBlank())
      throw new IllegalArgumentException("Prompt frontmatter missing: " + k);
    return v.toString();
  }

  private static String stripExt(String name) {
    if (name == null) return "";
    int i = name.lastIndexOf('.');
    return i < 0 ? name : name.substring(0, i);
  }
}
