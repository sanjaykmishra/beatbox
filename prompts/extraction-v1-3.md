---
version: extraction_v1.3
model: claude-haiku-4-5  # first-pass; escalates to claude-sonnet-4-6 on confidence-low or schema-fail
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.ExtractionSchema
prompt_cache:
  - extraction_instructions_block
escalation:
  to_model: claude-sonnet-4-6
  trigger: confidence == "low" OR schema_validation_failed
---

# Article extraction prompt (v1.3)

Adds a fourth `subject_prominence` value: `missing`. The v1.0–v1.2 enum (`feature | mention | passing`) had no value for "the subject is not in this article at all," so the LLM was forced to misuse `passing` (which strictly means "name appears once") for off-topic articles. That misuse downstream caused the executive-summary path to softpedal zero-coverage periods into PR-speak (see the Franklin BBQ dogfood: 3 articles all about other companies, all tagged `passing`, summary fabricated outlets).

Adding `missing` cleans the signal so the runtime guard in `SummaryService` can fire on a precise condition (≥1 non-`missing` item) rather than the imprecise proxy of "all `passing`."

## What changes vs. v1.2

1. **`subject_prominence` enum gains `missing`.** The schema spec inside the cached block lists the four values explicitly and pins their definitions.
2. **The instructions explicitly tell the model to choose `missing`** when the subject's name does not appear in the article body at all — even when the article is in the same industry or on a topically-adjacent subject.
3. **No structural change.** Same JSON shape, same caching layout, same orchestration. `ExtractionSchema.PROMINENCE` is extended to accept `missing`; existing v1.0/v1.1/v1.2 outputs (which never produce `missing`) remain valid under the same validator.

Old reports keep their original prompt version; the version on the row is the only debugging path for "why is this 6-month-old report different from a fresh one." See `docs/05-llm-prompts.md`.

## Inputs

Same as v1.2:
- `{{url}}`, `{{outlet_name}}`, `{{subject_name}}`
- `{{client_context}}` — optional pre-rendered context block (or empty string)
- `{{article_text}}` — cleaned article text, ≤ 8000 tokens

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
  "summary": "string — your 2-sentence neutral summary of how subject is mentioned, max 50 words. If the subject is not mentioned, say so plainly.",
  "key_quote": "string or null — the single most newsworthy direct quote",
  "sentiment": "one of: positive | neutral | negative | mixed — sentiment toward subject specifically. Use neutral when the subject is missing.",
  "sentiment_rationale": "string — one short sentence justifying the sentiment label",
  "subject_prominence": "one of: feature | mention | passing | missing",
  "topics": "array of 1-3 lowercase topic tags",
  "confidence": "high | medium | low — your confidence in this extraction"
}

subject_prominence definitions — pick the strictly correct value:
- feature: the subject is the primary subject of the article. The article is about them.
- mention: the subject is named with a quote or paragraph of context, but isn't the main subject.
- passing: the subject's name appears once, in passing (e.g., in a list of competitors).
- missing: the subject's name does NOT appear in the article body at all. Use this even when the
  article is in the same industry, mentions adjacent topics, or names competitors of the subject.
  Topical adjacency is not mention. If you searched the article body and the subject's literal
  name (or a clear alias) is absent, the value is missing.

When subject_prominence is missing:
- Set sentiment to "neutral" and sentiment_rationale to "subject not mentioned in article."
- Set key_quote to null.
- Still extract headline / author / publish_date / lede / summary as usual — they describe the
  article itself, not the subject's relationship to it.

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

Same structure as v1.2; reuses the cached instructions block above. The escalation prefix is built at call time by `TwoTierExtractionService` and prepended to the per-article block.

## Orchestration

Same as v1.2 with one addition: the cross-customer cache key bumps to `(content_hash, prompt_version=extraction_v1.3)`. v1.2 cache entries are not reused for v1.3 — the `subject_prominence` enum widened, so a v1.2-era cached row could be missing the `missing` discriminator the downstream guard depends on.

## Eval set additions

v1.0–v1.2 eval sets carry forward. New tests:

**Off-topic article scenarios.**
- 5 articles in the subject's industry that don't mention the subject — gate: 100% return `subject_prominence: missing`. The Franklin BBQ regression scenario lives here.
- 5 articles mentioning competitors only — gate: 100% return `missing` (competitor mention is not subject mention).
- 5 articles where the subject is mentioned in passing — gate: 100% return `passing`, not `missing`.

**Boundary calibration.**
- 3 articles where the subject is named exactly once, in a list — gate: `passing`, not `missing` (the strict definition is "name appears once").
- 3 articles where the subject is mentioned in a quoted source's affiliation only ("Jane Smith, formerly of Acme") — gate: `passing` if it's the subject's name, otherwise `missing`.

## Migration plan

1. Land v1.3 prompt + schema extension. v1.2 stays loadable.
2. Add `beat.prompts.extraction.version` flag (default `v1_3`; set `v1_2` to fall back during the migration).
3. Shadow mode for one week — run both v1.2 and v1.3 against new traffic; log the prominence delta. Manual-review 30 cases where v1.3 says `missing` but v1.2 said `passing`.
4. Promote v1.3 as the persistent default. v1.2 stays available behind the flag for one billing cycle.
5. Update `SummaryService.hasNoSubstantiveCoverage` to fire on "≥1 non-missing" rather than the v1.2-era proxy of "all passing." This is the change that closes the loop on the Franklin BBQ failure mode.

## Known limitations

- Re-extracting old reports under v1.3 would change their `subject_prominence` values from `passing` to `missing` for off-topic items. Per `docs/05-llm-prompts.md`, the discipline is "never re-extract retroactively" — so old reports keep their imprecise tags. The runtime guard's new logic ("≥1 non-missing") is therefore more permissive on old data (the guard won't fire for old all-`passing` reports) and more precise on new data (guard fires for all-`missing` reports).
- The cost-engineering numbers from v1.2 are unchanged: same model, same caching layout, same prompt length give-or-take ~80 tokens for the new instructions.
