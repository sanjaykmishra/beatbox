---
version: extraction_v1.0
model: claude-sonnet-4-6
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.schema.ExtractionSchema
---

# Article extraction prompt

Used by the extraction worker to pull structured data out of a single press article.

## Inputs

- `{{url}}` — source URL of the article
- `{{outlet_name}}` — best guess at the outlet (or "unknown")
- `{{subject_name}}` — the company or person the agency is doing PR for
- `{{article_text}}` — cleaned article text (Mercury Parser output, ≤ 8000 tokens)

## Prompt

```
You are extracting structured data from a news article about a company or
person that the user is doing PR for. Be factual, neutral, and concise. If a
field cannot be determined from the text, return null — do not guess.

Source URL: {{url}}
Outlet (if known): {{outlet_name}}
Subject of coverage: {{subject_name}}

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

- `{{subject_name}}` is critical for sentiment. An article can be globally negative ("the SaaS market is crashing") while being positive about our subject ("but Acme bucked the trend"). Sentiment must be subject-specific.
- We ask for the headline verbatim because we display it next to the screenshot — if they disagree the user notices immediately.
- `key_quote` must be verbatim. Inventing quotes is the worst possible failure mode. The eval harness specifically tests for fabricated quotes.
- Temperature 0.1 (not 0) to allow some flexibility in summary phrasing without inviting drift.
- `topics` are lowercase to make analytics aggregation deterministic.
- Schema is permissive on nulls because real articles often hide the publish date, byline, or have no quotable content.

## Known limitations

- Articles >8K tokens (after Mercury) get truncated. Long-form features may lose context.
- Non-English articles return mostly null. We fail explicitly rather than guess.
- Paywalled articles return what's visible (often headline + lede only).
