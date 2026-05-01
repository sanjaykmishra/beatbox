---
version: post_variant_v1.1
model: claude-sonnet-4-6
temperature: 0.6
max_tokens: 1200
expected_output_format: json
prompt_cache:
  - variant_instructions_block
  - brand_voice_block
---

# Social post variant generation prompt (v1.1)

Generates 3 variants of a social post for client review (per `docs/17-phase-1-5-social.md`).

This is v1.1 of the prompt; v1.0 ships in Phase 1.5 and is currently shipping. The migration to v1.1 is gated on the eval set in `docs/06-evals.md` showing variant quality unchanged.

This is part of the Phase 1.5 cost engineering pass. See `docs/18-cost-engineering.md`. The change is small: prompt-cache the brand voice and platform-constraint blocks. The model stays Sonnet because variant generation needs creativity that Haiku doesn't reliably produce, and the absolute cost is already modest.

## How the cost engineering applies here

One layer of savings: prompt-caching of brand voice and platform constraint blocks. These are stable per-client (brand voice changes when the client updates their style guide; platform constraints change when platforms update character limits — both rare). After the first variant generation in a cache window, ~70% of input tokens are cached.

Combined: ~$2-4/month per workspace projection on Sonnet without caching → ~$1.20-2.50/month with.

## Inputs

Same as v1.0:
- `{{client_name}}`, `{{client_industry}}`
- `{{post_topic}}` — what the post is about (user input)
- `{{platform}}` — bluesky | linkedin | x | reddit | threads
- `{{platform_constraints}}` — character limit, hashtag conventions, link behavior — prompt-cached per platform
- `{{brand_voice_notes}}` — the client's brand voice description from `client_context.style_notes` — prompt-cached per client
- `{{example_past_posts}}` — 2-3 of the client's past high-performing posts for reference

## Prompt

```
[CACHED — variant_instructions_block]
You are generating 3 variants of a social post for a client to review and
choose from. The variants should differ meaningfully — different angles,
different tones, different opening hooks — not just word substitutions.

Output schema:
{
  "variants": [
    {
      "label": "string — 2-4 word descriptor of the variant's angle (e.g. 'data-led', 'human story', 'contrarian take')",
      "body": "string — the post body, within platform constraints",
      "rationale": "string — 1 sentence explaining why this angle was chosen for this post and audience"
    },
    {
      ... // variant 2
    },
    {
      ... // variant 3
    }
  ]
}

Constraints:
- Each variant must respect platform_constraints (character limit, hashtag
  conventions). Posts that exceed limits are failures.
- Each variant must reflect brand_voice_notes — same client, same voice,
  three different framings.
- Do NOT generate variants that contradict each other on factual claims.
  All three should be true; they differ in framing only.
- No fabricated statistics or quotes. If post_topic mentions data, use
  the data as given; if it doesn't, don't invent.
- Avoid hyperbole: no "groundbreaking," "revolutionary," "incredible,"
  "tremendous." Confident factual language only.
[/CACHED]

[CACHED — brand_voice_block]
Client: {{client_name}} ({{client_industry}})

Brand voice:
{{brand_voice_notes}}

Past high-performing posts for reference:
{{example_past_posts}}

Platform: {{platform}}
Platform constraints:
{{platform_constraints}}
[/CACHED]

[NOT CACHED — per-post]
Topic for this post:
{{post_topic}}

Generate 3 variants. Return ONLY the JSON.
```

## Design notes

- **Sonnet stays.** Haiku tested on this task in shadow mode; variant diversity dropped meaningfully. The cost difference per call ($0.001 vs $0.003) doesn't justify the quality regression.
- **Temperature 0.6.** Higher than extraction or classification — variants explicitly need creative variation. Lower than pure-creative because we still need brand-voice fidelity.
- **Two cache blocks.** Variant instructions are universal; brand voice is per-client. Splitting them lets variant instructions stay cached even when the brand voice block invalidates (e.g., client updates style guide).
- **Past high-performing posts as in-context examples.** Better than a description of voice. The model picks up patterns directly.

## Eval set

15 hand-graded (client, topic, platform) scenarios across:

- 5 standard cases (typical client, typical topic, mainstream platforms)
- 3 platform-specific cases (Reddit-formatted, X-thread, LinkedIn long-form)
- 2 sensitive-topic cases (testing tone calibration around hard topics)
- 2 brand-voice-specific cases (testing fidelity to distinctive voices — e.g., a deliberately irreverent brand)
- 2 cases with sparse brand-voice notes (testing graceful degradation)
- 1 case with no example_past_posts (cold start)

For each: hand-written acceptable variants — must respect constraints, must reflect voice, three meaningfully-different angles.

Hard gates:
- Schema compliance: 100%
- All 3 variants within platform character limit: 100%
- Variant diversity: LLM-as-judge confirms 3 angles are meaningfully different on ≥85% of cases
- Brand-voice fidelity: ≥80% of judges identify the variants as matching the example_past_posts on blind eval
- Zero hallucinated facts about the client or the topic

## Migration plan

1. Restructure prompt for caching.
2. Shadow mode for 1 week: v1.0 (production) and v1.1 both generate; user sees v1.0; v1.1 logged.
3. LLM-as-judge comparison on 50 sampled variant sets.
4. Promote v1.1.

No model migration; risk is low.

## Known limitations

- Cache TTL means the first variant generation in a long quiet period for a client pays full input cost. Practical impact: small.
- Variants for very new platforms (where past_posts is empty) tend to be more generic. Workspace owner can add manual platform-specific guidance to brand_voice_notes.
- Cross-platform variant generation (e.g., "give me LinkedIn and Bluesky versions of this post") runs as separate calls. Could be batched in a future v1.2 if customer demand surfaces.
