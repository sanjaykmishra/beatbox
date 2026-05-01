---
version: pitch_tone_analysis_v1.0
model: claude-sonnet-4
temperature: 0.2
max_tokens: 500
expected_output_format: json
prompt_cache:
  - tone_analysis_instructions_block
cache:
  high_frequency: per_author_30d   # >10 articles in trailing 90d
  mid_frequency: per_author_90d    # 3-10 articles
  low_frequency: per_author_on_demand  # <3 articles
---

# Pitch tone analysis prompt

Analyzes a journalist's recent articles to produce a tone descriptor used by the pitch drafting prompt. Cached per-author with frequency-tiered TTL.

This prompt ships at v1.0 with cost engineering built in. See `docs/18-cost-engineering.md`. The savings come from frequency-tiered cache (most journalists shift to quarterly or on-demand refresh) plus prompt-cached analysis instructions.

## Inputs

- `{{author_name}}`, `{{author_outlet}}`
- `{{author_recent_articles}}` — last 90 days, formatted with headline + lede + representative paragraphs (~5,000 tokens of source material)

## Prompt

```
[CACHED — tone_analysis_instructions_block]
Read these articles by the journalist. Identify their writing patterns —
tone, structure, length preferences, what catches their attention, and
how they treat sources. The output is used to help craft pitches that
match how this journalist writes.

You are NOT writing a pitch. You are NOT predicting what they want to
cover. You are characterizing their written voice based on the evidence.

Output schema:

{
  "tone_descriptors": ["string"],
        // 3-6 short tags, e.g. "skeptical-but-fair", "data-forward",
        // "industry-jargon-light", "human-interest-driven", "direct-prose",
        // "investigative", "analyst-voice", "trade-pub-formal"

  "structural_preferences": {
    "typical_lede_style": "string — e.g. 'opens with a specific company or person doing something specific', 'leads with a data point', 'starts with the implications'",
    "typical_length": "string — e.g. 'short, ~400 words' / 'medium, 800-1200' / 'long-form, 1500+'",
    "uses_quotes_heavily": "boolean",
    "data_orientation": "high | medium | low"
  },

  "what_seems_to_get_their_attention": ["string"],
        // 2-4 patterns extracted from the articles. e.g. "concrete deals
        // with named parties", "regulatory inflection points", "specific
        // dollar figures attached to outcomes". Don't editorialize; report.

  "what_to_avoid_in_a_pitch": ["string"],
        // 2-4 patterns where the journalist's writing suggests they would
        // not respond well. e.g. "vague language; this writer wants
        // specifics", "buzzword-heavy framing".

  "pitch_length_preference": "very short (≤100 words) | short (100-200) | medium (200-400) | long (400+)",
        // Inferred from how the journalist writes — short-form writers
        // typically reward short pitches; analyst-voice writers tolerate
        // longer pitches.

  "examples_of_good_opening_lines": ["string"],
        // 2-3 examples of opening lines that would suit THIS journalist's
        // style. Generic enough to apply to many topics; specific enough
        // to be useful as inspiration.

  "confidence_in_analysis": "high | medium | low",
        // High = many representative articles, clear patterns.
        // Medium = enough articles, mixed patterns.
        // Low = few articles or wildly varied output.

  "rationale": "string — 2-3 sentences explaining the descriptors. Reference specific articles by headline.",

  "analysis_date": "ISO 8601 date — for cache freshness checks downstream"
}

Constraints:
- Every characterization must come from the supplied articles. Do not pull
  from general knowledge of the outlet or the journalist's reputation.
- If the articles are too few or too varied to characterize confidently,
  set confidence_in_analysis to "low" and be honest in the rationale.
- Do NOT make claims about the journalist's personal life, beliefs, or
  anything outside their professional written output.
[/CACHED]

[NOT CACHED — per-author]
Articles by {{author_name}} at {{author_outlet}}:
---
{{author_recent_articles}}
---

Return ONLY the JSON.
```

## Cache tier logic

The cache lookup runs as part of pitch drafting orchestration:

```
function get_tone_descriptor(author_id):
  count = count_articles_in_trailing_90d(author_id)

  if count > 10:
    cache_ttl_days = 30
  elif count >= 3:
    cache_ttl_days = 90
  else:
    cache_ttl_days = inf  # on-demand only

  cached = lookup_cache(author_id, max_age_days=cache_ttl_days)
  if cached:
    return cached

  if count == 0:
    return null  # no articles to analyze
  else:
    descriptor = run_tone_analysis_prompt(author_id)
    write_cache(author_id, descriptor)
    return descriptor
```

For low-frequency journalists, the lookup falls through to a fresh analysis only when pitch drafting actually requests the descriptor — meaning the analysis runs at most once per pitch event, not on a refresh schedule.

## Design notes

- **Why this is its own prompt (not folded into pitch drafting).** Three reasons: caching (tone analysis is stable; pitch drafting is per-target), cost (drafting with full article context every time is expensive), and auditability (the user can review the inferred tone before any pitch is generated).
- **Why monthly refresh for active journalists.** Their tone shifts measurably as they cover new beats. Quarterly is too stale for active writers.
- **Why quarterly for mid-frequency.** Their voice is more stable; the marginal benefit of monthly refresh doesn't justify the cost.
- **Why on-demand for low-frequency.** Most journalists in any database fall here; running monthly analysis on someone who publishes twice a year is wasteful.
- **What the descriptor doesn't include.** Topics the journalist might cover next (that's the ranking prompt's job); personal preferences inferred from outside-of-bylines signals (no social-media analysis, no biography mining); predictive claims (the descriptor is descriptive, not predictive).

## Eval set

20 journalists with hand-labeled descriptors:

- 5 with strong, distinctive voices (analyst columnists, opinionated trade writers)
- 5 with neutral wire-service style
- 5 mid-spectrum (most general assignment journalists)
- 5 with sparse output (testing low-confidence handling)

Plus 5 stale-descriptor cases — verify that a 90-day-old descriptor on a mid-frequency journalist remains usable downstream.

Hard gates:
- Schema compliance: 100%
- Confidence-level accuracy: ≥90%
- Zero claims grounded in non-byline information (LLM-as-judge)
- Zero personal-life references (regex + judge)
- Stale descriptors maintain pitch-drafting quality on the eval set

## Known limitations

- A journalist who publishes a burst of unusual content (3 articles in a week on a new beat) gets the same descriptor as before for up to 90 days under mid-frequency. If the new beat matters for an active pitch, the user can force a refresh from the journalist profile drawer.
- One journalist working at multiple outlets gets a single global descriptor; we don't yet differentiate "her tone at TechCrunch vs. her tone in her Substack." Phase 4+ enhancement.
- Prompt cache TTL (Anthropic side) is shorter than the descriptor cache TTL (Beat side). Prompt cache savings only accrue across rapid-succession analysis runs, which happen mostly at the start of a campaign.
