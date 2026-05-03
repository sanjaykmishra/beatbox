---
version: executive_summary_v1.2
model: claude-sonnet-4-6
temperature: 0.2
max_tokens: 1500
expected_output_format: text
prompt_cache:
  - summary_instructions_block
  - workspace_style_block
---

# Executive summary prompt (v1.2)

Tightens v1.1's grounding rules after a real-traffic dogfood surfaced sev-1 hallucinations: the
Sonnet path fabricated outlets ("Platformer"), articles ("Meta employees becoming training data"),
and item counts ("eight pieces of coverage" when the real count was 3). It also softened a
zero-substantive-coverage period ("Franklin BBQ appeared in the right rooms, but largely as
context") into PR-speak instead of stating outright that the client wasn't covered.

Per CLAUDE.md guardrail #3 ("Never invent attributions in summaries — that's a sev-1 bug"), this
is a hard quality regression that v1.1's prompt couldn't catch alone.

## What changes vs. v1.1

1. **Per-item context is now passed.** v1.1 only got counts + top outlets + top 5 headlines.
   v1.2 also receives per-item detail: the extraction-side summary (which already says "client not
   mentioned in this article" when applicable), the `subject_prominence` value, and the per-item
   sentiment. Grounding is now on the per-item facts, not vibes from headlines.
2. **Subject-prominence aggregate is in the input.** {{feature_n}}/{{mention_n}}/{{passing_n}}
   counts let the prompt enforce a hard rule about how to talk about prominence.
3. **Zero-substantive-coverage rule.** If 0 items have prominence ∈ {feature, mention}, the
   summary MUST state directly that the client was not covered as a subject — no softening.
4. **Outlet-fabrication ban.** The prompt explicitly says only outlets in the per-item block may
   be named; no inferred or implied outlets.
5. **Count-fabrication ban.** Item counts and tier counts must match the structured stats verbatim.
6. **Lower temperature** (0.2 vs. 0.3) to reduce inventive variance on a task where invention is
   the failure mode.

The product's runtime layer ALSO short-circuits the LLM call when
{{#if has_no_substantive_coverage}}has_no_substantive_coverage{{/if}} is true (see
`SummaryService.generateV12`). v1.2 still has to handle the case correctly because re-runs from
admin tooling may bypass the guard, and partial-coverage cases (1 mention + 10 passing) still go
through the LLM and need the grounding rules.

## Inputs

Same as v1.1, plus:
- `{{coverage_items_summary}}` — now richer, per `SummaryInputs.coverageItemsSummaryV12()`. Includes
  prominence breakdown and per-item summary lines.

## Prompt

```
[CACHED — summary_instructions_block]
You are writing the executive summary at the top of a monthly PR coverage
report. The summary is what the agency owner reads to their client first.
It must be confident, specific, and grounded in the data shown — never
hyperbolic, never generic, and never invented.

Three paragraphs, in this order:

1. Headline finding. What's the most important thing the client should know
   about their coverage this period? One sentence stating the finding,
   followed by 1-2 sentences of supporting context from the data.

2. Sentiment and reach pattern. How did coverage skew this period — positive,
   neutral, mixed, negative? Which outlets carried the highest-reach pieces?
   Were there notable shifts vs. typical patterns?

3. Notable quotes or anomalies. The single most newsworthy quote from the
   coverage, or the unusual story that broke the pattern (a competitor
   mention, a regulatory development, a journalist who covered the client
   for the first time). One paragraph maximum.

GROUNDING RULES — these are non-negotiable. A summary that violates any of
them is a defective output:

A. Item count. The total item count, tier counts, and sentiment counts you
   state must match the structured stats in the input verbatim. Do not
   round, summarize, or extrapolate.

B. Outlets. The only outlets you may name are those that appear in the
   "Top outlets by reach" line OR in the per-item detail block. Do not
   name any outlet not in those lists. If you are not sure an outlet is in
   the data, do not mention it.

C. Articles and quotes. Every article you reference, summarize, or quote
   must be one of the items in the per-item detail block. Do not invent
   article subjects, headlines, or quotes. Do not paraphrase a quote that
   is not present.

D. Subject prominence. The prominence breakdown lists how many items each
   value has across {feature, mention, passing, missing, unknown}.
   - If most items are 'missing' (the client's name does not appear in
     the article body), your summary must say this directly. The runtime
     layer short-circuits when ALL items are missing/unknown, but
     partial-missing cases (e.g. 1 mention + 8 missing) reach the LLM and
     must be framed honestly.
   - Do not soften absence with metaphor ("appeared in the right rooms",
     "showed up in the conversation", "appeared as context"). State the
     finding plainly.

E. Hyperbole ban. No "groundbreaking," "incredible," "unprecedented,"
   "tremendous," "phenomenal," "amazing," "outstanding," "revolutionary,"
   "game-changer." Confident factual language only.

F. Length. 250-400 words across the three paragraphs. No bullet lists.
   No section headers within the summary.
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

Write the executive summary, observing the GROUNDING RULES literally.
```

## Eval set additions

v1.1's eval set carries forward. New tests:

**Zero-substantive-coverage scenario** (deterministic — runtime guard fires before the LLM).
- Hand-built fixture: client = "Franklin BBQ", 3+ articles all tagged
  `subject_prominence='missing'`. The runtime guard short-circuits and emits
  `noSubstantiveCoverageText`. Gate (unit test, not in `summary-rubric.yaml`): the deterministic
  output names the client, contains "not the subject" or "missing the subject", and contains
  zero outlet names from the missing items. No LLM call should fire.

**Partial-missing scenario** (`partial_missing_one_substantive` in `summary-rubric.yaml`).
- 1 mention + 7 missing. The SummaryInputs two-pass split feeds the LLM aggregates from the 1
  substantive item only; the prominence breakdown still surfaces the missing count for
  transparency. Gate: the reference summary names only the substantive outlet (TechCrunch),
  does not invent any of the 7 missing items' outlets (Anthropic, Vega, Platformer, Let's
  Data Science, Claude Code), and does not fabricate the count ("eight pieces" must NOT
  appear). Enforced offline via `must_include_phrases` / `must_not_include`.

**Outlet-fabrication probe.**
- 5 additional fixtures where the outlet list has known outlets (e.g. "TechCrunch, NYT") and
  the headlines reference them. Gate: the LLM-as-judge confirms no outlets outside that list
  are named.

**Count-fabrication probe.**
- 5 fixtures with various item counts. Gate: every numeric claim in the output (item count, tier
  counts, sentiment counts) matches the input verbatim.

**Outlet-leak regression probe.**
- The `partial_missing_one_substantive` fixture above is the canonical regression test for the
  SummaryInputs two-pass split. Before the split, the LLM saw tier/sentiment/outlet aggregates
  that included missing items — the must_not_include list catches that exact regression.

## Migration plan

1. Build v1.2 (this prompt). Land alongside v1.1 — no replacement until evals pass.
2. Add the runtime short-circuit guard (`hasNoSubstantiveCoverage`) — this ships in the same
   commit because it doesn't depend on the prompt.
3. Shadow mode for 1 week — `beat.prompts.summary.version=shadow_v12` (see SummaryService).
   Both v1.1 (production) and v1.2 generate; user sees v1.1; v1.2 logged.
4. LLM-as-judge comparison + manual review on 30 sampled summaries.
5. Promote v1.2 behind feature flag for one customer-month.
6. Full rollout. v1.1 retired.

Existing reports keep their original prompt version on the relevant row (per
`docs/05-llm-prompts.md`); never re-summarize retroactively.

## Known limitations

- The grounding rules add ~600 tokens to the cached system block (one-time +25% input cost on
  the first call in a cache window; ~10% steady-state given 90% cache-read share). Worth it.
- A determined LLM can still fabricate within the per-item summary text it received from
  extraction. The fix for that lives upstream in extraction prompts (separate eval gate).
