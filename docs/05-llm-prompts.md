# 05 — LLM prompts

The actual prompt text lives in versioned files under `/prompts/`. This doc covers the system around them: model selection, versioning, integration points, and quality controls.

## Model selection

The system tiers models by signal value per the cost engineering discipline in `docs/18-cost-engineering.md`. Three tiers are in active use:

| Use case | Model | Why |
|---|---|---|
| Reply classification (post-pre-filter) | Claude Haiku | Bounded 6-class task on short text; Haiku handles confidently with Sonnet escalation only on low-confidence cases |
| Coverage extraction (first-pass) | Claude Haiku | ~80% of articles are clean professional journalism Haiku extracts reliably; escalates to Sonnet on low confidence or schema fail |
| Pitch attribution (high-similarity matches) | Claude Haiku | Binary verification on top embedding-similarity matches; deterministic enough for Haiku |
| Journalist ranking (first-pass) | Claude Haiku | Score the bulk of candidates; escalate borderline (45-80) to Sonnet |
| Coverage extraction (escalation) | Claude Sonnet | When Haiku flags low confidence or fails schema |
| Outlet tier classification (cache miss) | Claude Sonnet | Light reasoning; cached forever per domain so volume is low |
| Pitch tone analysis | Claude Sonnet | Pattern characterization; cached with frequency-tiered TTL |
| Executive summary | Claude Sonnet | Structured prose; modern Sonnet matches Opus on this task |
| Pitch draft (medium/low confidence) | Claude Sonnet | Personalized prose; modern Sonnet handles routine pitches at quality indistinguishable from Opus |
| Reply classification (escalation) | Claude Sonnet | When Haiku confidence is low |
| Pitch attribution (medium-similarity) | Claude Sonnet | Genuine ambiguity needs reasoning |
| Campaign insights | Claude Sonnet | Analytical writing on aggregated data |
| Campaign strategy | Claude Opus | Keystone — drives every downstream step; agency reads aloud to client |
| Pitch draft (high confidence) | Claude Opus | Highest-stakes pitches go to most-likely-to-respond journalists; quality matters most here |

Exact model versions are env vars (`ANTHROPIC_MODEL_HAIKU`, `ANTHROPIC_MODEL_SONNET`, `ANTHROPIC_MODEL_OPUS`) so we can pin at deploy time and roll forward intentionally.

Note that for prompts running on multiple models (extraction, ranking, pitch draft, reply classification, attribution), each prompt file documents which model handles which path and the escalation/routing logic. The orchestration layer decides which to call.

## Prompt sources

```
prompts/
├── extraction-v1.md, extraction-v1-1.md, extraction-v1-2.md
├── outlet-tier-v1.md
├── executive-summary-v1.md, executive-summary-v1-1.md
├── reply-classification-v1.md, reply-classification-v1-1.md
├── pitch-attribution-v1.md, pitch-attribution-v1-1.md
├── post-variant-v1.md, post-variant-v1-1.md
├── social-extraction-v1.md
├── campaign-strategy-v1.md          ← Phase 3 Part 2
├── journalist-ranking-v1.md         ← Phase 3 Part 2
├── pitch-tone-analysis-v1.md        ← Phase 3 Part 2
├── pitch-draft-v1.md                ← Phase 3 Part 2
└── campaign-insights-v1.md          ← Phase 3 Part 2
```

Versioning lineage: v1.0 prompts are the originals. v1.1/v1.2 successors are the cost-engineered versions per `docs/18-cost-engineering.md`. Phase 3 Part 2 prompts ship at v1.0 with cost engineering built in (no v1.0-then-v1.1 lineage; the cost-engineered design is the initial release).

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
- **Hard cost cap per workspace per month**: starts at $150 for solo, $400 for agency, $700 for studio, configurable per-workspace by ops. These ceilings reflect the cost engineering analysis in `docs/18-cost-engineering.md` — typical workspaces run well under these caps; the cap exists to catch runaway-loop bugs and abuse, not to throttle normal usage.
- **Idempotency cache**: `(article_content_hash, prompt_version) → extracted_json`. If a customer re-pastes the same article URL into a new report, we hit cache. Coverage extraction v1.2 extends this to a cross-customer cache (extraction output is workspace-agnostic) for additional savings.
- **Workspace metering**: every LLM call writes `extraction.cost_usd` metric tagged by workspace_id. Weekly review for outliers.
- **Prompt caching**: most prompts use Anthropic's prompt caching for stable instruction blocks and per-workspace context. See individual prompt files for which blocks are cached.
- **Batch mode**: ranking and drafting (Phase 3 Part 2) run via the Anthropic Batches API for the 50% async discount.

For the system-wide cost discipline (model tiering, caching, context compression, batching, deterministic pre-filters), see `docs/18-cost-engineering.md`. That doc is canonical for cost-related decisions until individual prompt files are updated to match.

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
