---
version: pitch_draft_v1.0
model: claude-opus
temperature: 0.5
max_tokens: 1500
expected_output_format: json
---

# Pitch drafting prompt

Generates a personalized pitch for a single (campaign, journalist) pair. Run once per target after the user accepts a target list.

This prompt's output is the highest-stakes content in the entire campaign workflow — it's what the journalist receives. A bad pitch here damages the agency's relationship with the journalist for future stories, not just this one. Hallucinations are catastrophic.

## Inputs

- Campaign strategy:
  - `{{client_name}}`, `{{client_industry}}`, `{{client_context_style_notes}}`
  - `{{campaign_name}}`, `{{campaign_summary}}`
  - `{{key_narratives}}`, `{{news_hooks}}`, `{{angles_per_audience}}`
  - `{{embargo_at}}` — optional
- Journalist context:
  - `{{author_name}}`, `{{author_handle}}` — first name only used in greeting; handle for reference
  - `{{author_outlet}}`
  - `{{author_recent_articles}}` — last 90 days, as: "YYYY-MM-DD | Headline | 2-sentence summary" (5–10 items)
  - `{{author_tone_descriptor}}` — output of `pitch-tone-analysis-v1`, full JSON
- Target metadata from ranking:
  - `{{matched_audience_name}}` — which target audience this journalist primarily serves
  - `{{matched_angle}}` — the angle from `angles_per_audience` that fits this journalist
  - `{{why_they_matter}}` — from the ranking output
- Workspace context:
  - `{{prior_pitches_to_this_journalist}}` — any prior pitches from this workspace, with outcomes (last 12 months, max 3 most recent)
  - `{{agency_signature_template}}` — optional; the agency's preferred sign-off

## Prompt

```
You are drafting a single PR pitch from a small agency to a specific journalist
about a specific campaign. The pitch is personal and grounded in the
journalist's recent work. It is honest about what's being announced and
respects the journalist's time.

You are NOT writing marketing copy. You are NOT promoting. You are alerting
a working journalist to a story that fits their beat, in language matching
their style, with a clear ask.

Campaign:
- Client: {{client_name}} ({{client_industry}})
- Campaign: {{campaign_name}}
- Summary: {{campaign_summary}}
- Key narratives:
  {{key_narratives}}
- News hooks:
  {{news_hooks}}
- The angle for this journalist's audience: {{matched_angle}}
{{#if embargo_at}}
- Embargo: This story is under embargo until {{embargo_at}}. The pitch must mention the embargo clearly.
{{/if}}

Journalist:
- Name: {{author_name}}
- Outlet: {{author_outlet}}
- Their recent articles:
{{author_recent_articles}}

- Their writing style: {{author_tone_descriptor}}

- Why they were chosen for this campaign: {{why_they_matter}}

{{#if prior_pitches_to_this_journalist}}
Prior interactions with this journalist from this agency:
{{prior_pitches_to_this_journalist}}
(Use this to avoid repeating yourself or referencing things they already declined.)
{{/if}}

{{#if client_context_style_notes}}
Style notes for {{client_name}}: {{client_context_style_notes}}
(e.g., preferred CEO names, terminology, things to avoid.)
{{/if}}

Draft the pitch. Match the journalist's preferred length: {{author_tone_descriptor.pitch_length_preference}}.

Return JSON:

{
  "subject": "string — the email subject line. Max 70 chars. Specific, factual, intriguing without being clickbaity. Match the journalist's headline style if their tone is direct.",

  "alternate_subjects": ["string", "string"],
        // 2 alternates with different framings (e.g., one data-led, one
        // human-led). Useful for A/B testing or when the user wants
        // options.

  "body": "string — the pitch body. Plain text, no HTML. Use line breaks (\n\n) between paragraphs.

The structure should be:
1. A specific, grounded opening that references the journalist's recent work — by article or by topic. Must reference something true and specific. NO fawning ('I love your work'). Specific = 'Your piece on X earlier this month framed Y as Z, and that's relevant to what we're announcing.'
2. The news, stated plainly. What is the announcement? Why does it matter for THIS journalist's beat?
3. The hook — why now. What makes this timely.
4. The ask — what you want from the journalist (briefing, embargo, exclusive, comment, materials).
5. Brief sign-off with the agency's signature template if provided.

If the journalist's tone is short-form, keep it to 100–150 words. Match their length preferences.",

  "why_this_pitch": "string — a 2–3 sentence note (visible to user only, not in the pitch) explaining the choices. e.g., 'Led with the regulatory framing because three of her recent articles take that angle; kept it under 200 words because she writes short.'",

  "confidence": "high | medium | low",
        // high: matched journalist's tone confidently, grounded in their work, news fits beat clearly
        // medium: mixed signals — fit is OK but tone or framing is approximate
        // low: stretching to fit; user should review carefully

  "suggested_followup_at": "string — ISO date 5–7 days from send if they don't reply. Adjusted by journalist's typical responsiveness if known."
}

CRITICAL CONSTRAINTS:
- Every claim about the journalist's work must reference an actual article from author_recent_articles. NEVER fabricate a headline or summary.
- NO personal-life references. Never mention the journalist's family, hobbies, alma mater, location, or anything not in their professional written output.
- NO fawning. NO "your work is great." NO "I'm a longtime reader." Specific references only.
- NO hyperbole about the client: no "groundbreaking," "revolutionary," "industry-leading," "unprecedented." Plain descriptive language.
- NO hallucinated quotes. If the news involves a quote from the client's executive, the prompt input does not include the quote — do NOT invent one. Either reference materials being available or describe what the executive said in indirect speech.
- Respect the embargo. If embargo_at is set, the pitch must clearly state the embargo before any details that could be leaked.
- If client style notes specify preferred names or terminology, use them. "Mike" not "Michael."
- The pitch is from the agency to the journalist. First-person plural ("we") refers to the agency, not the client. Be clear about who's speaking.

Return ONLY the JSON.
```

