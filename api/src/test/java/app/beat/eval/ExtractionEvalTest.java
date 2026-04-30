package app.beat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import app.beat.llm.AnthropicClient;
import app.beat.llm.ExtractionSchema;
import app.beat.llm.PromptLoader;
import app.beat.llm.PromptTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Eval harness driver. By default runs in {@code dry-run} mode: the harness loads the golden set +
 * the prompt and asserts schema/comparison logic on a synthetic stub response (proves the rig is
 * intact even without an API key). When {@code ANTHROPIC_API_KEY} is set in CI or locally,
 * additionally runs the LLM end-to-end and applies the hard gates.
 */
public class ExtractionEvalTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void dryRun_validatesHarnessAndPromptShape() throws Exception {
    Path evalDir = EvalRunner.defaultEvalDir();
    var items = EvalRunner.loadGoldenSet(evalDir);
    assertThat(items).hasSizeGreaterThanOrEqualTo(5);

    // Prompt template must load and contain all the placeholders we substitute.
    var loader = new PromptLoader();
    java.lang.reflect.Method m = PromptLoader.class.getDeclaredMethod("loadAll");
    m.setAccessible(true);
    m.invoke(loader);
    PromptTemplate tmpl = loader.get("extraction-v1");
    assertThat(tmpl.body())
        .contains("{{url}}", "{{outlet_name}}", "{{subject_name}}", "{{article_text}}");
    PromptTemplate tmpl11 = loader.get("extraction-v1-1");
    assertThat(tmpl11.body()).contains("{{client_context}}");

    // Validate the comparison logic against a synthetic-but-shape-correct response.
    List<EvalRunner.Outcome> outcomes = new ArrayList<>();
    for (var it : items) {
      String synthetic = stubResponse(it);
      boolean schemaOk;
      try {
        ExtractionSchema.parseStrict(synthetic);
        schemaOk = true;
      } catch (Exception e) {
        schemaOk = false;
      }
      Map<String, Object> got = EvalRunner.jsonToMap(synthetic);
      List<String> failures = EvalRunner.compare(it.expected(), got);
      outcomes.add(new EvalRunner.Outcome(it.id(), it.category(), schemaOk, failures, "dry-run"));
    }
    Path report = EvalRunner.writeReport(evalDir, outcomes);
    assertThat(report).exists();
    assertThat(outcomes).allMatch(EvalRunner.Outcome::schemaOk);
  }

  /**
   * Live LLM run. Disabled unless ANTHROPIC_API_KEY is set, so CI for unrelated PRs doesn't burn
   * tokens. The eval.yml workflow exports the key for prompt/LLM-touching PRs.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
  @EnabledIfSystemProperty(named = "beat.eval.live", matches = "true")
  void live_runsExtractionAgainstGoldenSet_andEnforcesHardGates() throws Exception {
    Path evalDir = EvalRunner.defaultEvalDir();
    var items = EvalRunner.loadGoldenSet(evalDir);
    var loader = new PromptLoader();
    java.lang.reflect.Method m = PromptLoader.class.getDeclaredMethod("loadAll");
    m.setAccessible(true);
    m.invoke(loader);
    PromptTemplate tmpl = loader.get("extraction-v1");
    AnthropicClient client = new AnthropicClient(System.getenv("ANTHROPIC_API_KEY"));

    int sentimentMatches = 0;
    int schemaOk = 0;
    int hallucinations = 0;
    List<EvalRunner.Outcome> outcomes = new ArrayList<>();
    PromptTemplate tmplWithContext = loader.get("extraction-v1-1");
    for (var it : items) {
      boolean hasContext = it.contextStyleNotes() != null && !it.contextStyleNotes().isBlank();
      PromptTemplate t = hasContext ? tmplWithContext : tmpl;
      Map<String, String> vars = new java.util.HashMap<>();
      vars.put("url", String.valueOf(it.url()));
      vars.put("outlet_name", String.valueOf(it.outletName()));
      vars.put("subject_name", String.valueOf(it.subjectName()));
      vars.put("article_text", it.articleText());
      if (hasContext) {
        vars.put(
            "client_context",
            "Relevant context about "
                + it.subjectName()
                + ":\n- Style notes: "
                + it.contextStyleNotes());
      }
      String rendered = t.render(vars);
      AnthropicClient.Result r = client.call(t.model(), t.temperature(), t.maxTokens(), rendered);
      boolean ok;
      Map<String, Object> got = new LinkedHashMap<>();
      List<String> failures = new ArrayList<>();
      try {
        var parsed = ExtractionSchema.parseStrict(r.text());
        got = JSON.convertValue(parsed, LinkedHashMap.class);
        ok = true;
        schemaOk++;
        if (parsed.sentiment().equals(it.expected().get("sentiment"))) sentimentMatches++;
      } catch (Exception e) {
        ok = false;
        failures.add("schema: " + e.getMessage());
      }
      if (ok) {
        failures = EvalRunner.compare(it.expected(), got);
        for (String f : failures) if (f.startsWith("output contains forbidden")) hallucinations++;
      }
      outcomes.add(new EvalRunner.Outcome(it.id(), it.category(), ok, failures, "live"));
    }
    Path report = EvalRunner.writeReport(evalDir, outcomes);
    System.out.println("eval report: " + report);

    int total = items.size();
    // Hard gates per docs/06-evals.md.
    assertThat(schemaOk).as("schema compliance must be 100%").isEqualTo(total);
    assertThat(hallucinations).as("hallucination rate must be 0").isZero();
    int sentimentExpected = (int) Math.ceil(total * 0.9);
    assertThat(sentimentMatches)
        .as("sentiment accuracy must be >= 90%")
        .isGreaterThanOrEqualTo(sentimentExpected);
  }

  /** Synthetic shape-correct response keyed off the golden expected block. */
  private static String stubResponse(EvalRunner.Item it) {
    String headline = "Acme Corp announces something";
    String sentiment = (String) it.expected().getOrDefault("sentiment", "neutral");
    String prominence = (String) it.expected().getOrDefault("subject_prominence", "feature");
    @SuppressWarnings("unchecked")
    List<String> wantTopics =
        (List<String>) it.expected().getOrDefault("topics_must_include_any_of", List.of("news"));
    @SuppressWarnings("unchecked")
    List<String> wantFacts =
        (List<String>) it.expected().getOrDefault("must_include_facts", List.of());
    String summary = "Synthetic summary mentioning " + String.join(", ", wantFacts) + ".";
    return "{"
        + "\"headline\": \""
        + headline
        + "\","
        + "\"subheadline\": null,"
        + "\"author\": null,"
        + "\"publish_date\": null,"
        + "\"lede\": \"This is a stub lede long enough to satisfy length checks.\","
        + "\"summary\": \""
        + summary
        + "\","
        + "\"key_quote\": null,"
        + "\"sentiment\": \""
        + sentiment
        + "\","
        + "\"sentiment_rationale\": \"stub rationale\","
        + "\"subject_prominence\": \""
        + prominence
        + "\","
        + "\"topics\": [\""
        + (wantTopics.isEmpty() ? "news" : wantTopics.get(0))
        + "\"]"
        + "}";
  }
}
