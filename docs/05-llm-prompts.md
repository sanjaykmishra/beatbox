# 05 — LLM prompts

The actual prompt text lives in versioned files under `/prompts/`. This doc covers the system around them: model selection, versioning, integration points, and quality controls.

## Model selection

| Use case | Model | Why |
|---|---|---|
| Article extraction | Claude Sonnet | Cheap, fast, excellent at structured JSON output |
| Outlet tier classification (cache miss) | Claude Sonnet | Same |
| Executive summary | Claude Opus | Prose quality matters most here; this is what the client sees first |

Exact model versions are env vars (`ANTHROPIC_MODEL_EXTRACTION`, `ANTHROPIC_MODEL_SUMMARY`) so we can pin at deploy time and roll forward intentionally.

## Prompt sources

```
prompts/
├── extraction-v1.md          ← see contents
├── outlet-tier-v1.md
└── executive-summary-v1.md
```

Each file:
1. Starts with frontmatter: `version`, `model`, `temperature`, `max_tokens`, `expected_output_format`.
2. Contains the prompt template with `{placeholders}` for variable substitution.
3. Ends with notes documenting the design choices.

The Java prompt loader reads these at startup, validates frontmatter, and caches the parsed templates. Templates are versioned strings; runtime substitution uses a simple `{{var}}` style (Mustache-like) — NOT string concatenation.

## Versioning

Every coverage_item stores `extraction_prompt_version` ("extraction_v1.0", "extraction_v1.1", etc.).

When a prompt changes:
1. Bump the version in the frontmatter.
2. Save as a new file (`extraction-v1-1.md`) — never overwrite.
3. Run the eval harness on the new version. It must pass all hard gates.
4. Update `ANTHROPIC_PROMPT_EXTRACTION_VERSION` env var to point to the new version.
5. New extractions use the new version. Existing items are not re-extracted unless explicitly requested.

NEVER hot-swap a prompt. NEVER edit a prompt file in place after it ships. The version string on the row is the only way to debug "why is this 6-month-old report different from a fresh one."

## Integration: extraction worker pseudocode

```java
public CoverageItem extract(UUID coverageItemId) {
    var item = coverageRepo.findById(coverageItemId);
    var article = articleFetcher.fetch(item.getSourceUrl());

    var prompt = promptLoader.load("extraction-v1");
    var rendered = prompt.render(Map.of(
        "url", item.getSourceUrl(),
        "outlet_name", article.getOutletName().orElse("unknown"),
        "subject_name", item.getReport().getClient().getName(),
        "article_text", article.getCleanText()
    ));

    var response = anthropic.messages(prompt.model(), rendered, prompt.options());
    var json = JsonValidator.parseStrict(response.text(), ExtractionSchema.SCHEMA);

    return mergeRespectingUserEdits(item, json, prompt.version());
}
```

`mergeRespectingUserEdits` is critical: any field listed in `coverage_items.edited_fields` is preserved. The LLM cannot overwrite a user's manual correction.

## Cost controls

Enforced in the worker:

- **Hard token cap per article**: 8,000 input + 1,000 output for extraction; truncate article text if needed.
- **Hard cost cap per workspace per month**: starts at $50 for solo, $200 for agency, configurable per-workspace by ops.
- **Idempotency cache**: `(article_content_hash, prompt_version) → extracted_json`. If a customer re-pastes the same article URL into a new report, we hit cache.
- **Workspace metering**: every LLM call writes `extraction.cost_usd` metric tagged by workspace_id. Weekly review for outliers.

## Output validation

The article extraction prompt returns JSON. We validate strictly:

1. JSON parses (else fail and retry).
2. JSON matches the schema (else fail and retry).
3. Specific field constraints (sentiment is one of 4 values, prominence is one of 3, etc.).

Schema definition lives in code (`ExtractionSchema.java`) and mirrors the prompt's documented schema. They must stay in sync — the eval harness includes a test that asserts this.

## Eval integration

Every change to a prompt file or the LLM client code triggers the full eval harness on CI. Hard gates (schema compliance, hallucination rate) block merge. See `docs/06-evals.md`.

## Logging

Per-call structured log:
```
{
  "level": "info",
  "msg": "anthropic_call",
  "request_id": "req_...",
  "workspace_id": "...",
  "coverage_item_id": "...",
  "prompt_version": "extraction_v1.0",
  "model": "claude-sonnet-4-...",
  "input_tokens": 4231,
  "output_tokens": 412,
  "cost_usd": 0.018,
  "duration_ms": 3120,
  "outcome": "success"
}
```

Never log the article content or the rendered prompt — those can contain customer data and could be enormous. We log the inputs to substitution (URL, outlet name) but not the substituted output.

## Failure modes & handling

| Failure | Handler |
|---|---|
| API rate limit (429) | Exponential backoff, retry up to 3 times |
| API error 5xx | Same |
| Malformed JSON output | Re-prompt once with "your previous response was not valid JSON, return ONLY the JSON object"; then fail |
| Schema validation fail | Same as malformed JSON |
| Article fetch fails (paywall, 404) | Mark item `failed` with explanatory message; don't burn an LLM call |
| Article too long (>8K tokens after Mercury) | Truncate to first 8K and proceed — note in `raw_extracted.truncated=true` |
| Job exceeds 60s wall time | Mark `failed`; surface to user |

## Things deliberately not done with LLMs

- **Outlet name → tier (when in curated list)**: deterministic lookup. Only LLM-classify on cache miss for unknown outlets.
- **Domain authority numbers**: pulled from DataForSEO, not LLM-generated.
- **Reach estimates**: deterministic formula based on outlet visits + recency. LLMs are bad at numbers we can compute.
- **Sentiment of the report overall**: aggregated deterministically from item-level sentiment.

LLMs do what only LLMs can do: read prose, summarize, classify ambiguous semantic content. They don't do arithmetic or lookups.
