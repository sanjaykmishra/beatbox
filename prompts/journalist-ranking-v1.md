---
version: journalist_ranking_v1.0
model: claude-haiku-4  # first-pass; escalates to claude-sonnet-4 on borderline scores
temperature: 0.1
max_tokens: 400
expected_output_format: json
batch_mode: true
escalation:
  to_model: claude-sonnet-4
  trigger: score >= 45 AND score <= 80
  max_tokens_after_escalation: 600
---

# Journalist ranking prompt

Per-candidate scoring for a single (campaign, journalist) pair. Run many times per campaign — Haiku for first-pass cost, with Sonnet escalation for borderline candidates that need nuanced judgment.

This prompt ships at v1.0 with two-tier cost engineering built in. See `docs/18-cost-engineering.md` for the system-wide discipline. Ranking is the highest-cost AI surface in Beat at full Phase 3 volumes; the two-tier design plus SQL pre-filtering plus batch mode reduces per-workspace cost from a $30-50/month projection on Sonnet-only to $5-10/month.

## How the cost engineering applies here

Four layers of savings stack:

1. **SQL pre-filter.** The orchestration layer narrows candidates before this prompt is called. Topics, recent activity, opt-out status, and do-not-pitch list are all SQL-checkable. Typical pool: ~80-120 candidates instead of the full author database.
2. **Two-tier model.** Haiku handles confident-fit (≥80) and confident-no-fit (≤45) cleanly. The 45-80 borderline band escalates to Sonnet. Typically 65-75% of candidates resolve at Haiku.
3. **Cached enrichment.** Recent articles, beat tags, and `pitch_response_rate_global` are read from `journalist_enrichment_cache` (per `docs/12-phase-3-pitch-tracker.md` §12.5), refreshed by background job, never recomputed per ranking call.
4. **Batch mode.** Ranking is user-async; the entire ranking run goes through the Anthropic Batches API for the 50% discount.

The eval set must pass at both tiers. The Haiku tier is tested on its own outputs (does it produce reliable confident-fit/confident-no-fit decisions and reliably escalate ambiguous cases?), and the Sonnet tier is tested on the borderline-only inputs it receives in production.

## Inputs

- Strategy fields from `campaigns.strategy_structured`:
  - `{{topics}}`, `{{news_hooks}}`, `{{target_audiences}}` (names only — descriptions stripped to compress context)
- Journalist fields from `authors` + cached enrichment:
  - `{{author_name}}`, `{{author_outlet}}`, `{{author_outlet_tier}}`, `{{author_beats}}`
  - `{{author_recent_articles}}` — last 90 days from cache, formatted as `"YYYY-MM-DD | Headline | 1-line summary"` (5-10 lines)
  - `{{author_pitch_response_rate_global}}`, `{{author_pitch_response_rate_workspace}}`
- Workspace context:
  - `{{workspace_prior_coverage_with_author}}`, `{{client_competitive_context}}`

## Prompt — Haiku tier

```
You are scoring whether a journalist is a good fit for a PR campaign.
Score honestly — including weak fits as weak. Most candidates are weak.

Campaign:
- Topics: {{topics}}
- News hooks: {{news_hooks}}
- Target audiences: {{target_audiences}}

Journalist:
- {{author_name}} at {{author_outlet}} (Tier {{author_outlet_tier}})
- Beats: {{author_beats}}
- Recent articles (last 90 days):
{{author_recent_articles}}
- Reply rate to PR pitches (global, anonymized): {{author_pitch_response_rate_global}}
- Reply rate to this agency: {{author_pitch_response_rate_workspace}}
- Prior coverage of this client: {{workspace_prior_coverage_with_author}}
- Recent competitor coverage: {{client_competitive_context}}

Score the fit. Return JSON:

{
  "score": 0.0-100.0,
  "confidence": "high | medium | low | exploratory | escalate",
  "score_breakdown": {
    "topic_alignment": 0.0-25.0,
    "beat_fit": 0.0-20.0,
    "recency": 0.0-15.0,
    "audience_reach": 0.0-10.0,
    "responsiveness": 0.0-15.0,
    "client_history": 0.0-10.0,
    "competitive_signal": 0.0-5.0
  },
  "why_they_matter_brief": "string — 1 sentence. Reference one specific recent article. Required if confidence != escalate.",
  "escalate_reason": "string or null — populate ONLY if confidence == escalate"
}

Score guidance:
- 80-100 (high): clear fit on topic AND beat AND recent work AND outlet match
- 60-79 (medium): topic + beat fit but one signal is weak or missing
- 45-59: borderline — set confidence to "escalate"
- 30-44 (low): possible fit but multiple weak signals
- 0-29 (exploratory or no): adjacent territory or no fit

If you're uncertain about scoring (e.g., the journalist's beat seems
to be shifting, or the recent articles cover the topic but at an
unusual angle, or the response rate signals contradict the topical
fit), set confidence to "escalate" and explain why in escalate_reason.
A more capable model will score those cases.

Constraints:
- why_they_matter_brief must reference an actual article from
  author_recent_articles. Do not invent.
- Do NOT invent recent articles, beat tags, or response rate data.
- Score honestly; do not inflate to be charitable.

Return ONLY the JSON.
```

