# 18 — Cost engineering across the AI surface

This doc imposes a system-wide cost discipline on every AI call in Beat. It exists because earlier phase specs accumulated AI features without a unifying cost ceiling, and the unconstrained accumulated bill at full Phase 3 + 4 volumes produces negative gross margin on heavy users at the original $99/month price point.

This doc applies retroactively to Phase 1 and 1.5 prompts (which are already shipped and will need to be rebuilt to v1.x successors as the cost-engineered versions land), and is built into Phase 3 prompts from the start.

When this doc and an individual prompt file disagree, this doc is canonical until the prompt file is updated to match.

## Targets

A fully-loaded active workspace at full Phase 3 volumes (8 clients, 5 campaigns/month, 200 pitches/month) should cost no more than $50/month in AI. With the LLM visibility add-on (Phase 4 paid feature) included, no more than $100/month. These ceilings support the recommended pricing of $59/$179/$349 per tier (see `docs/10-roadmap-overview.md`) with healthy gross margin.

Pre-engineering, the same workspace was projected at $120-280/month — too high to support the price point. Post-engineering, the projection is $25-47/month.

## The five levers

Every prompt and orchestration decision in the system maps to at least one of these. Together they routinely produce 50-80% savings without quality regression — but only when applied with judgment about what each prompt is actually doing.

**Tier models by signal value.** Haiku for high-volume mechanical work where the task is bounded (classification, structural extraction, first-pass scoring). Sonnet for judgment and synthesis. Opus reserved for the customer-facing artifact where prose quality dominates cost — and even then only when volume is bounded enough that absolute spend stays modest. The single most common cost mistake is using Sonnet (or Opus) for work Haiku handles fine.

**Cache aggressively.** Anything stable for more than 24 hours gets cached. Per-journalist tone analysis caches monthly (or quarterly for low-frequency journalists). Per-client context caches until edited. System prompts and shared instructions use Anthropic's prompt caching to amortize the overhead across all calls in a window. Strategy outputs are reused across all 50 pitches in a campaign rather than re-derived per pitch.

**Compress context ruthlessly.** Every prompt's input is reviewed for what's load-bearing vs. defensive padding. The strategy prompt previously fed top-10 recent coverage; top-5 plus structured summary serves as well. Pitch drafting previously fed the journalist's last 90 days of articles in full; the top 3-5 most relevant plus the cached tone descriptor serves as well. A 20% input-token reduction in a prompt called 200x per campaign is real money.

**Batch async work.** The Anthropic Batches API gives a 50% discount for work that can wait up to 24 hours. Ranking and drafting are user-async by nature — the user clicks "generate targets" and waits for results — so they go through batch mode. The user sees a progress indicator instead of a spinner; the cost halves.

**Pre-filter with deterministic code.** SQL filters, regex matchers, embedding similarity, and rule-based pre-screening narrow the candidate set before LLM gets involved. Ranking 200 candidates with an LLM is 200 calls; SQL-narrowing to 80 high-priors and LLM-ranking those is 80 calls. The LLM was wasted on the 120 obvious-no-fit candidates anyway. Same for de-duplication of coverage extraction across customers and pre-screening of replies that are clearly auto-replies.

## Feature-by-feature pass

Each feature below states current cost at typical workspace volumes, the levers applied, the projected post-engineering cost, and pointers to the prompt file. Pricing assumes Anthropic public rates as of April 2026: roughly $1/MTok input and $5/MTok output for Haiku, $3/$15 for Sonnet, $15/$75 for Opus. Batch-mode halves both input and output rates.

### Coverage extraction (Phase 1)

**Current.** Sonnet on every scraped article. ~10 reports/month × ~30 articles/report = 300 calls. ~$5-15/month.

**Levers.** Two-tier extraction (Haiku + Sonnet escalation), cross-customer URL deduplication, prompt-cached extraction instructions, URL-pattern pre-filter.

**Projected.** $1.50-4/month (70% reduction).

**Prompt file.** `prompts/extraction-v1-2.md` (cost-engineered v1.2; v1.0 and v1.1 currently shipping in Phase 1).

**Implementation.** Two prompts (Haiku-tier and Sonnet-escalation), confidence threshold tuning, deduplication keying by content hash, URL pre-filter rules. ~3 days of work.

### Executive summaries (Phase 1)

**Current.** Opus per report. 10 reports/month. ~$3-5/month.

**Levers.** Model downgrade to Sonnet with prompt-caching of static style guidance.

**Projected.** $0.80-1.50/month (70% reduction).

**Prompt file.** `prompts/executive-summary-v1-1.md` (cost-engineered v1.1; v1.0 currently shipping).

**Implementation.** Update prompt frontmatter, refresh eval set on Sonnet outputs, roll out behind a feature flag for one customer-month before full rollout. ~1 day.

### Reply classification (Phase 3 — Part 1)

**Current.** Sonnet on every inbound reply. Variable volume — 20-100 replies/month for an active workspace. ~$1-3/month.

**Levers.** Model downgrade to Haiku with confidence-based escalation; regex pre-classifier for obvious cases.

**Projected.** $0.10-0.40/month (85% reduction). Effectively free.

