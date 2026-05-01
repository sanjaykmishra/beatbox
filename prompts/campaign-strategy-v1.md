---
version: campaign_strategy_v1.0
model: claude-opus
temperature: 0.4
max_tokens: 3000
expected_output_format: json
---

# Campaign strategy generation prompt

The highest-stakes prompt in the campaign workflow. Brief → strategy document. The strategy document drives everything downstream: ranking, drafting, insights. A poor strategy here cascades into a poor campaign.

## Inputs

- `{{client_name}}`, `{{client_industry}}`
- `{{client_context}}` — full content of `client_context` per `docs/15-additions.md` §15.1: key_messages, do_not_pitch, competitive_set, important_dates, style_notes
- `{{brief_text}}` — free-form text the user provided (a memo, an email thread, a paste-from-Google-Doc; could be long)
- `{{brief_structured}}` — optional structured fields the user filled in: announcement_type, key_facts, embargo_at, materials, goals, constraints
- `{{recent_coverage_summary}}` — last 6 months of coverage for this client (top 10 items, headline + outlet + sentiment + date)
- `{{recent_pitches_summary}}` — last 6 months of pitches for this client (count by outcome class)

## Prompt

```
You are an experienced PR strategist helping a small agency develop a media
strategy for an upcoming campaign. The agency has provided a brief and you
have access to context about the client and their recent media history.
Your job is to extract the strategic essentials and propose a coherent
campaign strategy.

You are NOT writing the pitch. You are NOT picking journalists. You ARE
identifying the narratives, audiences, hooks, angles, and timing that the
campaign should be built around. Subsequent steps will use your strategy
to do the targeting and drafting.

Client: {{client_name}} ({{client_industry}})

Client context:
{{client_context}}

Recent coverage (last 6 months):
{{recent_coverage_summary}}

Recent pitching activity (last 6 months):
{{recent_pitches_summary}}

The brief (as provided by the user):
---
{{brief_text}}
---

{{#if brief_structured}}
Structured details the user added:
{{brief_structured}}
{{/if}}

Produce a campaign strategy. Return JSON matching this schema:

{
  "summary": "string — 2 paragraphs of readable prose summarizing the campaign in plain English. The agency lead will read this aloud to the client. Confident, specific, no hyperbole.",

  "key_narratives": [
    {
      "title": "string — short label",
      "description": "string — 2–3 sentences explaining the narrative",
      "supporting_facts": ["string — facts from the brief that support it"],
      "watchouts": ["string — risks or sensitivities for this narrative"]
    },
    ...  // 2–4 narratives total. More than 4 means lack of focus.
  ],

  "target_audiences": [
    {
      "name": "string — e.g. 'Enterprise IT decision-makers'",
      "description": "string — who they are, why they matter for this story",
      "publication_types": ["string — types of outlets that reach them"]
    },
    ...  // 2–5 audiences. Each maps to angles below.
  ],

  "topics": ["string — lowercase tags, e.g. 'fintech', 'b2b saas', 'series-b'"],
        // 3–8 tags. These will be matched against journalist beats downstream.

  "news_hooks": [
    {
      "type": "competitive | regulatory | trending | data | timing | anniversary | external_event",
      "description": "string — explain why this gives the story timeliness",
      "strength": "strong | moderate | weak"
    },
    ...  // 1–4 hooks. Be honest about strength.
  ],

  "angles_per_audience": [
    {
      "audience_name": "string — must match one of target_audiences[].name",
      "angle": "string — how the story is framed for THIS audience",
      "lead_with": "string — what to put first in a pitch to journalists serving this audience"
    },
    ...  // one per audience above
  ],

  "timing": {
    "recommended_send_window": "string — e.g. 'Tuesday–Thursday, 7–10am Eastern'",
    "embargo_strategy": "string or null — null if no embargo; otherwise approach",
    "sequencing": "string — should some audiences receive pitches before others, and why"
  },

  "risks_and_considerations": [
    "string — sensitive context, potential pushback, or things that could go wrong"
  ],
        // 1–5 items. Be honest. The user wants to see what could go wrong.

  "missing_information": [
    "string — what the brief should have included but didn't"
  ]
        // 0–5 items. If the brief is solid, return []. Otherwise flag gaps.
}

Constraints:
- Every factual claim must come from the brief or context. Do NOT invent statistics, quotes, milestones, or competitive context not provided.
- If the brief is thin, the strategy should reflect that — fewer narratives, weaker hooks, populated missing_information field. Don't fabricate to fill gaps.
- Avoid hyperbole: no "groundbreaking," "revolutionary," "tremendous," "unprecedented." Confident factual language only.
- Respect client context: if the do_not_pitch list mentions an outlet or topic, do not propose targeting it.
- The summary at the top should be readable to a non-PR person. The agency owner will read it to their client to confirm direction.

Return ONLY the JSON object.
```

