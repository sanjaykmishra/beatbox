---
version: extraction_v1.2
model: claude-haiku-4  # first-pass; escalates to claude-sonnet-4 on confidence-low or schema-fail
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.ExtractionSchema
prompt_cache:
  - extraction_instructions_block
escalation:
  to_model: claude-sonnet-4
  trigger: confidence == "low" OR schema_validation_failed
---

# Article extraction prompt (v1.2)

Same shape as `extraction-v1-1.md` plus cost engineering: two-tier extraction (Haiku first-pass, Sonnet escalation), prompt-cached extraction instructions, and orchestration-layer URL pre-filter and cross-customer dedup.

This is v1.2 of the prompt; v1.0 and v1.1 are currently shipping in Phase 1. The migration is gated on the eval set in `docs/06-evals.md` showing combined two-tier accuracy ≥ v1.1's accuracy on the full golden set, with hallucination rate at 0.

This prompt is part of the Phase 1 rebuild required by the cost engineering pass. See `docs/18-cost-engineering.md`.

## How the cost engineering applies here

Four layers of savings:

1. **URL pre-filter.** Pattern-match obvious non-articles (homepage, category pages, tag listings, paginated archives) before any LLM call. Saves 5-15% of calls.
2. **Cross-customer URL deduplication.** Two workspaces tracking the same client may scrape the same article. Extraction output is a function of article content (no per-workspace data) so the extracted JSON can be shared keyed by `(content_hash, prompt_version)`. Hit rate 8-15% in mature systems.
3. **Two-tier extraction.** Haiku handles the structural pass (headline, byline, outlet, date, sentiment, summary). ~80% of articles are clean professional journalism that Haiku extracts reliably. Below confidence threshold or on schema-validation failure, escalates to Sonnet.
4. **Prompt-cached instructions.** The extraction prompt body is stable; only inputs change per call. Caching reduces input-token overhead.

Combined, these reduce per-workspace coverage extraction cost from a $5-15/month projection on full-Sonnet to $1.50-4/month.

## Inputs

Same as v1.1:
- `{{url}}`, `{{outlet_name}}`, `{{subject_name}}`
- `{{client_context}}` — optional pre-rendered context block (or empty string)
- `{{article_text}}` — cleaned article text, ≤ 8000 tokens

Plus a new addition:
- `{{escalate_reason}}` — populated only when this prompt is running on the Sonnet escalation tier (otherwise empty)

## Prompt — Haiku tier

```
[CACHED — extraction_instructions_block]
You are extracting structured data from a news article about a company or
person that the user is doing PR for. Be factual, neutral, and concise.
If a field cannot be determined from the text, return null — do not guess.

If you encounter ambiguous content where you can't confidently extract a
field (unusual article structure, content seems unrelated to subject,
sentiment is genuinely mixed across paragraphs), set the response's
"confidence" field to "low" — a more capable model will re-extract.

Output schema:
{
  "headline": "string — exact headline as published",
  "subheadline": "string or null",
  "author": "string or null — primary byline only, no 'and' joins",
  "publish_date": "ISO 8601 date or null",
  "lede": "string — first sentence or two of the article, verbatim, max 280 chars",
  "summary": "string — your 2-sentence neutral summary of how subject is mentioned, max 50 words",
  "key_quote": "string or null — the single most newsworthy direct quote",
  "sentiment": "one of: positive | neutral | negative | mixed — sentiment toward subject specifically",
  "sentiment_rationale": "string — one short sentence justifying the sentiment label",
  "subject_prominence": "one of: feature | mention | passing",
  "topics": "array of 1-3 lowercase topic tags",
  "confidence": "high | medium | low — your confidence in this extraction"
}

Return ONLY the JSON object, no surrounding text.
[/CACHED]

[NOT CACHED — per-article]
Source URL: {{url}}
Outlet (if known): {{outlet_name}}
Subject of coverage: {{subject_name}}

{{client_context}}

Article text:
---
{{article_text}}
---
```

## Prompt — Sonnet escalation tier

The Sonnet escalation prompt has the same structure with one addition: it receives the Haiku tier's `escalate_reason` (the field that would have been "confidence" set to "low") plus the original article. Output schema matches.

```
[Same instructions block as Haiku tier]

[NOT CACHED]
Source URL: {{url}}
Outlet: {{outlet_name}}
Subject: {{subject_name}}

{{client_context}}

The first-pass extraction returned low confidence with this rationale:
{{escalate_reason}}

Re-extract with full attention. Article text:
---
{{article_text}}
---
```

## Orchestration

```
1. Compute content_hash of article_text. Look up cross-customer cache for (content_hash, prompt_version=extraction_v1.2).
   If hit, return cached extraction (write-through to coverage_items with workspace's metadata).

2. URL pre-filter: pattern-match against known non-article URL shapes.
   If filter matches, mark coverage_item as 'failed' with explanation; do not call LLM.

3. Run Haiku extraction. Validate schema strictly.
   - If schema valid AND confidence in {"high", "medium"}: persist, write to cross-customer cache.
   - If schema invalid OR confidence == "low": escalate to Sonnet with escalate_reason populated.

4. Run Sonnet extraction (if escalated). Validate schema; persist; write to cache.

5. mergeRespectingUserEdits as in v1.0/v1.1 — any field listed in coverage_items.edited_fields is preserved.
```

## Eval set additions

v1.0 and v1.1 eval sets carry forward. New tests:

**Haiku-tier confidence calibration.**
- 20 articles where Haiku should return high/medium confidence — gate: ≥90% resolve at Haiku tier.
- 10 articles where Haiku should escalate (unusual structure, ambiguous sentiment, off-topic content) — gate: ≥85% correctly escalate.

**Cross-customer cache integrity.**
- Verify cache entries don't carry workspace-specific data (no `subject_name` substitution leakage in cached output).
- Verify cache invalidation when prompt version bumps.

**Combined two-tier accuracy.**
- Full v1.1 eval set run end-to-end through v1.2 — gate: hallucination rate stays at 0; field-level accuracy ≥ v1.1's recorded accuracy.

## Migration plan

This is a Phase 1 rebuild. Sequence:

1. Build URL pre-filter rules and content-hash cache table.
2. Build Haiku prompt and escalation orchestration behind a feature flag.
3. Shadow mode: v1.1 (current production) and v1.2 both extract every new article; user sees v1.1; v1.2 logged.
4. Compare on real traffic — measure agreement, escalation rate, cost.
5. Tune escalation threshold based on observed agreement.
6. Promote v1.2 behind feature flag for one customer-month.
7. Update `ANTHROPIC_PROMPT_EXTRACTION_VERSION` to `extraction_v1.2`.
8. Existing `coverage_items` rows pinned to their original prompt version remain pinned. New extractions use v1.2.

Per `docs/05-llm-prompts.md`, never re-extract retroactively — the version on the row is the only debugging path for "why is this 6-month-old report different from a fresh one."

## Known limitations

- Cross-customer dedup hit rate is low for workspaces in distinct verticals. Most savings come from the two-tier model and prompt caching.
- The Haiku tier may underperform on articles with unusual languages or non-standard byline conventions. Eval coverage on edge cases needs ongoing expansion.
- Schema validation failures are escalated to Sonnet, but if Sonnet also fails schema, the existing v1.0/v1.1 retry-once-then-fail logic applies.
