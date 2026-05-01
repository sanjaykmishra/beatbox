---
version: journalist_ranking_v1.0
model: claude-sonnet-4
temperature: 0.1
max_tokens: 600
expected_output_format: json
---

# Journalist ranking prompt

Per-candidate scoring for a single (campaign, journalist) pair. Run many times per campaign — Sonnet for cost.

The deterministic SQL pre-filter narrows the candidate pool first (matching topics, recent activity, not opted-out, not on do_not_pitch list). This prompt then evaluates each survivor.

## Inputs

- Strategy fields from `campaigns.strategy_structured`:
  - `{{key_narratives}}`, `{{topics}}`, `{{news_hooks}}`, `{{target_audiences}}`, `{{angles_per_audience}}`
- Journalist fields from `authors` + recent extracts:
  - `{{author_name}}`, `{{author_outlet}}`, `{{author_outlet_tier}}`, `{{author_beats}}`
  - `{{author_recent_articles}}` — last 90 days, formatted as: `"YYYY-MM-DD | Outlet | Headline | 1-line summary"` (5–15 lines)
  - `{{author_pitch_response_rate_global}}` — anonymized cross-customer reply rate, or null if insufficient data
  - `{{author_pitch_response_rate_workspace}}` — workspace-specific rate, or null if no prior pitches
- Workspace context:
  - `{{workspace_prior_coverage_with_author}}` — has the agency previously gotten coverage from this journalist for this client? Format: count + recency
  - `{{client_competitive_context}}` — has this journalist recently covered the client's competitors? Format: count of competitor pieces in last 30 days

## Prompt

```
You are evaluating whether a journalist is a good fit for a specific PR
campaign. You will receive a campaign strategy and a journalist's recent
work. Score the fit honestly — including weak fits as weak.

Campaign strategy:

Key narratives: {{key_narratives}}
Topics: {{topics}}
News hooks: {{news_hooks}}
Target audiences: {{target_audiences}}
Angles per audience: {{angles_per_audience}}

Journalist:

Name: {{author_name}}
Outlet: {{author_outlet}} (Tier {{author_outlet_tier}})
Stated beats: {{author_beats}}
Recent articles (last 90 days):
{{author_recent_articles}}

Track record signals:
- Global response rate to pitches (across all PR users, anonymized): {{author_pitch_response_rate_global}}
- Response rate to this agency's prior pitches: {{author_pitch_response_rate_workspace}}
- Prior coverage of this client by this journalist: {{workspace_prior_coverage_with_author}}
- Recent coverage of competitors of this client: {{client_competitive_context}}

Score the fit. Return JSON:

{
  "score": 0.0–100.0,
  "confidence": "high | medium | low | exploratory",
  "score_breakdown": {
    "topic_alignment": 0.0–25.0,
    "beat_fit": 0.0–20.0,
    "recency": 0.0–15.0,
    "audience_reach": 0.0–10.0,
    "responsiveness": 0.0–15.0,
    "client_history": 0.0–10.0,
    "competitive_signal": 0.0–5.0
  },
  "why_they_matter": "string — 2–3 sentences explaining why this journalist is or isn't a fit. Reference specific recent articles by headline. No claims about the journalist's interests beyond what their recent work demonstrates.",
  "risks": "string or null — anything to be careful about (e.g., 'recently covered a competitor's funding negatively', 'beat appears to have shifted')"
}

Scoring guidance:

- 80–100 (high confidence): the journalist's recent work directly aligns with at least one key narrative; their beat covers the topics; they're at an outlet that reaches the target audience; the strategy's news hooks match how they frame stories.
- 60–79 (medium confidence): clear topical fit but some signal is missing — beat is adjacent rather than exact; or recent work is on the topic but at a different angle; or response rate signals are unknown/weak.
- 40–59 (low confidence): possible fit but multiple weak signals — outlet doesn't perfectly match the target audience; beat is broader than the campaign's topic; recent work is sparse on the relevant topics.
- 20–39 (exploratory): worth knowing exists but probably not a primary target. Outlet or beat is in adjacent territory.
- 0–19: poor fit. The pre-filter should have eliminated most of these; if you're scoring this low, explain.

Confidence labels are NOT just score ranges — they reflect signal quality. A medium score with strong signals on every dimension might be high confidence. A high score driven by one strong signal and several missing signals is medium confidence.

Constraints:
- Every claim in why_they_matter must reference an actual article from the recent_articles list. Do not infer interests not visible in their bylines.
- If the journalist has covered a competitor recently and that piece was negative, raise risks. If positive, that's a fit signal AND a saturation risk (they may not want to write the same story twice).
- If you don't have enough information to score, set confidence to "low" and explain in why_they_matter what's missing.
- Do NOT invent recent articles, beat tags, or response rate data.

Return ONLY the JSON.
```

