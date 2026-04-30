---
version: pitch_attribution_v1.0
model: claude-sonnet-4
temperature: 0.1
max_tokens: 600
expected_output_format: json
---

# Pitch attribution prompt

Given a piece of coverage and a list of candidate pitches that might have led to it, rank the candidates and identify the most likely one. Run when a new coverage_item is extracted and at least one candidate pitch exists.

## When this runs

The attribution worker pre-filters candidates with a deterministic SQL query:

- Same client_id as the coverage
- Pitch recipient's author matches the coverage's author (same author_id), OR pitch recipient email matches journalist's known email
- Pitch sent within 30 days before the coverage publish date
- Recipient status was `replied` or `interested` (or `sent` if no reply tracking)

If 0 candidates: skip — no LLM call.
If 1 candidate: surface as suggested without LLM (high prior).
If 2+ candidates: run this prompt to rank.

## Inputs

- `{{coverage_headline}}`, `{{coverage_lede}}`, `{{coverage_summary}}`, `{{coverage_key_quote}}`, `{{coverage_outlet}}`, `{{coverage_publish_date}}`
- `{{candidates}}` — array of objects, each with: `id`, `subject`, `body_summary` (first 500 chars), `sent_at`, `reply_classification` (or null), `reply_excerpt` (first 200 chars or null)

## Prompt

```
A piece of press coverage just landed for a client. Below is the coverage and
several pitches the agency sent that could plausibly have led to it. Your
job is to identify which pitch (if any) most likely caused this coverage,
and explain why.

Coverage:
- Outlet: {{coverage_outlet}}
- Date: {{coverage_publish_date}}
- Headline: {{coverage_headline}}
- Lede: {{coverage_lede}}
- Summary: {{coverage_summary}}
- Key quote: {{coverage_key_quote}}

Candidate pitches:
{{candidates}}
(formatted as: "Pitch [id] sent [days_ago]d ago: subject — body excerpt — reply: [classification, excerpt]")

Score each candidate from 0.0 to 1.0 on likelihood of causing this coverage.
Consider:
- Topic alignment between pitch and coverage
- Whether the journalist's reply (if any) signaled interest
- Recency: pitches closer to the publication date are slightly more likely
- Specificity: a pitch about exactly this story is much more likely than a
  pitch about an adjacent topic

Be conservative. If the topic match is weak, scores should be low across
the board. Do NOT default to picking a winner — coverage often arrives
from sources outside our pitching (organic mentions, competitor pieces,
journalist's own initiative).

Return JSON:
{
  "ranked_candidates": [
    {
      "pitch_id": "...",
      "score": 0.0–1.0,
      "rationale": "one or two sentences"
    },
    ...
  ],
  "top_candidate_id": "..." | null,
  "top_candidate_confidence": "high" | "medium" | "low" | "none",
  "summary": "one short paragraph explaining your overall conclusion"
}

Use top_candidate_confidence = "high" only if the score is ≥ 0.75 AND the
margin to second place is ≥ 0.20. Otherwise "medium" or "low".
Use "none" if no candidate scored above 0.40.

Return ONLY the JSON.
```

## Design notes

- **Conservative bias is intentional.** False positive attributions are worse than false negatives. Suggesting a wrong pitch as the cause makes the agency look bad in their own reports.
- The `top_candidate_confidence` thresholds are deliberately strict so the UI can apply different visual treatment by confidence:
  - `high` → "✓ Likely caused by this pitch" — green badge, suggest auto-confirm
  - `medium` → "Possible match" — yellow, requires user confirmation
  - `low` → "Weak match" — gray, hide unless user opens detail view
  - `none` → don't surface anything; no candidate is worth the user's attention
- We deliberately don't pass the full pitch body — the summary is enough. Long pitches can dilute relevance signals.
- Reply classification is a strong signal. A pitch that got `interested` reply two weeks before the coverage is much more likely than a pitch that got `declined`.

## Eval set

30 hand-labeled pitch→coverage pairs in the eval harness:

- 15 true positives (verified by the founder calling the agency that placed the story)
- 10 true negatives (coverage that came from sources other than our pitches — organic, competitor pieces, journalist initiative)
- 5 hard cases (multiple plausible pitches; only one is correct)

Hard gates:
- Top-1 attribution accuracy ≥ 80% on true positives.
- False positive rate (claiming a pitch caused coverage when it didn't) < 5%.
- "high" confidence claims must be at least 90% accurate (we surface these prominently, so accuracy matters more than recall).

## Known limitations

- Multi-touch attribution: a journalist may have been pitched, declined, then later picked up the story after seeing it elsewhere. We attribute to the pitch but the causal chain is murkier.
- Co-pitching: when the agency and the company's internal comms both pitch the same journalist, we only see our side.
- Long-form features: a Wired feature might draw on pitches months apart, with no single pitch as the cause. The model should output low confidence here, which is correct.
