---
version: pitch_tone_analysis_v1.0
model: claude-sonnet-4
temperature: 0.2
max_tokens: 500
expected_output_format: json
cache: per_author_monthly
---

# Pitch tone analysis prompt

Analyzes a journalist's recent articles to produce a tone descriptor used by the pitch drafting prompt. **Cached per author**, refreshed monthly. Separated from drafting so we can amortize the cost across all pitches sent to a given journalist.

## Inputs

- `{{author_name}}`, `{{author_outlet}}`
- `{{author_recent_articles}}` — last 90 days, formatted: each article includes headline, lede (first 2 sentences), and 1–2 representative paragraphs from the body. Aim for ~5,000 tokens of source material.

## Prompt

```
Read these articles by {{author_name}} at {{author_outlet}}. Identify the
journalist's writing patterns — tone, structure, length preferences, what
catches their attention, and how they treat sources. The output will be
used to help craft pitches that match how this journalist writes.

You are NOT writing a pitch. You are NOT predicting what they want to cover.
You are characterizing their written voice based on the evidence.

Articles:
---
{{author_recent_articles}}
---

Return JSON:

{
  "tone_descriptors": ["string"],
        // 3–6 short tags, e.g. "skeptical-but-fair", "data-forward",
        // "industry-jargon-light", "human-interest-driven", "direct-prose",
        // "investigative", "analyst-voice", "trade-pub-formal"

  "structural_preferences": {
    "typical_lede_style": "string — e.g. 'opens with a specific company or person doing something specific', 'leads with a data point', 'starts with the implications'",
    "typical_length": "string — e.g. 'short, ~400 words' / 'medium, 800–1200' / 'long-form, 1500+'",
    "uses_quotes_heavily": "boolean",
    "data_orientation": "high | medium | low"
  },

  "what_seems_to_get_their_attention": ["string"],
        // 2–4 patterns extracted from the articles. e.g. "concrete deals
        // with named parties", "regulatory inflection points", "specific
        // dollar figures attached to outcomes". Don't editorialize; report.

  "what_to_avoid_in_a_pitch": ["string"],
        // 2–4 patterns where the journalist's writing suggests they would
        // not respond well. e.g. "vague language; this writer wants
        // specifics", "buzzword-heavy framing".

  "pitch_length_preference": "very short (≤100 words) | short (100–200) | medium (200–400) | long (400+)",
        // Inferred from how the journalist writes — short-form writers
        // typically reward short pitches; analyst-voice writers tolerate
        // longer pitches.

  "examples_of_good_opening_lines": ["string"],
        // 2–3 examples of opening lines that would suit THIS journalist's
        // style. Generic enough to apply to many topics; specific enough
        // to be useful as inspiration.

  "confidence_in_analysis": "high | medium | low",
        // High = many representative articles, clear patterns.
        // Medium = enough articles, mixed patterns.
        // Low = few articles or wildly varied output.

  "rationale": "string — 2–3 sentences explaining the descriptors. Reference specific articles by headline."
}

Constraints:
- Every characterization must come from the supplied articles. Do not pull from general knowledge of the outlet or the journalist's reputation.
- If the articles are too few or too varied to characterize confidently, set confidence_in_analysis to "low" and be honest in the rationale.
- Do NOT make claims about the journalist's personal life, beliefs, or anything outside their professional written output.

Return ONLY the JSON.
```

## Design notes

### Why this is its own prompt (not folded into pitch drafting)

Three reasons:

1. **Caching.** Tone analysis is stable over months. Pitch drafting is per-target, per-campaign. Separating lets us cache the slow-moving descriptor and re-run only the fast-moving draft.
2. **Cost.** Drafting a personalized pitch with full article context fed in every time is expensive. Drafting with a pre-computed tone descriptor is cheaper.
3. **Auditability.** The user can see what tone the system inferred for a journalist before any pitch is generated. Wrong tone → user fixes it before generating 50 pitches based on a flawed descriptor.

### Why monthly refresh

Journalists' tone shifts slowly. Beats can change quarterly, but the underlying voice is stable over 30 days. Monthly refresh balances freshness against cost. New high-priority pitches can trigger an on-demand refresh if a journalist's tone descriptor is unusually old.

### Why not just pass raw articles to the drafting prompt

We could. The reason we don't: drafting needs to focus on the campaign-specific work (matching narratives to angles, picking subject lines, getting the call-to-action right). Adding "extract tone from these 5,000 tokens of bylines" to the drafting prompt's job means it does both poorly. Specialization wins.

### What the descriptor doesn't include

- **What topics the journalist might cover next.** That's the ranking prompt's job.
- **Personal preferences inferred from outside-of-bylines signals.** No social media analysis, no biography mining, no LinkedIn scraping.
- **Predictive claims** ("this journalist would respond well to..."). The descriptor is descriptive, not predictive.

## Eval set

20 journalists with hand-labeled descriptors:

- 5 with strong, distinctive voices (analyst columnists, opinionated trade writers)
- 5 with neutral wire-service style
- 5 mid-spectrum (most general assignment journalists)
- 5 with sparse output (testing low-confidence handling)

Hand-labeled ground truth: acceptable tone_descriptors set (one of several valid characterizations is fine), structural preferences with tolerance ranges, expected confidence level.

Hard gates:
- Schema compliance: 100%
- Confidence-level accuracy: ≥ 90%
- Zero claims grounded in non-byline information (LLM-as-judge)
- Zero personal-life references (regex + judge)

## Known limitations

- Journalists who write across diverse topics produce mixed tone descriptors. The output flags this with "low confidence" but downstream consumers should still use the descriptor cautiously.
- Recent-articles freshness depends on the Phase 3 enrichment job. Stale source material → stale descriptors.
- One journalist working at multiple outlets gets a single global descriptor; we don't yet differentiate "her tone at TechCrunch vs. her tone in her Substack." Phase 4+ enhancement.
