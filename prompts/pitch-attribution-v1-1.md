---
version: pitch_attribution_v1.1
model: claude-haiku-4  # for high-similarity binary decisions; escalates to claude-sonnet-4 for medium-similarity
temperature: 0.0
max_tokens: 300
expected_output_format: json
prompt_cache:
  - attribution_instructions_block
escalation:
  to_model: claude-sonnet-4
  trigger: similarity in [0.65, 0.85)
pre_filter:
  embedding_threshold: 0.65   # below this, no LLM call
---

# Pitch attribution prompt (v1.1)

Decides whether a piece of coverage was caused by a specific pitch. Surfaces suggestions for human review (per `docs/12-phase-3-pitch-tracker.md` §12.3).

This is v1.1 of the prompt; v1.0 ships first as part of Phase 3 Part 1. The migration to v1.1 is gated on the eval set in `docs/06-evals.md` showing the embedding-prefiltered + tiered system maintains ≥80% recall on true attributions while the LLM call volume drops by ~70%.

This is part of the Phase 3 cost engineering pass. See `docs/18-cost-engineering.md`.

## How the cost engineering applies here

Three layers stack:

1. **Embedding-based candidate pre-filter.** For each new article, embed the article and embed each candidate pitch (using cached embeddings where available). Cosine similarity narrows to top-5 candidates. Below a 0.65 similarity threshold, no LLM call — the article is unattributed. Embedding API is ~$0.0001 per article; cheap.
2. **Haiku for clear matches.** Top candidate with similarity ≥0.85 goes to Haiku for binary "is this attributable?" verification. Haiku handles confident matches reliably.
3. **Sonnet only for medium-similarity ambiguity.** Similarity 0.65-0.85 is genuine ambiguity — Sonnet for the reasoning. The volume of medium-similarity cases is typically 30-40% of attribution candidates.

Combined: ~$1-2/month per workspace projection on full-Sonnet → ~$0.20-0.60/month.

## Orchestration

```
1. New coverage_items row appears (Phase 1 ingestion or Phase 4 monitoring).
2. SQL filter: pitches sent to the same author OR pitches sent within 30 days
   for the same client. Typically 0-10 candidate pitches.
   If 0 candidates, no attribution; done.

3. Embed the article. For each candidate pitch, lookup or compute pitch
   embedding (cached). Compute cosine similarity for each (article, pitch) pair.

4. For each candidate ranked by similarity:
   - similarity < 0.65: skip (no LLM call).
   - similarity in [0.65, 0.85): escalate to Sonnet attribution prompt.
   - similarity >= 0.85: run Haiku attribution prompt.

5. For each LLM-confirmed attribution, write pitch_coverage_attributions row
   with status='suggested', confidence from prompt output.

6. Suggestions with confidence >= 0.6 surface in the dashboard for human review.
   Auto-confirmation (workspace setting, default off) applies at >= 0.85.
```

## Inputs

When LLM is invoked:
- `{{article_headline}}`, `{{article_outlet}}`, `{{article_publish_date}}`
- `{{article_summary}}` — 2-sentence neutral summary from coverage extraction
- `{{pitch_subject}}`, `{{pitch_sent_at}}`, `{{pitch_journalist_name}}`
- `{{pitch_summary}}` — 1-line summary of the pitch
- `{{embedding_similarity}}` — pre-computed cosine similarity, surfaced as context

## Prompt — Haiku tier (high-similarity)

For pairs with similarity ≥0.85, where the question is mostly verification:

```
[CACHED — attribution_instructions_block]
You are deciding whether a piece of coverage was caused by a specific PR
pitch. The system has already identified high topical similarity; your job
is to verify or reject.

A pitch CAUSED coverage when:
- The journalist who covered the piece received the pitch (same person)
  OR a colleague at the same outlet received the pitch
- The article appeared after the pitch was sent
- The article's framing/topic substantially matches the pitch's framing/topic
- No counter-evidence (article references events that postdate the pitch
  in ways that suggest the journalist was already covering this independently)

Output schema:
{
  "is_attributable": "boolean",
  "confidence": 0.0-1.0,
  "rationale": "string — 1-2 sentences explaining the decision, citing specific overlap or counter-evidence"
}

Constraints:
- If the article postdates the pitch by less than 24 hours, weight that against
  causation (the journalist almost certainly was already working on it).
- If the article is from a journalist who didn't receive the pitch but is at
  the same outlet, only attribute if the timing and topic strongly align.
- If similarity is high but timing is wrong, set is_attributable=false with
  appropriate rationale.
- Do NOT invent details not in the inputs.
[/CACHED]

[NOT CACHED — per-pair]
Article:
- Headline: {{article_headline}}
- Outlet: {{article_outlet}}
- Published: {{article_publish_date}}
- Summary: {{article_summary}}

Pitch:
- Subject: {{pitch_subject}}
- Sent: {{pitch_sent_at}}
- To journalist: {{pitch_journalist_name}}
- Summary: {{pitch_summary}}

Topical similarity (pre-computed): {{embedding_similarity}}

Decide. Return ONLY the JSON.
```

## Prompt — Sonnet escalation tier (medium-similarity)

For pairs with similarity in [0.65, 0.85), where the judgment is harder. Same structure with one addition:

```
[Same instructions block as Haiku tier, plus:]

The topical similarity is in the medium range — strong overlap on some
dimensions but not all. Reason carefully about whether the pitch likely
caused this coverage or whether the alignment is coincidental (the
journalist may have been working on the topic independently).

[Per-pair inputs identical to Haiku tier.]
```

## Output and persistence

The attribution worker writes one `pitch_coverage_attributions` row per (article, pitch) pair the LLM confirmed:

- `status = 'suggested'` (default) or `'confirmed'` if auto-confirm threshold met
- `confidence` from prompt output
- `rationale` from prompt output
- `attribution_prompt_version = 'pitch_attribution_v1.1'`

The dashboard surfaces unconfirmed suggestions as `attribution.pending` alerts (per `docs/16-client-dashboard.md`).

## Eval set

30 hand-labeled (pitch, coverage, expected_attribution) triples:

- 10 clear true attributions (matching journalist, matching topic, plausible timing)
- 10 clear false attributions (wrong journalist, wrong topic, or impossible timing)
- 5 ambiguous cases (medium-similarity, mixed evidence) — these test the Sonnet tier specifically
- 5 timing-edge cases (article published <24h after pitch — should NOT attribute)

Hard gates:
- Zero false-confirmed attributions on the eval set when run with auto-confirm threshold 0.85
- ≥80% recall on true attributions at confidence ≥0.6
- Embedding pre-filter correctly drops non-attributable pairs (similarity < 0.65) on ≥95% of cases
- Confidence calibration: high-confidence outputs (>=0.85) precision ≥95%; medium-confidence (0.6-0.84) precision ≥75%

## Migration plan

v1.0 ships as part of Phase 3 Part 1 build (Sonnet on all candidates). v1.1 lands shortly after:

1. Build embedding pipeline and cache (`pitch_embeddings`, `coverage_embeddings` tables).
2. Build orchestration with similarity-based routing behind a feature flag.
3. Shadow mode 2 weeks: both v1.0 and v1.1 attribute every new article; user sees v1.0; v1.1 logged.
4. Compare on real traffic — measure recall, precision, cost.
5. Tune thresholds based on observed agreement.
6. Promote v1.1.

## Known limitations

- Embeddings are cached, but cache misses (new pitches not yet embedded) require an API call. Background warming keeps the cache hot.
- An article that genuinely was caused by a pitch but is written in a very different style (e.g., the journalist took the pitch and turned it into a contrarian piece) may produce low embedding similarity and get filtered out. The user can manually attribute these.
- Cross-language attribution doesn't work — the embedding model is English-tuned. Non-English coverage falls through to manual attribution. Phase 4+.