**Prompt file.** `prompts/reply-classification-v1-1.md` (cost-engineered v1.1; v1.0 ships first as part of Phase 3 Part 1).

**Implementation.** Migrate prompt to Haiku-tier, add regex pre-classifier with eval coverage, define escalation threshold. ~1 day.

### Pitch attribution (Phase 3 — Part 1)

**Current.** Sonnet on candidate (pitch, coverage) pairs. ~20-50 attribution decisions/month per workspace. ~$1-2/month.

**Levers.** Embedding-based candidate generation, Haiku for binary decisions on high-similarity matches, Sonnet only for medium-similarity ambiguous cases.

**Projected.** $0.20-0.60/month (70% reduction).

**Prompt file.** `prompts/pitch-attribution-v1-1.md` (cost-engineered v1.1).

**Implementation.** Embedding pipeline addition, two-tier orchestration, confidence calibration on existing labeled examples. ~3 days.

### Social post-variant generation (Phase 1.5)

**Current.** Sonnet generating 3 variants per post. ~$2-4/month.

**Levers.** Prompt caching of brand-voice context.

**Projected.** $1.20-2.50/month (35% reduction).

**Prompt file.** `prompts/post-variant-v1-1.md` (cost-engineered v1.1).

**Implementation.** Prompt restructuring for caching, no model migration. ~0.5 day.

### Campaign strategy (Phase 3 — Part 2)

**Current.** Opus per campaign brief. 5 campaigns/month at full agency tier. Pre-engineering projection $5-10/month.

**Levers.** Stay on Opus, but compress context aggressively (top 5 coverage; aggregated pitch counts) and prompt-cache static portions (client context, strategy instructions).

**Projected.** $2-4/month (60% reduction).

**Prompt file.** `prompts/campaign-strategy-v1.md` (ships at v1.0 with cost engineering built in).

**Why keep Opus.** Strategy is the keystone — wrong strategy cascades into wrong rankings, wrong pitches, wrong outcomes. The agency lead reads the strategy summary aloud to the client. Worth the cost. Run frequency is bounded, so absolute spend stays modest even on Opus.

### Journalist ranking (Phase 3 — Part 2) — highest leverage

**Current.** Pre-engineering projection: Sonnet per candidate. 200 candidates × 5 campaigns/month = 1000 calls. $30-50/month. The single biggest line item.

**Levers.** All five.

- Two-tier ranking — Haiku scores all candidates first; only the 45-80 borderline band escalates to Sonnet.
- Aggressive SQL pre-filter narrows from ~200 candidates to ~80-120 before LLM.
- Cached journalist enrichment — recent articles, beat tags, response rates read from `journalist_enrichment_cache` rather than recomputed.
- Batch via Anthropic Batches API — ranking is user-async.
- Trimmed strategy context — only fields the ranking actually uses.

**Projected.** $5-10/month (75-80% reduction).

**Prompt file.** `prompts/journalist-ranking-v1.md` (ships at v1.0 with two-tier).

**Implementation.** Two prompts (Haiku tier, Sonnet escalation), SQL pre-filter expansion, enrichment cache table + background job, batch-mode orchestration with progress reporting, eval coverage of both tiers. ~6 days. Biggest implementation lift in this pass and the highest payoff.

### Pitch tone analysis (Phase 3 — Part 2)

**Current.** Pre-engineering projection: Sonnet, cached per_author_monthly. ~$5-10/month at typical campaign volumes.

**Levers.** Frequency-tiered cache TTL (active journalists monthly, mid-frequency quarterly, low-frequency on-demand); prompt-cached analysis instructions.

**Projected.** $1.50-3/month (65% reduction).

**Prompt file.** `prompts/pitch-tone-analysis-v1.md` (ships at v1.0 with frequency tiering).

### Pitch draft (Phase 3 — Part 2) — second highest leverage

**Current.** Pre-engineering projection: Opus per pitch. 50 pitches × 5 campaigns/month = 250 drafts. $45-75/month.

**Levers.** Confidence-tiered model selection, prompt-cached campaign-shared context, trimmed journalist context, batch mode.

- High-confidence targets (score 80+, ~30-40% of campaign): Opus, target 150-220 words.
- Medium-confidence (60-79, ~40-50%): Sonnet, target 130-180 words.
- Low/exploratory (40-59, ~15-25%): Sonnet with shorter pitch (100-130 words).

**Projected.** $12-20/month (70% reduction).

**Prompt file.** `prompts/pitch-draft-v1.md` (ships at v1.0 with confidence routing).

**Why keep Opus at all.** High-confidence targets are by definition the journalists most likely to reply. Marginal quality of Opus over Sonnet matters most where the response rate is highest. Opus also stays as the quality anchor — when Sonnet 4.7 or 5 ships, the tiers re-evaluate and Opus may drop out entirely.

### Campaign insights (Phase 3 — Part 2)

**Current.** Pre-engineering projection: Sonnet, runs once per closed campaign. ~$1-3/month.

**Levers.** Pre-aggregate per-pitch outcomes in SQL before the prompt sees them; prompt-cache insights instructions.

