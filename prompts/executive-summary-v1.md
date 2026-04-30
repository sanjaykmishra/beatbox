---
version: executive_summary_v1.0
model: claude-opus
temperature: 0.4
max_tokens: 600
expected_output_format: text
---

# Executive summary prompt

Used by the render worker to write the prose paragraph that opens every report. This is the highest-stakes prompt in the system: it's the first thing the agency's client reads, and it's what they'll skim if they read nothing else.

## Inputs

- `{{client_name}}` — e.g. "Acme Corp"
- `{{period_start}}`, `{{period_end}}` — date range
- `{{count}}` — total coverage items
- `{{tier_1_n}}`, `{{tier_2_n}}`, `{{tier_3_n}}` — counts by tier
- `{{positive_n}}`, `{{neutral_n}}`, `{{mixed_n}}`, `{{negative_n}}` — counts by sentiment
- `{{outlet_list}}` — top 5 outlets by reach, comma-separated
- `{{topic_list}}` — top topics, comma-separated
- `{{up_to_5_headlines}}` — newline-separated, the most prominent placements

## Prompt

```
You are writing the executive summary that opens a monthly PR coverage report.
The reader is the client (a marketing or communications executive at the
company being covered). The voice is the agency's: confident, factual, positive
but not hype-y, written like a senior account director would write it.

Client: {{client_name}}
Period: {{period_start}} to {{period_end}}
Total coverage items: {{count}}
Tier breakdown: Tier 1: {{tier_1_n}}, Tier 2: {{tier_2_n}}, Tier 3: {{tier_3_n}}
Sentiment breakdown: positive: {{positive_n}}, neutral: {{neutral_n}}, mixed: {{mixed_n}}, negative: {{negative_n}}
Top outlets by reach: {{outlet_list}}
Most-mentioned topics: {{topic_list}}
Notable headlines:
{{up_to_5_headlines}}

Write a 3-paragraph executive summary:

Paragraph 1: Headline result of the period — quantify it. ("This month
delivered N pieces of coverage across M outlets, including X tier-1
placements...")
Paragraph 2: Narrative. What were the dominant themes? Which placements matter
most and why?
Paragraph 3: Forward-looking — what to watch next month based on what landed.
Honest, not a sales pitch.

Constraints:
- 180–250 words total
- No bullet points, no headers
- No hyperbole ("groundbreaking," "tremendous," "unprecedented")
- Do not invent statistics or quotes not in the data above
- If the period had only neutral or mixed coverage, say so honestly

Return only the summary text.
```

## Design notes

- Opus, not Sonnet, because prose quality dominates here. Cost difference is ~$0.20 per report — trivial.
- Temperature 0.4 (higher than extraction) for prose variety. We don't want every report opening "This month delivered..."
- The hard ban on hyperbole is enforced by the eval harness via a forbidden-word list. Words like "groundbreaking," "revolutionary," "tremendous," "unprecedented" trigger a regression failure.
- We feed structured stats AND headlines so the model can be specific and accurate without summarizing summaries.
- The "honest, not a sales pitch" instruction is doing real work. Without it, models drift into PR-speak.
- 180–250 words is enforced as a soft bound by the prompt and a hard bound by max_tokens.

## Anti-patterns this prompt is designed to avoid

1. **Made-up numbers.** Stats only come from the substituted variables.
2. **Made-up quotes.** No quotes are passed in; the prompt doesn't ask for any.
3. **Hyperbole.** Explicit bans + eval enforcement.
4. **Generic openings.** Temperature + variety prompt.
5. **Glossing over bad months.** The "say so honestly" instruction is critical for trust.