## Prompt — Sonnet escalation tier

The Sonnet escalation prompt receives only candidates the Haiku tier flagged as `escalate`, plus the Haiku tier's `escalate_reason` as additional context. Output schema matches the Haiku tier so downstream consumers see one shape.

```
You are evaluating a borderline (campaign, journalist) fit. A first-pass
model scored this candidate and flagged it for nuanced review. Your job
is to produce the final score and rationale.

[same campaign + journalist context as Haiku tier]

First-pass note: {{escalate_reason}}

Score the fit. Return JSON:

{
  "score": 0.0-100.0,
  "confidence": "high | medium | low | exploratory",
  "score_breakdown": { ... same as Haiku },
  "why_they_matter": "string — 2-3 sentences explaining why this journalist is or isn't a fit. Reference specific recent articles by headline. No claims about the journalist's interests beyond what their recent work demonstrates.",
  "risks": "string or null — anything to be careful about (e.g., 'recently covered a competitor's funding negatively', 'beat appears to have shifted')"
}

Confidence labels are NOT just score ranges — they reflect signal quality.
A medium score with strong signals on every dimension might be high
confidence. A high score driven by one strong signal and several missing
signals is medium confidence.

Same constraints as the Haiku tier — every claim grounded in actual articles, no invention.

Return ONLY the JSON.
```

## Orchestration

The ranking endpoint receives an array of candidates from the SQL pre-filter and runs them through the two-tier system:

```
1. Submit all candidates to Haiku via Batches API.
2. When batch returns:
   a. Candidates with confidence in {high, medium, low, exploratory} are accepted as-is.
   b. Candidates with confidence == "escalate" are queued for Sonnet.
3. Submit escalated candidates to Sonnet via Batches API.
4. Merge results, persist to campaign_targets including ranking_model_tier.
```

Total wall-clock time: typically 3-8 minutes for a campaign with 80-120 candidates, of which ~25-35% escalate. The UI shows progress per-tier.

## Eval set

30 hand-labeled (strategy, journalist) pairs:

- 10 strong fits (recent articles directly on topic; beat aligned)
- 10 medium fits (adjacent topic; partial beat overlap)
- 10 weak/no fits (different beat; no recent topical work)

**For the Haiku tier:**
- 10 pairs that should resolve to "high" without escalation
- 10 pairs that should resolve to "low/exploratory" without escalation
- 10 pairs that should escalate (genuinely borderline)
- Hard gate: Haiku correctly escalates ≥85% of borderline cases (false-confidence below 15%)
- Hard gate: Haiku correctly resolves clear cases ≥90% (false-escalation below 10%)

**For the Sonnet tier:**
- The 10 borderline cases above
- Hard gates: ≥85% confidence label accuracy; score within ±10 of ground truth midpoint ≥75%

**For the combined system:**
- All 30 pairs run through end-to-end
- Hard gate: top-1 confidence label matches expected ≥80%
- Hard gate: `why_they_matter` references at least one actual article from input list — 100%
- Hard gate: zero opt-out journalists EVER appear in ranking output — verified by SQL pre-filter; double-checked at prompt level as defense in depth

## Anti-patterns this prompt is designed to avoid

1. **Inflated scores for everyone.** The temptation is to score charitably. The prompt explicitly anchors low-end scores.
2. **Citing journalist bios as fit signals.** A journalist's bio is self-promotion. Recent bylines are evidence. The prompt restricts claims to bylines.
3. **Confidence inflation.** "High confidence" should be hard to earn. The prompt distinguishes confidence from raw score.
4. **Missing the saturation problem.** A journalist who just covered the same story for a competitor is high-topic-fit but low-likelihood-to-cover-again. The `risks` field captures this.

## Known limitations

- Haiku's calibration on the lower end (0-45) is less reliable than its high-end. The system mitigates by escalating anything in 45-80; if Haiku underscores a real fit at 40, it stays unescalated. Acceptable — those candidates were unlikely to be sent to anyway, and the user can manually add them.
- The escalation rate may drift as the journalist database grows and patterns shift. Monitor monthly; if escalation drops below 15% (Haiku is over-confident) or rises above 50% (Haiku is under-confident), retune.
- Batch mode adds 0-30 minutes of latency. For users who want synchronous ranking (rare), a non-batch path is available at full price.