## Design notes

### Why Sonnet, not Opus

Volume. A campaign might rank 200 candidates. Opus pricing across 200 calls is significant. Sonnet handles structured judgment with citations to source material well; it's the right tool here.

### Why temperature 0.1

Ranking is a judgment task. We want consistency across re-runs of the same candidate, and we want the same journalist to score similarly across similar campaigns. Higher temperatures introduce noise that hurts model trust.

### Why score_breakdown

Two reasons:
1. The user can audit why a journalist scored what they did. Without breakdown, the score is opaque.
2. We can later tune the model by adjusting weights. If `responsiveness` is over-weighted relative to `topic_alignment` (we saw lots of high-response-rate-but-wrong-beat journalists ranking too high), we adjust without re-prompting.

### Why explicit confidence labels

Score is a number; confidence is meta about the score. A 65 score with strong signals across every dimension is more trustworthy than a 65 score with one strong signal and four missing. Confidence captures that distinction. The UI uses confidence for visual treatment; users learn to trust the labels over time.

### Why source-grounding is hard-required

The single biggest risk is the model claiming a journalist covers a topic they don't actually cover. Requiring every claim in `why_they_matter` to reference an article from the input list closes the gap. The eval set verifies this.

## Eval set

30 hand-labeled (strategy, journalist) pairs:

- 10 strong fits (recent articles directly on topic; beat aligned)
- 10 medium fits (adjacent topic; partial beat overlap)
- 10 weak/no fits (different beat; no recent topical work)

Hand-labeled ground truth: confidence label + acceptable score range + must-include facts in `why_they_matter` (specific article headlines).

Hard gates:
- Top-1 confidence label matches ground truth ≥ 80%
- Score within ±10 of ground truth midpoint ≥ 70%
- `why_they_matter` references at least one actual article from the input list (not fabricated) — 100%
- Zero opt-out journalists EVER appear in ranking output — verified by the SQL pre-filter, but tested at the prompt level too as defense in depth (input filtering should never reach the prompt with an opted-out journalist; if it ever does, the prompt should refuse)

## Anti-patterns this prompt is designed to avoid

1. **Inflated scores for everyone.** The temptation is to score charitably. The prompt explicitly anchors low-end scores and provides examples of when each level applies.
2. **Citing journalist bios as fit signals.** A journalist's bio is self-promotion. Recent bylines are evidence. The prompt restricts claims to bylines.
3. **Confidence inflation.** "High confidence" should be hard to earn. The prompt distinguishes confidence from raw score.
4. **Missing the saturation problem.** A journalist who just covered the same story for a competitor is a high-topic-fit, low-likelihood-to-cover-again candidate. The `risks` field captures this.

## Known limitations

- Recent-articles parsing relies on the journalist database being fresh. Stale data → stale scoring. Phase 3's monthly enrichment job is the mitigation.
- The model has no visibility into the journalist's email read habits or current bandwidth. A great fit who's drowning in pitches still won't reply.
- Cross-customer aggregate response rates can be misleading — a journalist who reliably ignores low-quality pitches but engages with high-quality ones shows a low overall rate that under-sells them. The `why_they_matter` rationale tries to mitigate but doesn't fully solve.
