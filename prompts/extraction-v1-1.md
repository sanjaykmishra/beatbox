---
version: extraction_v1.1
model: claude-sonnet-4-6
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.ExtractionSchema
---

# Article extraction prompt (v1.1)

Same shape as `extraction-v1.md` plus an optional `{{client_context}}` block per
docs/15-additions.md §15.1. The new block carries client-specific context
(key messages, style notes / preferred names, competitive set, recent context)
so the LLM can disambiguate names and produce summaries that respect the
agency's preferences.

Excluded by design (see §15.1 "what NOT to put in the prompt"):

- `do_not_pitch` — agency reference only; could bias sentiment.
- `important_dates` — embargo dates aren't relevant to a published article.

If the substituting code passes an empty `{{client_context}}` (no fields set),
the entire context block is omitted and the prompt behaves identically to v1.

## Inputs

- `{{url}}` — source URL of the article
- `{{outlet_name}}` — best guess at the outlet (or "unknown")
- `{{subject_name}}` — the company or person the agency is doing PR for
- `{{client_context}}` — optional pre-rendered context block (or empty string)
- `{{article_text}}` — cleaned article text (Mercury Parser output, ≤ 8000 tokens)

## Prompt

```
You are extracting structured data from a news article about a company or
person that the user is doing PR for. Be factual, neutral, and concise. If a
field cannot be determined from the text, return null — do not guess.

Source URL: {{url}}
Outlet (if known): {{outlet_name}}
Subject of coverage: {{subject_name}}

{{client_context}}

Article text:
---
{{article_text}}
---

Return a JSON object matching this schema:
{
  "headline": "string — exact headline as published",
  "subheadline": "string or null",
  "author": "string or null — primary byline only, no 'and' joins",
  "publish_date": "ISO 8601 date or null",
  "lede": "string — first sentence or two of the article, verbatim, max 280 chars",
  "summary": "string — your 2-sentence neutral summary of how {{subject_name}} is mentioned, max 50 words",
  "key_quote": "string or null — the single most newsworthy direct quote from or about {{subject_name}}, max 200 chars, verbatim from article",
  "sentiment": "one of: positive | neutral | negative | mixed — sentiment toward {{subject_name}} specifically, not the article overall",
  "sentiment_rationale": "string — one short sentence justifying the sentiment label",
  "subject_prominence": "one of: feature | mention | passing — feature = primary subject, mention = named with quote or paragraph, passing = name appears once",
  "topics": "array of 1–3 lowercase topic tags (e.g. 'funding', 'product launch', 'partnership', 'leadership')"
}

Return ONLY the JSON object, no surrounding text.
```

## Design notes

- Context fields should disambiguate (preferred CEO name, brand pronunciation)
  not bias judgment. The prompt doesn't ask the model to "match" the context;
  it just makes the context available so naming and tone are consistent.
- Same temperature / token budget as v1 — context is supplementary, not a new
  task surface.
- Eval coverage per docs/06-evals.md "Additional categories": 5 new context-
  aware items, regression on the no-context set, and a context-bleed test.

## Migration

When this prompt graduates from "alongside v1" to "default":

1. Bump `ANTHROPIC_PROMPT_EXTRACTION_VERSION` env var.
2. Existing `coverage_items.extraction_prompt_version` rows stay pinned to
   `extraction_v1.0` — never re-extract retroactively.
3. New extractions use v1.1.