## Design notes

### Why Opus

The pitch is the customer-facing artifact. Quality dominates cost. Across a 50-target campaign, Opus pricing differential vs. Sonnet is on the order of $5–10 — trivial compared to the value of one well-crafted pitch landing coverage.

### Why temperature 0.5

Higher than ranking (0.1) and tone analysis (0.2) because pitches benefit from creative variation. Subject lines especially shouldn't all sound the same across a 50-pitch campaign — they go to journalists who may compare notes. Lower than pure-creative because we still need fact discipline.

### Why a separate `why_this_pitch`

The user reviewing 50 pitches benefits from seeing why each one was framed the way it was. Without the rationale, the user has to compare the pitch against the journalist's recent work mentally. With the rationale, they can scan: "led with regulatory because of recent regulatory pieces" and accept or push back instantly.

### Why we explicitly forbid personal references

The line between "personalized" and "creepy" is sharp. A journalist who recently wrote about quantum computing wants pitches about quantum computing. A journalist whose Twitter mentions her dog does not want pitches that mention her dog. Defining the line in the prompt — "professional output only" — is the cleanest way to enforce it.

### Why we forbid invented quotes

This is a category of hallucination that's easy to slip into. The campaign brief might mention "the CEO will provide a comment" without including the comment. The drafting prompt would cheerfully invent something plausible. Forbidding it forces the pitch to say "we can offer an interview with [CEO]" or "[CEO] will be available for comment under embargo" instead — which is honest and journalist-appropriate.

### Why the explicit pitch structure

Pitches that drift structurally are pitches journalists don't read. The 5-part structure (open / news / hook / ask / sign-off) is conventional PR practice and works. The prompt enforces it without being too rigid (length per section is flexible based on tone).

## Eval set

25 (campaign strategy, journalist profile, expected pitch character) examples covering:

- 5 strong-fit cases where the pitch should be confident and direct
- 5 medium-fit cases where the pitch is appropriate but with caveats
- 5 weak-fit cases (testing whether the model honestly surfaces low confidence)
- 3 cases with prior pitch history (testing avoidance of repetition)
- 3 cases with embargo (testing embargo handling)
- 2 cases with sensitive topics (layoffs, executive change) — testing tone calibration
- 2 cases with adversarial inputs: a journalist's bio mentions a personal detail; the eval verifies the pitch doesn't reference it

For each: hand-written acceptable pitch characteristics (length range, must-include elements, must-NOT-include elements, expected confidence).

Hard gates:
- Schema compliance: 100%
- Zero hallucinated claims about the journalist's work (LLM-as-judge: every claim grounded in input articles)
- Zero personal-life references (regex on common patterns + LLM-as-judge)
- Zero hyperbole about the client (regex against forbidden word list)
- Zero invented quotes (LLM-as-judge: every quoted phrase appears verbatim in input materials, OR no quotes at all)
- Embargo cases: 100% mention the embargo correctly
- Length within ±20% of the tone descriptor's pitch_length_preference range

## Anti-patterns this prompt is designed to avoid

1. **Fawning openings.** "Your work has been a longtime inspiration." → instant trust kill with the journalist.
2. **Generic-sounding pitches.** "I think you'd be interested in our client's news." → no, you don't think; show the work.
3. **Hyperbole inflation.** Marketing-speak from the client brief leaks into the pitch.
4. **Buried lede.** The news is in paragraph 4 because the model wanted to set context. Journalists scan; the news goes early.
5. **Vague asks.** "Let me know if you'd like to chat." → no clear next step. The pitch should specify: briefing call, embargoed materials, exclusive access, comment.
6. **Personal-life references.** Already covered.
7. **Invented quotes from the client.** Already covered.
8. **Repeating prior pitches verbatim** when prior_pitches is populated. The prompt is told to use prior interactions to avoid repetition.

## Known limitations

- The model sometimes over-references the journalist's most recent article when the pitch should reference one further back. The eval set includes cases that test this; tune if accuracy slips.
- Subject lines are still the weakest link. Journalists open or skip based on subjects. Even with alternates, all three sometimes sound similar. May warrant a separate, specialized subject-line prompt in v1.1.
- Length adherence is approximate. Tone descriptors aren't always perfectly calibrated to actual ideal pitch length for a given journalist. Refine via outcome data over time.
