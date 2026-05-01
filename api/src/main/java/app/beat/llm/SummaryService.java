package app.beat.llm;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.report.Report;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates the executive summary at the top of every report.
 *
 * <p>Two paths exist (see docs/18-cost-engineering.md and prompts/executive-summary-v1-1.md):
 *
 * <ul>
 *   <li>{@code v1} — Opus, prompts/executive-summary-v1.md, current Phase 1 production.
 *   <li>{@code v1_1} — Sonnet, prompts/executive-summary-v1-1.md, cost-engineered with prompt
 *       caching of the static instructions and workspace style block.
 * </ul>
 *
 * <p>Mode is set by {@code beat.prompts.summary.version} ({@code v1} default | {@code v1_1} |
 * {@code shadow}). Shadow mode runs both: returns v1 output to the user, logs v1.1 for telemetry.
 * Hyperbole is detected locally on whichever path returns; the eval harness has the full rubric.
 */
@Service
public class SummaryService {

  private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

  static final String MODE_V1 = "v1";
  static final String MODE_V1_1 = "v1_1";
  static final String MODE_SHADOW = "shadow";

  /**
   * Marker that splits the v1.1 prompt body into the cached system portion and the per-call user
   * portion.
   */
  private static final String V11_USER_MARKER = "[NOT CACHED — per-report]";

  /**
   * Strips bracketed marker comments like {@code [CACHED — foo]} and {@code [/CACHED]} from
   * rendered text.
   */
  private static final Pattern V11_BRACKET_MARKERS =
      Pattern.compile("\\[(?:/?CACHED|NOT CACHED)[^\\]]*]\\s*", Pattern.CASE_INSENSITIVE);

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final String modelOverride;
  private final String mode;

  public SummaryService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      @Value("${ANTHROPIC_MODEL_SUMMARY:}") String modelOverride,
      @Value("${beat.prompts.summary.version:v1}") String mode) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.modelOverride = modelOverride;
    this.mode = normalizeMode(mode);
    log.info("SummaryService version mode = {}", this.mode);
  }

  static String normalizeMode(String raw) {
    if (raw == null) return MODE_V1;
    String m = raw.trim().toLowerCase();
    return switch (m) {
      case MODE_V1, MODE_V1_1, MODE_SHADOW -> m;
      default -> MODE_V1;
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
    return switch (mode) {
      case MODE_V1_1 -> generateV11(inputs, clientIndustry, styleNotes);
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
      default -> generateV1(inputs, styleNotes);
    };
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
    SystemAndUser split = splitV11(rendered);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r =
        anthropic.call(
            model,
            t.temperature(),
            t.maxTokens(),
            split.system(), /* cacheSystem */
            true,
            split.user());
    return finish(r.text().trim(), t.version());
  }

  /**
   * The v1.1 prompt body documents its cache structure with bracket markers. Split the rendered
   * text at the per-report marker, strip all markers from each side, and return the cacheable
   * system portion separately from the per-call user portion.
   */
  static SystemAndUser splitV11(String rendered) {
    int idx = rendered.indexOf(V11_USER_MARKER);
    String systemRaw = idx < 0 ? rendered : rendered.substring(0, idx);
    String userRaw = idx < 0 ? "" : rendered.substring(idx + V11_USER_MARKER.length());
    return new SystemAndUser(stripMarkers(systemRaw), stripMarkers(userRaw));
  }

  private static String stripMarkers(String s) {
    return V11_BRACKET_MARKERS.matcher(s).replaceAll("").trim();
  }

  record SystemAndUser(String system, String user) {}

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
