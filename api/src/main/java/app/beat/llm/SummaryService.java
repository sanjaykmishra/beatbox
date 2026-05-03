package app.beat.llm;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.report.Report;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates the executive summary at the top of every report.
 *
 * <p>Three paths exist (see docs/18-cost-engineering.md and prompts/executive-summary-v1-*.md):
 *
 * <ul>
 *   <li>{@code v1} — Opus, prompts/executive-summary-v1.md, current Phase 1 production.
 *   <li>{@code v1_1} — Sonnet, prompts/executive-summary-v1-1.md, cost-engineered with prompt
 *       caching of the static instructions and workspace style block.
 *   <li>{@code v1_2} — Sonnet with hardened grounding rules and per-item context, after a real
 *       dogfood surfaced sev-1 hallucinations on v1.1 (fabricated outlets, fabricated counts,
 *       softened zero-coverage periods into PR-speak). See prompts/executive-summary-v1-2.md.
 * </ul>
 *
 * <p>Mode is set by {@code beat.prompts.summary.version}: {@code v1} (default), {@code v1_1},
 * {@code v1_2}, {@code shadow} (v1 user-facing + v1.1 logged), {@code shadow_v12} (v1.1 user-facing
 * + v1.2 logged). Hyperbole is detected locally on whichever path returns; the eval harness has the
 * full rubric.
 *
 * <p><b>Runtime short-circuit.</b> Before any LLM call, if the input has 0 items with
 * subject_prominence in {feature, mention}, the service emits a deterministic "no substantive
 * coverage" summary and skips the call entirely. This is mode-independent — it fires for v1, v1.1,
 * v1.2, and shadow modes alike, because no prompt can be trusted to produce a grounded summary with
 * zero grounded data. See {@link #noSubstantiveCoverageText}.
 */
@Service
public class SummaryService {

  private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

  static final String MODE_V1 = "v1";
  static final String MODE_V1_1 = "v1_1";
  static final String MODE_V1_2 = "v1_2";
  static final String MODE_SHADOW = "shadow";
  static final String MODE_SHADOW_V12 = "shadow_v12";

  /**
   * Sentinel prompt-version recorded on reports whose summary was produced by the runtime
   * short-circuit (no LLM call). Distinguishes "we deliberately wrote a deterministic summary
   * because there was no substantive coverage" from "the v1.x prompt produced this."
   */
  static final String VERSION_NO_COVERAGE_GUARD = "no_substantive_coverage_guard_v1";

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final String modelOverride;
  private final String mode;

  public SummaryService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      @Value("${ANTHROPIC_MODEL_SUMMARY:}") String modelOverride,
      @Value("${beat.prompts.summary.version:v1_2}") String mode) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.modelOverride = modelOverride;
    this.mode = normalizeMode(mode);
    log.info("SummaryService version mode = {}", this.mode);
  }

  static String normalizeMode(String raw) {
    if (raw == null) return MODE_V1_2;
    String m = raw.trim().toLowerCase();
    return switch (m) {
      case MODE_V1, MODE_V1_1, MODE_V1_2, MODE_SHADOW, MODE_SHADOW_V12 -> m;
      default -> MODE_V1_2;
    };
  }

  public boolean isEnabled() {
    return anthropic.isConfigured();
  }

  public record Outcome(String text, String promptVersion, List<String> hyperboleHits) {}

  public Outcome generate(
      Report report,
      String clientName,
      List<CoverageItem> items,
      Map<UUID, Outlet> outlets,
      String styleNotes /* nullable, from client_context */) {
    return generate(report, clientName, /* clientIndustry */ null, items, outlets, styleNotes);
  }

  /** Overload with explicit client industry (used by v1.1 prompt's {{client_industry}} input). */
  public Outcome generate(
      Report report,
      String clientName,
      String clientIndustry,
      List<CoverageItem> items,
      Map<UUID, Outlet> outlets,
      String styleNotes) {
    if (!isEnabled()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");

    SummaryInputs inputs =
        SummaryInputs.build(clientName, report.periodStart(), report.periodEnd(), items, outlets);

    // Runtime guard: if we have items but none feature/mention the client, the LLM has nothing
    // grounded to say. Any "summary" it produces is hallucination dressed up as PR analysis (see
    // the Franklin BBQ dogfood: 3 'passing' items → fabricated outlets + softened "appeared in
    // the right rooms" instead of the truthful "client was not the subject"). Short-circuit with
    // a deterministic message and skip the call entirely.
    if (inputs.hasNoSubstantiveCoverage()) {
      log.info(
          "summary: short-circuit — no substantive coverage (items={}, feature=0, mention=0,"
              + " passing=0, missing={})",
          inputs.count(),
          inputs.missingCount());
      return new Outcome(noSubstantiveCoverageText(inputs), VERSION_NO_COVERAGE_GUARD, List.of());
    }

    return switch (mode) {
      case MODE_V1_1 -> generateV11(inputs, clientIndustry, styleNotes);
      case MODE_V1_2 -> generateV12(inputs, clientIndustry, styleNotes);
      case MODE_SHADOW -> {
        Outcome primary = generateV1(inputs, styleNotes);
        try {
          Outcome shadow = generateV11(inputs, clientIndustry, styleNotes);
          log.info(
              "summary_shadow: v1.1 produced {} chars, hyperbole_hits={}",
              shadow.text().length(),
              shadow.hyperboleHits().size());
        } catch (RuntimeException e) {
          log.warn("summary_shadow: v1.1 path failed (ignored): {}", e.toString());
        }
        yield primary;
      }
      case MODE_SHADOW_V12 -> {
        Outcome primary = generateV11(inputs, clientIndustry, styleNotes);
        try {
          Outcome shadow = generateV12(inputs, clientIndustry, styleNotes);
          log.info(
              "summary_shadow_v12: v1.2 produced {} chars, hyperbole_hits={}",
              shadow.text().length(),
              shadow.hyperboleHits().size());
        } catch (RuntimeException e) {
          log.warn("summary_shadow_v12: v1.2 path failed (ignored): {}", e.toString());
        }
        yield primary;
      }
      default -> generateV1(inputs, styleNotes);
    };
  }

  /**
   * Deterministic summary text used when the report has 0 items with subject_prominence ∈ {feature,
   * mention}. Honest about the situation, gives the agency owner something useful to tell the
   * client (review URL choices, prominence audit) rather than fabricated PR analysis.
   */
  static String noSubstantiveCoverageText(SummaryInputs in) {
    String name =
        in.clientName() == null || in.clientName().isBlank() ? "the client" : in.clientName();
    String period = in.reportPeriodLabel().isBlank() ? "this period" : in.reportPeriodLabel();
    return String.join(
        "\n\n",
        period
            + " did not produce substantive coverage of "
            + name
            + ". "
            + in.count()
            + " "
            + (in.count() == 1 ? "item was" : "items were")
            + " logged, but "
            + name
            + " was not named in any of them — every article was tagged as missing the subject.",
        "Before treating this as a 'quiet month' narrative, audit the URLs that were added to "
            + "the report. The most common cause of an empty subject-prominence pattern is that "
            + "the URLs were on-topic for "
            + name
            + "'s industry but didn't actually mention "
            + name
            + ". If that's the case, refine the source list. If the items genuinely should have "
            + "named the client and didn't, that's the finding to take to the client conversation.",
        "No newsworthy quote or anomaly to flag — there was no substantive coverage to draw one "
            + "from. Looking ahead, the priority is securing at least one "
            + name
            + "-led story for the next reporting period.");
  }

  private Outcome generateV1(SummaryInputs inputs, String styleNotes) {
    PromptTemplate t = prompts.get("executive-summary-v1");
    var vars = inputs.toPromptVars();
    if (styleNotes != null && !styleNotes.isBlank()) {
      vars.put("style_notes", styleNotes);
    }
    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r = anthropic.call(model, t.temperature(), t.maxTokens(), rendered);
    return finish(r.text().trim(), t.version());
  }

  private Outcome generateV11(SummaryInputs inputs, String clientIndustry, String styleNotes) {
    PromptTemplate t = prompts.get("executive-summary-v1-1");

    Map<String, String> vars = new HashMap<>();
    vars.put("client_name", inputs.clientName() == null ? "the client" : inputs.clientName());
    vars.put("client_industry", clientIndustry == null ? "" : clientIndustry);
    vars.put("report_period", inputs.reportPeriodLabel());
    vars.put("coverage_items_summary", inputs.coverageItemsSummary());
    vars.put("client_context", styleNotes == null ? "" : styleNotes);
    // workspace_style_notes is a Phase 2 (per-workspace style guide) input; empty for now so the
    // {{#if}} block in the prompt drops cleanly.
    vars.put("workspace_style_notes", "");

    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r =
        anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), rendered);
    return finish(r.text().trim(), t.version());
  }

  private Outcome generateV12(SummaryInputs inputs, String clientIndustry, String styleNotes) {
    PromptTemplate t = prompts.get("executive-summary-v1-2");

    Map<String, String> vars = new HashMap<>();
    vars.put("client_name", inputs.clientName() == null ? "the client" : inputs.clientName());
    vars.put("client_industry", clientIndustry == null ? "" : clientIndustry);
    vars.put("report_period", inputs.reportPeriodLabel());
    // The richer block — adds prominence breakdown + per-item summaries (which contain the
    // extraction-side "client not mentioned" notes that v1.1 was missing).
    vars.put("coverage_items_summary", inputs.coverageItemsSummaryV12());
    vars.put("client_context", styleNotes == null ? "" : styleNotes);
    vars.put("workspace_style_notes", "");

    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r =
        anthropic.callMaybeCached(model, t.temperature(), t.maxTokens(), rendered);
    return finish(r.text().trim(), t.version());
  }

  private Outcome finish(String text, String version) {
    List<String> hits = HyperboleDetector.findViolations(text);
    if (!hits.isEmpty()) {
      log.warn("summary: hyperbole hits in generated summary: {}", hits);
    }
    return new Outcome(text, version, hits);
  }

  /** Defensive helper for callers that don't pass a date range. */
  public static SummaryInputs buildInputsForPeriod(
      String clientName,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<CoverageItem> items,
      Map<UUID, Outlet> outlets) {
    return SummaryInputs.build(clientName, periodStart, periodEnd, items, outlets);
  }
}
