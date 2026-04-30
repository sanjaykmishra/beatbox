package app.beat.llm;

import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.report.Report;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates the executive summary via Anthropic Opus using prompts/executive-summary-v1.md.
 * Validates the output for hyperbole locally; the eval harness has the full rubric.
 *
 * <p>Disabled when ANTHROPIC_API_KEY isn't set so local dev still ships a PDF (with a placeholder
 * summary section in the template).
 */
@Service
public class SummaryService {

  private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

  private final PromptLoader prompts;
  private final AnthropicClient anthropic;
  private final String modelOverride;

  public SummaryService(
      PromptLoader prompts,
      AnthropicClient anthropic,
      @Value("${ANTHROPIC_MODEL_SUMMARY:}") String modelOverride) {
    this.prompts = prompts;
    this.anthropic = anthropic;
    this.modelOverride = modelOverride;
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
    if (!isEnabled()) throw new IllegalStateException("ANTHROPIC_API_KEY not configured");

    PromptTemplate t = prompts.get("executive-summary-v1");
    SummaryInputs inputs =
        SummaryInputs.build(clientName, report.periodStart(), report.periodEnd(), items, outlets);
    var vars = inputs.toPromptVars();
    if (styleNotes != null && !styleNotes.isBlank()) {
      vars.put("style_notes", styleNotes);
    }
    String rendered = t.render(vars);
    String model = modelOverride.isBlank() ? t.model() : modelOverride;

    AnthropicClient.Result r = anthropic.call(model, t.temperature(), t.maxTokens(), rendered);
    String text = r.text().trim();
    List<String> hits = HyperboleDetector.findViolations(text);
    if (!hits.isEmpty()) {
      log.warn("summary: hyperbole hits in generated summary for report {}: {}", report.id(), hits);
    }
    return new Outcome(text, t.version(), hits);
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
