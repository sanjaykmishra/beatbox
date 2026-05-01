---
version: pitch_draft_v1.0
model: dynamic  # routed by confidence tier from journalist-ranking output
temperature: 0.5
max_tokens: dynamic  # 1500 high-conf, 1200 medium, 900 low-conf short variant
expected_output_format: json
batch_mode: true
prompt_cache:
  - campaign_strategy_block
  - drafting_instructions_block
  - brand_voice_block
routing:
  high_confidence:    # ranking score >= 80
    model: claude-opus-4
    pitch_target_words: 150-220
  medium_confidence:  # ranking score 60-79
    model: claude-sonnet-4
    pitch_target_words: 130-180
  low_confidence:     # ranking score 40-59
    model: claude-sonnet-4
    pitch_target_words: 100-130
---

# Pitch drafting prompt

Generates a personalized pitch for a single (campaign, journalist) pair. Run once per accepted target after the user has reviewed the target list.

This prompt's output is the highest-stakes content in the entire campaign workflow — it's what the journalist receives. A bad pitch damages the agency's relationship with the journalist for future stories, not just this one. Hallucinations are catastrophic.

This prompt ships at v1.0 with cost engineering built in. See `docs/18-cost-engineering.md`. At full Phase 3 volumes (50 pitches × 5 campaigns/month), pitch drafting was the second-highest-cost AI surface; the cost-engineered design reduces it from a $45-75/month projection to $12-20/month.

## How the cost engineering applies here

Four layers of savings stack:

1. **Confidence-tiered model selection.** This is the central change from naive "Opus everywhere." High-confidence targets (where the journalist is most likely to engage and pitch quality matters most) get Opus. Medium-confidence get Sonnet. Low-confidence get Sonnet with a shorter pitch — they're long-shots and a tighter pitch respects the journalist's time.

2. **Prompt-cached campaign-shared context.** Across the 50 pitches in one campaign, the campaign strategy, brand-voice notes, and structural drafting instructions are identical. The first pitch pays full input cost; the remaining 49 reuse the cached portion. ~60-70% input-token reduction.

3. **Trimmed journalist context.** Top 3-5 most-relevant articles (selected by embedding similarity to campaign topics) plus the cached tone descriptor. The tone descriptor already encodes the patterns; the articles provide grounding for specific references.

4. **Batch mode.** Drafting is user-async ("draft 50 pitches; I'll review them when ready"). 50% discount.

The most important non-cost discipline: every claim about the journalist's work must be grounded in articles passed to the prompt. The eval set tests this aggressively across all three tiers.

## Inputs

- Campaign:
  - `{{client_name}}`, `{{client_industry}}`, `{{client_context_style_notes}}`
  - `{{campaign_name}}`, `{{campaign_summary}}`
  - `{{key_narratives}}`, `{{news_hooks}}`
  - `{{embargo_at}}` — optional
- Journalist:
  - `{{author_name}}`, `{{author_outlet}}`
  - `{{author_recent_articles}}` — top 3-5 most-relevant from cached enrichment
  - `{{author_tone_descriptor}}` — output of `pitch-tone-analysis-v1`
- Target metadata from ranking:
  - `{{ranking_confidence}}` — used by orchestration to route to the correct model variant
  - `{{matched_audience_name}}`, `{{matched_angle}}`, `{{why_they_matter}}`
- Workspace context:
  - `{{prior_pitches_to_this_journalist}}` — last 12 months, max 3 most recent
  - `{{agency_signature_template}}` — optional
  - `{{pitch_target_words}}` — supplied by orchestration based on routing tier

## Prompt — high-confidence tier (Opus, target 150-220 words)

This tier is for targets ranked 80+ by the ranking step.

```
[CACHED — drafting_instructions_block]
You are drafting a single PR pitch from a small agency to a specific
journalist about a specific campaign. The pitch is personal and grounded
in the journalist's recent work. It is honest about what's being announced
and respects the journalist's time.

You are NOT writing marketing copy. You are NOT promoting. You are
alerting a working journalist to a story that fits their beat, in
language matching their style, with a clear ask.

Output schema:
{
  "subject": "string, max 70 chars, specific and factual",
  "alternate_subjects": ["string", "string"],
  "body": "string, plain text with \\n\\n paragraph breaks",
  "why_this_pitch": "string, 2-3 sentences for user only",
  "confidence": "high | medium | low",
  "suggested_followup_at": "ISO date 5-7 days from send"
}

CRITICAL CONSTRAINTS — these apply to every pitch:
- Every claim about the journalist's work references an actual article
  from author_recent_articles. NEVER fabricate a headline.
- NO personal-life references. Professional output only.
- NO fawning. NO "your work is great." Specific references only.
- NO hyperbole about the client: no "groundbreaking", "revolutionary",
  "industry-leading", "unprecedented".
- NO hallucinated quotes. If the news involves an exec quote and the
  brief did not provide one, do not invent. Reference materials being
  available instead.
- Respect embargoes. State the embargo before any leakable details.
- Use client style notes for preferred names ("Mike" not "Michael").
- The pitch is from the agency to the journalist. "We" is the agency.

The pitch structure:
1. Specific, grounded opening referencing journalist's recent work
2. The news, stated plainly, why it matters for THIS beat
3. The hook — why now
4. The ask — briefing, embargo, exclusive, comment, materials
5. Brief sign-off
[/CACHED]

[CACHED — campaign_strategy_block]
Campaign:
- Client: {{client_name}} ({{client_industry}})
- Campaign: {{campaign_name}}
- Summary: {{campaign_summary}}
- Key narratives:
  {{key_narratives}}
- News hooks:
  {{news_hooks}}
{{#if embargo_at}}
- Embargo: This story is under embargo until {{embargo_at}}.
{{/if}}
{{#if client_context_style_notes}}
Style notes for {{client_name}}: {{client_context_style_notes}}
{{/if}}
{{#if agency_signature_template}}
Agency signature: {{agency_signature_template}}
{{/if}}
[/CACHED]

[NOT CACHED — per-pitch]
This pitch:
- Journalist: {{author_name}} at {{author_outlet}}
- Their recent articles (top 3-5 most relevant):
{{author_recent_articles}}
- Their writing style: {{author_tone_descriptor}}
- The angle for this journalist: {{matched_angle}}
- Why they were chosen: {{why_they_matter}}
{{#if prior_pitches_to_this_journalist}}
- Prior interactions:
{{prior_pitches_to_this_journalist}}
(Avoid repeating yourself or referencing things they already declined.)
{{/if}}

Target length: {{pitch_target_words}} words. Match the journalist's
preferred length from the tone descriptor.

Return ONLY the JSON.
```

