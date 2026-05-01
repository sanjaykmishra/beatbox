---
version: campaign_insights_v1.0
model: claude-sonnet-4
temperature: 0.3
max_tokens: 2000
expected_output_format: json
prompt_cache:
  - insights_instructions_block
---

# Campaign insights prompt

Generates a post-campaign retrospective when the user closes a campaign (or 30 days after send window ends, whichever is first). Output is shown to the user as readable analysis they can act on.

This prompt ships at v1.0 with cost engineering built in. See `docs/18-cost-engineering.md`. The savings come from pre-aggregating per-pitch outcome data in SQL before the prompt sees it (the prompt reasons about patterns, not individual pitches), plus prompt-cached insights instructions.

## Inputs

- Campaign metadata: `{{campaign_name}}`, `{{client_name}}`, `{{send_window_start}}`, `{{send_window_end}}`
- Strategy summary: `{{strategy_summary}}`
- Aggregate outcomes:
  - `{{total_targets}}`, `{{total_pitches_sent}}`, `{{total_pitches_skipped}}`
  - `{{replies_count}}`, `{{replies_by_classification}}` — counts of `interested | declined | more_info | auto_reply | unrelated | unclear`
  - `{{coverage_count}}`, `{{coverage_attributed_to_pitches}}`
- `{{pitch_outcomes_aggregated}}` — pre-aggregated summary tables (replaces v0's per-pitch array):
  ```json
  {
    "reply_rate_by_subject_length": {
      "0-30 chars": {"sent": 12, "replied": 4, "rate": 0.33},
      "31-50 chars": {"sent": 18, "replied": 6, "rate": 0.33},
      "51+ chars":   {"sent": 11, "replied": 1, "rate": 0.09}
    },
    "reply_rate_by_body_words": { "<150": ..., "150-250": ..., "250+": ... },
    "reply_rate_by_send_dow": { ... },
    "reply_rate_by_outlet_tier": { "tier_1": ..., "tier_2": ..., "tier_3": ... },
    "by_drafting_confidence": {
      "high": {"sent": 14, "replied": 8, "covered": 4},
      "medium": {"sent": 21, "replied": 6, "covered": 2},
      "low": {"sent": 6, "replied": 1, "covered": 0}
    },
    "best_3_pitches": [/* subject + first 100 chars of body + outcome */],
    "worst_3_pitches": [/* same shape */]
  }
  ```
- Workspace baselines: `{{workspace_baseline_reply_rate}}`, `{{workspace_baseline_coverage_rate}}`

## Prompt

```
[CACHED — insights_instructions_block]
A PR campaign just closed. You're writing a brief retrospective the agency
will read to understand what worked, what didn't, and what to do differently
next time. The retrospective is for the agency's own learning — not a
report to send to the client.

Be honest. Don't sugarcoat weak results. Don't manufacture insights from
small samples. If the data is too thin to draw conclusions, say so.

Output schema:

{
  "headline_summary": "string — 2 sentences. The bottom line: how did this campaign perform vs. baseline, and was the campaign successful by its own goals.",

  "what_worked": [
    {
      "observation": "string — what worked, in plain language",
      "evidence": "string — specific data supporting it",
      "confidence": "high | medium | low"
        // High = clear pattern, sample size adequate.
        // Medium = pattern visible, sample small.
        // Low = noticed but not statistically reliable.
    }
  ],
        // 1-4 items. Empty array is acceptable if nothing worked notably well.

  "what_didnt_work": [
    {
      "observation": "string",
      "evidence": "string",
      "confidence": "high | medium | low"
    }
  ],
        // 1-4 items. Empty array is acceptable.

  "patterns_to_remember": [
    {
      "pattern": "string — a generalizable rule for future campaigns",
      "evidence": "string",
      "applicability": "this client only | this campaign type | general"
    }
  ],
        // 0-5 items. Quality over quantity.

  "recommendations_next_time": ["string"],
        // 1-4 items. Actionable, concrete.

  "data_quality_note": "string or null",
        // If sample size is small (e.g., < 20 pitches sent), note it here.
        // null if sample is large enough to be confident.

  "honest_limitations": "string or null"
        // If the campaign data has gaps (e.g., many replies were unclassified,
        // or attribution was uncertain for most coverage), be honest about
        // what we can't conclude. null if the data is clean.
}

Constraints:
- Every claim must be supported by the data shown above. Do NOT invent
  statistics or trends. If the data shows reply rate was 12% and you'd
  like to claim it was "above average," verify it actually exceeds the baseline.
- Sample size honesty. With 8 pitches sent, you can't draw confident
  conclusions about pitch length. Say so.
- No PR-speak congratulations. The agency owner reads this; they want truth.
- Apparent correlations may be spurious. If "pitches sent on Tuesday
  performed best" is based on 4 Tuesdays vs. 2 Wednesdays, flag the small-N.
- If the campaign underperformed baseline, say so directly. Don't paper over it.
[/CACHED]

[NOT CACHED — per-campaign]
Campaign:
- Name: {{campaign_name}} for {{client_name}}
- Send window: {{send_window_start}} to {{send_window_end}}
- Strategy summary: {{strategy_summary}}

Aggregate outcomes:
- Targets identified: {{total_targets}}
- Pitches sent: {{total_pitches_sent}}
- Pitches skipped (user chose not to send): {{total_pitches_skipped}}
- Replies received: {{replies_count}}
  - Breakdown: {{replies_by_classification}}
- Coverage that resulted: {{coverage_count}}
  - Attributed to pitches in this campaign: {{coverage_attributed_to_pitches}}

Workspace baselines (averages across prior campaigns):
- Reply rate baseline: {{workspace_baseline_reply_rate}}
- Coverage rate baseline: {{workspace_baseline_coverage_rate}}

Pre-aggregated per-pitch outcomes:
{{pitch_outcomes_aggregated}}

Produce the retrospective. Return ONLY the JSON.
```

## Design notes

- **Why Sonnet, not Opus.** Post-hoc analysis. The audience is the agency owner, not a journalist. Sonnet's analytical writing is fully sufficient.
- **Why temperature 0.3.** Slightly higher than extraction — varying observations across campaigns benefit from variation in framing. Lower than drafting because the analysis must be data-grounded.
- **Why explicit confidence levels per observation.** Small-sample noise is the biggest risk. Tagging each observation with confidence forces the model (and the user) to treat thin signals appropriately.
- **Why `data_quality_note` and `honest_limitations` are separate.** `data_quality_note` is about sample size; `honest_limitations` is about coverage gaps (e.g., we can't tell if a pitch led to coverage if attribution was rejected). Different failure modes; conflating hides nuance.
- **Why no automatic A/B testing recommendations.** With 50 pitches across 5 days, the per-day sample is 10 — way too small to support that recommendation confidently. The prompt explicitly resists generalizing from small samples.
- **Why pre-aggregated input.** The prompt reasoned about patterns in v0; aggregating in SQL first means the prompt sees ~80% less input. The 3 best / 3 worst pitches stay verbatim because they're used for concrete quotation in the output.

## Eval set

10 closed-campaign datasets with hand-written acceptable insights:

- 3 well-performing campaigns (testing positive framing without sycophancy)
- 3 underperforming campaigns (testing honest direct framing)
- 2 mixed campaigns (testing nuance)
- 2 small-sample campaigns (< 15 pitches; testing data_quality_note appearance)

Hand-labeled ground truth: must-include observations, forbidden statements, expected confidence levels.

Hard gates:
- Schema compliance: 100%
- Zero fabricated stats (LLM-as-judge against the input data)
- Zero unwarranted confidence: a small-sample case marked "high confidence" is a failure
- Honest framing: an underperforming campaign cannot be summarized as a success

## Anti-patterns this prompt is designed to avoid

1. **Sycophantic recap.** "Great campaign! Excellent execution! Strong results!" → unhelpful.
2. **Spurious correlations from small N.** With 8 pitches, "Tuesday performed best" is noise.
3. **Inflated confidence on weak signals.** A 3pp difference from baseline in a 30-pitch campaign is probably noise.
4. **Vague recommendations.** "Try different subject lines" is unactionable. Recommendations must be concrete.
5. **Fabricated benchmarks.** "Industry-average reply rate is 12%" — we don't have that data. The prompt only references the workspace's own baseline.

## Known limitations

- Insights quality scales with campaign size. A 200-pitch campaign produces more reliable insights than a 20-pitch campaign. The data_quality_note surfaces this honestly but doesn't fix it.
- Per-journalist outcomes (Sarah replied; Bob didn't) aren't surfaced as patterns because they're noisy at the campaign scale; aggregated learning is captured at the workspace level via the rollup table instead.
- The prompt assumes the user is reading the insights to plan future campaigns. Client-facing reporting on outcomes goes through the existing report builder, not this insights output.
