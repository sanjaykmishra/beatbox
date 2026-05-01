---
version: executive_summary_v1.1
model: claude-sonnet-4-6
temperature: 0.3
max_tokens: 1500
expected_output_format: text
prompt_cache:
  - summary_instructions_block
  - workspace_style_block
---

# Executive summary prompt (v1.1)

Generates the prose executive summary at the top of every coverage report.

This is v1.1 of the prompt; v1.0 ran on Opus and is currently shipping in Phase 1. The migration is gated on the eval set in `docs/06-evals.md` showing Sonnet output indistinguishable from Opus on hand-graded summaries (≥80% of judges cannot distinguish in side-by-side blind eval).

This prompt is part of the Phase 1 rebuild required by the cost engineering pass. See `docs/18-cost-engineering.md`. Modern Sonnet handles structured executive summaries at quality indistinguishable from Opus for this task — the summary's value comes from the structure of what's in the report, not from prose subtlety only Opus delivers.

## How the cost engineering applies here

Two layers of savings:

1. **Model downgrade Opus → Sonnet.** ~80% reduction in per-call cost. Quality preserved because the task is structured (paragraph 1 = headline finding, paragraph 2 = sentiment + reach summary, paragraph 3 = notable quotes or anomalies). Sonnet handles structured prose at this length cleanly.
2. **Prompt-cached static guidance.** The summary instructions and the workspace's brand-voice notes are stable per call. Caching reduces input tokens after the first call in a window.

Combined: ~$3-5/month per workspace projection on Opus → ~$0.80-1.50/month on Sonnet.

## Inputs

Same as v1.0:
- `{{client_name}}`, `{{client_industry}}`
- `{{report_period}}` — e.g. "January 2026"
- `{{coverage_items_summary}}` — structured summary of all coverage items in the report (headlines, sentiment counts, top outlets, top topics)
- `{{client_context}}` — optional pre-rendered context block (or empty string)
- `{{workspace_style_notes}}` — optional per-workspace style notes (tone, length preference, do-not-use phrases) — prompt-cached

## Prompt

```
[CACHED — summary_instructions_block]
You are writing the executive summary at the top of a monthly PR coverage
report. The summary is what the agency owner reads to their client first.
It must be confident, specific, and grounded in the data shown — never
hyperbolic, never generic.

Three paragraphs, in this order:

1. Headline finding. What's the most important thing the client should know
   about their coverage this period? One sentence stating the finding,
   followed by 1-2 sentences of supporting context from the data.

2. Sentiment and reach pattern. How did coverage skew this period — positive,
   neutral, mixed, negative? What outlets carried the highest-reach pieces?
   Were there notable shifts vs. typical patterns?

3. Notable quotes or anomalies. The single most newsworthy quote from the
   coverage, or the unusual story that broke the pattern (a competitor
   mention, a regulatory development, a journalist who covered the client
   for the first time). One paragraph maximum.

Constraints:
- Every claim must be supported by the data shown. Do NOT invent statistics,
  quotes, or trends not present in coverage_items_summary.
- Avoid hyperbole: no "groundbreaking," "incredible," "unprecedented,"
  "tremendous." Confident factual language only.
- The summary is for THIS client's executive team. Keep industry context
  light; assume they know their own industry.
- Length: 250-400 words across the three paragraphs.
- No bullet lists. Prose only.
- No section headers within the summary itself. The structure is implicit.
[/CACHED]

[CACHED — workspace_style_block]
{{#if workspace_style_notes}}
Style guidance for this workspace:
{{workspace_style_notes}}
{{/if}}
[/CACHED]

[NOT CACHED — per-report]
Client: {{client_name}} ({{client_industry}})
Report period: {{report_period}}

{{client_context}}

Coverage data for the period:
{{coverage_items_summary}}

Write the executive summary.
```

## Design notes

- **Sonnet is sufficient.** The eval set will demonstrate this; if Sonnet falls short on a specific category of report (e.g., crisis-period reports where prose nuance matters more), that category can route to Opus.
- **No JSON output.** This prompt returns plain prose; the calling code wraps it in the report HTML. Keeps the prompt simpler and the output more natural.
- **Temperature 0.3.** Slight variation in framing across reports; deterministic enough that re-runs produce similar (not identical) summaries.
- **Style notes prompt-cached.** Per-workspace style guidance is stable (changes when the agency updates their style guide, not per report). After the first report in a cache window, ~70% of style-block bytes are cached.

## Eval set

15 hand-graded report-period scenarios:

- 5 routine coverage months (typical mix of sentiment, no major events)
- 3 launch-month reports (heavy positive coverage around a single announcement)
- 2 crisis-month reports (testing somber, careful framing)
- 2 quiet months (low coverage volume)
- 2 mixed-with-anomaly reports (one unexpected piece of coverage)
- 1 competitor-saturation report (the client got eclipsed by a competitor)

For each scenario, hand-written acceptable summary characteristics: must-include facts, forbidden hyperbole, expected tone calibration.

Hard gates:
- Schema compliance: 100% (length within 250-400 words; three paragraphs; prose only)
- Zero hallucinated statistics or quotes (LLM-as-judge against coverage_items_summary)
- Zero hyperbole (regex against forbidden words list + LLM-as-judge)
- Crisis-period reports show appropriate restraint (LLM-as-judge with hand-written rubric)
- Side-by-side blind eval: Sonnet output is indistinguishable from v1.0 Opus output on ≥80% of cases

## Migration plan

1. Build v1.1 with cached structure.
2. Shadow mode for 2 weeks — both v1.0 (production) and v1.1 generate; user sees v1.0; v1.1 logged.
3. LLM-as-judge comparison + manual review on 30 sampled summaries.
4. Promote v1.1 behind feature flag for one customer-month.
5. Full rollout. v1.0 retired.

Existing reports keep their original prompt version on the relevant row (per `docs/05-llm-prompts.md`); never re-summarize retroactively.

## Known limitations

- Sonnet may produce slightly less elegant prose than Opus on the rare report that genuinely benefits from prose subtlety (e.g., delicate handling of mixed sentiment around layoffs). The fallback path: workspace owners can flag a report as "needs Opus" via the report settings; that report routes to v1.0 Opus pricing.
- Prompt-cache TTL means the first report in a long window pays full input cost. Practical impact: small.
