package app.beat.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Runs the golden set through the live extraction pipeline (or a stub) and writes a markdown report
 * to {@code api/src/test/eval/reports/eval-{timestamp}.md}. The harness is invoked by the eval test
 * classes; reports are uploaded as CI artifacts via the eval workflow.
 */
public final class EvalRunner {

  private static final ObjectMapper JSON = new ObjectMapper();

  private EvalRunner() {}

  public record Item(
      String id,
      String category,
      String url,
      String outletName,
      String subjectName,
      String articleText,
      String contextStyleNotes,
      Map<String, Object> expected) {}

  public record Outcome(
      String id, String category, boolean schemaOk, List<String> failures, String summary) {}

  /** Load all entries from {@code golden-set.yaml} alongside their cached article text. */
  public static List<Item> loadGoldenSet(Path evalDir) throws IOException {
    Path yaml = evalDir.resolve("golden-set.yaml");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> raw =
        (List<Map<String, Object>>) new Yaml().load(Files.readString(yaml, StandardCharsets.UTF_8));
    List<Item> items = new ArrayList<>();
    for (Map<String, Object> e : raw) {
      String id = (String) e.get("id");
      String category = (String) e.getOrDefault("category", "uncategorized");
      String url = (String) e.get("url");
      String outletName = (String) e.getOrDefault("outlet_name", null);
      String subjectName = (String) e.getOrDefault("subject_name", "the subject");
      String sourceFile = (String) e.get("source_file");
      String text = Files.readString(evalDir.resolve(sourceFile), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> expected = (Map<String, Object>) e.getOrDefault("expected", Map.of());
      String contextStyleNotes = (String) e.getOrDefault("context_style_notes", null);
      items.add(
          new Item(id, category, url, outletName, subjectName, text, contextStyleNotes, expected));
    }
    return items;
  }

  /** Resolve the eval directory at runtime — works for ./gradlew test from the api module. */
  public static Path defaultEvalDir() {
    Path here = Path.of("src/test/eval").toAbsolutePath();
    if (Files.exists(here)) return here;
    Path nested = Path.of("api/src/test/eval").toAbsolutePath();
    if (Files.exists(nested)) return nested;
    throw new IllegalStateException("eval dir not found from " + Path.of(".").toAbsolutePath());
  }

  /**
   * Compare a single extraction result (as a parsed Map — keeps this dependency-free for tests)
   * against an expected block. Returns the list of failures; empty means pass.
   */
  public static List<String> compare(Map<String, Object> expected, Map<String, Object> got) {
    List<String> failures = new ArrayList<>();

    String headlineExp = (String) expected.get("headline_contains");
    if (headlineExp != null) {
      Object h = got.get("headline");
      if (h == null || !h.toString().toLowerCase().contains(headlineExp.toLowerCase()))
        failures.add("headline missing '" + headlineExp + "'");
    }
    String sentExp = (String) expected.get("sentiment");
    if (sentExp != null && !sentExp.equals(got.get("sentiment")))
      failures.add("sentiment expected " + sentExp + " got " + got.get("sentiment"));

    String promExp = (String) expected.get("subject_prominence");
    if (promExp != null && !promExp.equals(got.get("subject_prominence")))
      failures.add(
          "subject_prominence expected " + promExp + " got " + got.get("subject_prominence"));

    @SuppressWarnings("unchecked")
    List<String> mustAny = (List<String>) expected.get("topics_must_include_any_of");
    if (mustAny != null && !mustAny.isEmpty()) {
      @SuppressWarnings("unchecked")
      List<String> topics = (List<String>) got.getOrDefault("topics", List.of());
      boolean any =
          topics.stream()
              .anyMatch(
                  t -> mustAny.stream().anyMatch(m -> t.toLowerCase().contains(m.toLowerCase())));
      if (!any) failures.add("topics missing any of " + mustAny + " (got " + topics + ")");
    }

    @SuppressWarnings("unchecked")
    List<String> mustInclude = (List<String>) expected.get("must_include_facts");
    if (mustInclude != null) {
      String summary = String.valueOf(got.get("summary"));
      for (String fact : mustInclude) {
        if (!summary.toLowerCase().contains(fact.toLowerCase()))
          failures.add("summary missing fact '" + fact + "'");
      }
    }

    @SuppressWarnings("unchecked")
    List<String> mustNot = (List<String>) expected.get("must_not_include");
    if (mustNot != null) {
      String summary = String.valueOf(got.get("summary"));
      String quote = String.valueOf(got.getOrDefault("key_quote", ""));
      for (String forbidden : mustNot) {
        if (summary.toLowerCase().contains(forbidden.toLowerCase())
            || quote.toLowerCase().contains(forbidden.toLowerCase()))
          failures.add("output contains forbidden '" + forbidden + "'");
      }
    }

    return failures;
  }

  /** Write a markdown report summarizing per-item pass/fail and aggregate metrics. */
  public static Path writeReport(Path evalDir, List<Outcome> outcomes) throws IOException {
    Path dir = evalDir.resolve("reports");
    Files.createDirectories(dir);
    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path out = dir.resolve("eval-" + stamp + ".md");

    int total = outcomes.size();
    long passed = outcomes.stream().filter(o -> o.failures.isEmpty() && o.schemaOk).count();
    long schemaOk = outcomes.stream().filter(Outcome::schemaOk).count();

    StringBuilder md = new StringBuilder();
    md.append("# Eval report — ").append(stamp).append("\n\n");
    md.append("- items: ").append(total).append("\n");
    md.append("- schema compliance: ").append(schemaOk).append("/").append(total).append("\n");
    md.append("- passed: ").append(passed).append("/").append(total).append("\n\n");

    md.append("| id | category | schema | failures |\n|---|---|---|---|\n");
    for (Outcome o : outcomes) {
      md.append("| ")
          .append(o.id)
          .append(" | ")
          .append(o.category)
          .append(" | ")
          .append(o.schemaOk ? "✓" : "✗")
          .append(" | ")
          .append(o.failures.isEmpty() ? "—" : String.join("; ", o.failures))
          .append(" |\n");
    }

    Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
    return out;
  }

  /** Convenience: parse a Map from a JSON string for the comparison helper. */
  public static Map<String, Object> jsonToMap(String json) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) JSON.readValue(json, LinkedHashMap.class);
    return m;
  }
}