## Prompt — medium-confidence tier (Sonnet, target 130-180 words)

Identical structure to the high-confidence tier with two differences: model is Sonnet, target word count is 130-180. The prompt text is byte-for-byte the same as high-confidence so cached blocks are reusable across tiers (cache key is the prompt content, not the model).

## Prompt — low-confidence tier (Sonnet, target 100-130 words, short variant)

This tier is for targets ranked 40-59. Same prompt as medium tier with one additional instruction:

```
NOTE FOR THIS PITCH: This is a shorter variant. Cut the news hook
section if it would push the pitch past 130 words. Keep the grounded
opening, the news, and the ask; drop framing prose.
```

The shorter pitch lands more reliably with low-fit journalists who would otherwise skim past 200-word pitches.

## Why we keep Opus at all

Two reasons. First, high-confidence targets are by definition the journalists most likely to reply. Expected value of a well-crafted pitch is highest there; Opus's marginal quality matters most where the response rate is highest. Second, keeping Opus in the system preserves the option to migrate other tiers back if Sonnet quality drops on subsequent model versions or if eval results show regression. Opus stays as the quality anchor.

When Sonnet 4.7 or 5 ships, the tiers should be re-evaluated. If Sonnet matches Opus on the high-confidence eval set, Opus drops out entirely.

## Orchestration

The drafting endpoint receives the accepted target list with `confidence` labels from the ranking step:

```
1. Group targets by confidence tier.
2. For each tier:
   a. Build prompts with cached blocks (campaign_strategy, drafting_instructions).
   b. Submit to Batches API with the appropriate model.
3. When batches return, merge into campaign_pitches table including drafting_model_tier.
```

Wall-clock time for a 50-pitch campaign: 5-15 minutes. Progress reported per-tier so the user sees high-confidence drafts first.

## Eval set

25 (campaign, journalist, expected pitch) examples covering all three tiers plus adversarial cases:

- 10 strong-fit cases (Opus tier) — outputs match v0 quality
- 10 medium-fit cases (Sonnet tier) — side-by-side with v0 Opus on these cases shows ≥80% indistinguishable
- 5 low-fit cases (Sonnet-short tier) — within length range, hard gates pass
- 3 cases with prior pitch history (testing avoidance of repetition)
- 3 cases with embargo (testing embargo handling)
- 2 cases with sensitive topics (layoffs, executive change) — testing tone calibration
- 2 adversarial cases: a journalist's bio mentions a personal detail; the eval verifies the pitch doesn't reference it

Hard gates across all tiers:

- Schema compliance: 100%
- Zero hallucinated claims about the journalist's work (LLM-as-judge: every claim grounded in input articles)
- Zero personal-life references (regex on common patterns + LLM-as-judge)
- Zero hyperbole about the client (regex against forbidden word list)
- Zero invented quotes (LLM-as-judge: every quoted phrase appears verbatim in input materials, OR no quotes at all)
- Embargo cases: 100% mention the embargo correctly
- Length within ±20% of the target word count

## Anti-patterns this prompt is designed to avoid

1. **Fawning openings.** "Your work has been a longtime inspiration." → instant trust kill.
2. **Generic-sounding pitches.** "I think you'd be interested in our client's news." → no, you don't think; show the work.
3. **Hyperbole inflation.** Marketing-speak from the client brief leaks into the pitch.
4. **Buried lede.** The news is in paragraph 4 because the model wanted to set context. Journalists scan; the news goes early.
5. **Vague asks.** "Let me know if you'd like to chat." → no clear next step.
6. **Personal-life references.** Already covered.
7. **Invented quotes from the client.** Already covered.
8. **Repeating prior pitches verbatim** when prior_pitches is populated.

## Known limitations

- The confidence tier from ranking is itself a model output and inherits ranking's noise. A target ranked 79 (medium) is close to a target ranked 81 (high); the boundary is somewhat arbitrary. Where the boundary actually matters and the user disagrees, they can manually flag a target as "high priority" and force Opus drafting.
- Prompt-cached blocks have a TTL. Long campaign drafting runs may straddle TTL boundaries; the system handles this automatically by re-establishing cache. Practical impact: small.
- Subject lines are still the weakest link. Even with alternates, all three sometimes sound similar across a campaign. May warrant a separate, specialized subject-line prompt in v1.1.
- Length adherence is approximate. Tone descriptors aren't always perfectly calibrated to actual ideal pitch length for a given journalist. Refine via outcome data over time.
