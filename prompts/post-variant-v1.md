---
version: post_variant_v1.0
model: claude-sonnet-4
temperature: 0.4
max_tokens: 1500
expected_output_format: json
---

# Post variant generation prompt

Generates platform-specific adaptations of an owned-content post from a master content piece. Used by the editorial calendar composer.

## Inputs

- `{{master_content}}` — the master copy the user wrote (typically the longest variant, often LinkedIn-shaped)
- `{{target_platforms}}` — array of platforms to generate variants for: x, linkedin, bluesky, threads, instagram, mastodon
- `{{client_name}}`, `{{client_style_notes}}` — from `client_context` per `docs/15-additions.md` §15.1
- `{{series_tag}}` — optional; the post's content series, if part of one
- `{{has_media}}` — boolean; whether the post will include media (affects char budget)

## Prompt

```
You are adapting a master piece of content to a set of social media platforms
for a PR agency posting on behalf of a client. Each platform has different
character limits, conventions, and audience expectations. Preserve the
substance and intent of the master content; adapt only the form.

Client: {{client_name}}
{{#if client_style_notes}}
Client style: {{client_style_notes}}
{{/if}}
{{#if series_tag}}
Series: {{series_tag}}
{{/if}}
Media included: {{has_media}}

Master content:
---
{{master_content}}
---

Generate a variant for each of these platforms: {{target_platforms}}

Platform conventions to respect:

- x: 280 char limit. Punchy, single-thought. If master is long, distill to the core point. No hashtag spam (1–2 max). Threads are acceptable if essential — return as numbered "1/", "2/" etc. but try to fit one tweet first.
- linkedin: 3000 char limit. First 200 chars matter most (visible before "see more"). Longer-form is fine; line breaks for scanability. Hashtags at the end, 3–5 max. Professional tone. No "🚀" energy unless the master content has it.
- bluesky: 300 char limit. Conversational, informal. Hashtags less common.
- threads: 500 char limit. Conversational, similar to X but slightly longer.
- instagram: Caption-style. 2200 char limit but most engagement under 200 chars. Emoji acceptable. Hashtags at the end, 5–10 acceptable.
- mastodon: 500 char limit (default; may be more on some instances). Conversational, hashtag-friendly. Federated audiences appreciate context.

Constraints:
- Preserve every factual claim in the master content. Do not invent statistics, quotes, or facts.
- Preserve client-specific names, terminology, and spelling (per style notes if provided).
- Match the master content's tone — if the master is sober, keep variants sober. If it's playful, keep that.
- Do NOT add hyperbole that isn't in the master ("groundbreaking," "revolutionary," "tremendous").
- Hashtags: only if they appear in the master, OR if the platform expects them (LinkedIn, Instagram). Never hashtag-spam.
- Stay within character limits. Round-down — don't get clever about exactly hitting the limit.

Return a JSON object:
{
  "variants": [
    {
      "platform": "x|linkedin|bluesky|threads|instagram|mastodon",
      "content": "the platform variant text",
      "char_count": <int>,
      "warnings": []  // array of strings; e.g. ["Master content has 2 stats; variant only fits 1"] or [] if none
    },
    ...
  ]
}

Return ONLY the JSON object.
```

## Design notes

### Why temperature 0.4

Higher than extraction (0.1) but lower than the executive summary (0.4 there too). The variants need real adaptation — phrasing differences, hashtag variation across platforms — so deterministic output would be flat. Temperature 0.4 produces variation without drift.

### Why one call for all platforms, not one call per platform

Two reasons:
1. **Coherence.** When the model adapts the same master to multiple platforms in one pass, it produces variations of the same idea rather than independent variants. Better cross-platform feel.
2. **Cost.** One call with multiple outputs is roughly half the cost of N separate calls.

The trade-off: one platform's failure can affect others. We mitigate by validating each variant independently and re-prompting just for the failed platform if needed.

### Why client style notes matter here

The post is going out under the client's name (or the agency posting on behalf of). Style notes ensure preferred names and terminology survive the adaptation. Example: a client's CEO is "Mike," not "Michael" — without style notes, the LLM may "professionalize" to Michael for LinkedIn.

### Warnings array

When the model has to drop content to fit (e.g., master has 4 stats, only 2 fit in the X variant), it surfaces this in `warnings`. The composer UI displays warnings inline so the user knows what was sacrificed.

### Anti-patterns this prompt is designed to avoid

1. **Hashtag spam.** Models love adding 8 hashtags. The prompt explicitly caps it.
2. **Hyperbole inflation.** Master says "this matters." Variants want to say "GAME-CHANGING." Caught by the no-hyperbole rule.
3. **Style drift across platforms.** The X variant goes punchy; the LinkedIn variant goes corporate-speak. Even when conventions allow it, drift breaks the "we wrote this with one voice" feel.
4. **Fabricated statistics.** Models occasionally invent numbers when adapting. Explicitly forbidden.

## Eval set

15 master content pieces of varying length and tone, each with hand-written acceptable variant for each platform. Eval checks:

- Character counts honored (hard gate; failure means the model didn't read its own constraints)
- Factual claims preserved (LLM-as-judge: do the variants contain only facts present in the master?)
- No invented hyperbole (regex against forbidden word list)
- Style-note adherence when style notes are provided (e.g., "Mike" vs "Michael" stays consistent)

Hard gates:
- Character limit compliance: 100%
- Hallucination rate: 0% (no facts not in master)
- Hyperbole rate: 0%

## Known limitations

- **Threads on X.** The prompt asks the model to fit one tweet first, fall back to threads if needed. Sometimes the model defaults to threads when one tweet would fit. Watch eval scores; tighten if drift is observed.
- **Image captions vs. posts.** Instagram especially distinguishes a caption (under the image) from the visual itself. The prompt treats Instagram output as caption text only; visual generation is out of scope.
- **TikTok** is not in the variant set because the master is text and TikTok requires video. Adapting text → video script is a different problem deferred to Phase 3+.
- **Cross-platform ordering of variants in JSON output** is not guaranteed. The composer UI sorts on receipt.