**Projected.** $0.60-1.50/month (50% reduction).

**Prompt file.** `prompts/campaign-insights-v1.md` (ships at v1.0 with aggregated input).

### LLM visibility tracking (Phase 4 — paid add-on)

**Current.** Multi-provider queries running daily/weekly. Pre-engineering projection $30-100/month per workspace.

**Levers.** Batch all queries overnight (50% discount); aggressive cross-client query deduplication; tiered cadence by competitive intensity.

**Projected.** $15-50/month per workspace (50% reduction).

**Implementation.** Phase 4 work; deferred until LLM visibility ships.

## Total cost picture

The summed projected cost for a fully-loaded active workspace at full Phase 3 volumes (LLM visibility excluded as a paid add-on):

| Surface | Per-month cost |
|---|---|
| Coverage extraction | $1.50-4 |
| Executive summaries | $0.80-1.50 |
| Reply classification | $0.10-0.40 |
| Pitch attribution | $0.20-0.60 |
| Social post variants | $1.20-2.50 |
| Campaign strategy | $2-4 |
| Journalist ranking | $5-10 |
| Pitch tone analysis | $1.50-3 |
| Pitch draft | $12-20 |
| Campaign insights | $0.60-1.50 |
| **Total** | **$25-47** |

With LLM visibility add-on: total $40-97/month. The pessimistic case ($47, no add-on) leaves ~74% gross margin at the $179/month agency tier. The optimistic case ($25) leaves ~86% gross margin. Margin stays healthy across the range.

This is the cost picture that supports the pricing plan honestly. It also supports a possible future $99/month "solo + 2 clients" tier, since that workspace's volumes would be 25-30% of the figures above.

## Migration sequencing

Total work: ~26 person-days across all features. Sequence by leverage:

1. **Journalist ranking two-tier + batch** (week 1-2). Highest leverage. Start here. Ships as new in Phase 3 Part 2.
2. **Pitch draft confidence tiering + batch** (week 2-3). Second highest. Depends on ranking confidence labels being trustworthy. New in Phase 3 Part 2.
3. **Reply classification → Haiku** (week 3, parallel). Phase 3 Part 1 ships v1.0; v1.1 cost-engineered version ships shortly after.
4. **Coverage extraction two-tier** (week 3-4, parallel). Affects Phase 1 spend; requires Phase 1 rebuild.
5. **Tone analysis frequency tiering** (week 4). New in Phase 3 Part 2.
6. **Strategy + insights context compression** (week 4). Built into Phase 3 Part 2 v1.0.
7. **Pitch attribution embedding pre-filter** (week 5). Phase 3 Part 1 v1.0 ships first; v1.1 follows.
8. **Executive summary → Sonnet** (week 5). Affects Phase 1; requires Phase 1 rebuild.
9. **Social variant prompt-caching** (week 5). Affects Phase 1.5; requires Phase 1.5 rebuild.
10. **LLM visibility batch + dedup**. Deferred to Phase 4.

Each migration ships behind a feature flag with shadow-mode running for at least one week before old prompts retire. Cost reductions are measured on real workspace traffic; reverted if quality regression appears. The eval gates in `docs/06-evals.md` are non-negotiable.

For Phase 1 and Phase 1.5 prompts that have v1.x cost-engineered successors, the migration plan in each prompt file states the rebuild strategy. Existing customers continue to receive output from the v1.0/v1.1 prompts they were created against (per the prompt versioning discipline in `docs/05-llm-prompts.md`); new extractions and new pitches use the v1.x successor.

## What this discipline doesn't fix

A few things stay unsolved. They are not cost problems; they are different problems.

**Cold-start quality.** First-month customers don't have the cached enrichment data, the workspace-specific patterns, or the per-author response history. Their AI runs use defaults that are honest but weaker. The quality issue compounds with the cost issue — cold-start customers consume more tokens because their enrichment misses, and produce worse output because the enrichment is missing. No engineering lever fixes this; only time and dogfood do.

**The Opus/Sonnet quality boundary will move.** Today's Sonnet is good enough for medium-confidence pitch drafting. Sonnet 4.7 or 5 may be good enough for high-confidence drafting too, at which point Opus drops out of the system entirely. The orchestration layer must support model swaps cleanly — version the prompts, run side-by-side evals, promote when the new tier passes. Don't lock prompts to specific model identifiers in code paths that branch on model.

**The eval gates protect quality, not cost.** None of the levers above can ship if they fail the existing eval set. If switching reply classification to Haiku tanks the auto-reply detection rate, the migration doesn't happen. The cost-engineering pass is gated on quality preservation; the eval harness is the gate.

## Cross-references

- `docs/05-llm-prompts.md` — prompt versioning conventions; v1.x prompts in this pass follow the same patterns
- `docs/06-evals.md` — eval gates that must pass before any cost-engineering migration
- `docs/10-roadmap-overview.md` — pricing and cost projections that this doc justifies
- `prompts/` — individual prompt files reflect the cost-engineering choices; see in particular the v1.1/v1.2 successors of Phase 1 and 1.5 prompts, and the v1.0 versions of Phase 3 prompts that ship cost-engineered from the start