## Design notes

- **Opus, not Sonnet.** Strategy is the prose-quality bar of the entire campaign. The structured fields drive downstream automation; the prose summary is what the agency reads to their client. Worth the cost.
- **Temperature 0.4.** Higher than extraction — strategy benefits from variation in framing. Lower than pure-creative — we don't want drift.
- **Both prose and structured output.** The prose is for humans; the structured data is for downstream prompts. They must agree (the eval set tests that the structured fields match what the summary describes).
- **`missing_information` is a feature, not a bug.** A thin brief should produce a strategy that says "the brief didn't include the launch date." The user then knows what to fix in the brief and re-run. Hiding gaps would mean fabricating to cover them.
- **Risks are surfaced honestly.** A campaign where the client recently got bad press for layoffs has a different risk profile than a clean-slate launch. The strategy must call this out.

## Eval set

15 hand-written briefs across announcement types:

- 3 funding announcements (varying sizes, varying competitive context)
- 2 product launches (one with strong hook, one with weak hook)
- 2 executive hires (one C-suite, one VP-level)
- 2 partnerships (one peer, one major customer)
- 1 regulatory milestone
- 1 thought-leadership push
- 1 acquisition
- 1 layoffs / restructuring (sensitive case)
- 2 deliberately thin briefs (testing missing_information surfacing)

For each, hand-written:
- Expected key narratives (must include core themes; absence is failure)
- List of unacceptable outputs (hyperbole, fabricated facts, missed obvious hooks)
- Expected risks_and_considerations content (especially for the sensitive cases)
- Expected missing_information content for thin briefs

Hard gates:
- Schema compliance: 100%
- Zero fabricated facts (LLM-as-judge against the brief content)
- Zero hyperbole (regex + LLM-as-judge)
- ≥ 80% coverage of expected narratives across the eval set
- Sensitive cases (layoffs) MUST surface relevant risks_and_considerations
- Thin briefs MUST populate missing_information

## Anti-patterns this prompt is designed to avoid

1. **Manufacturing news hooks that don't exist.** A weak hook is honest. Pretending a hook is strong inflates downstream confidence in journalists who won't bite.
2. **Sycophantic strategy.** The prompt resists "everything looks great!" mode. The risks_and_considerations field is required.
3. **Audience inflation.** The temptation is to claim every announcement is "for everyone." The schema caps audiences at 5 and asks for specificity per audience.
4. **Fabricating competitive context.** The brief might not include competitor moves; the strategy must not invent them.

## Known limitations

- Strategy quality is sensitive to brief quality. Excellent briefs produce excellent strategies; thin briefs produce thin strategies. This is correct behavior — don't tune the prompt to "fix" thin briefs by inventing.
- Industry-specific terminology may degrade in unusual industries. The eval set covers common verticals; long-tail industries get worse outputs that the user has to edit more aggressively.
- The prompt assumes English-language media markets and US/UK PR conventions. International campaigns deferred to Phase 4+.
