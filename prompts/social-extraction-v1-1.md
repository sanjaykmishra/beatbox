---
version: social_extraction_v1.1
model: claude-sonnet-4-6
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.schema.SocialExtractionSchema
prompt_cache:
  - social_instructions_block
---

# Social mention extraction prompt (v1.1)

Adds `missing` to the `subject_prominence` enum, mirroring `extraction-v1.3.md`. v1.0 forced the model to misuse `passing` for posts that don't mention the subject at all (e.g. a Reddit thread about a topic adjacent to the client's industry but never naming the client). The runtime guard in `SummaryService` keys on the unified `feature/mention/passing/missing` signal across both streams; without `missing` on social, off-topic Reddit/Bluesky posts kept the guard from firing.

Per CLAUDE.md guardrail #3, this is a hallucination-prevention change, not a quality optimization.

## What changes vs. v1.0

1. **`subject_prominence` enum gains `missing`.** Strict definitions for all four values, including the social-specific calibration (a 12-word post about the client is still `feature` because social posts are short).
2. **Explicit instruction to choose `missing`** when the subject's name (or a clear alias) does not appear in the post body or in the parent thread (for replies / quote-posts). Topical adjacency is not mention.
3. **Cache structure annotated.** The instruction block is stable; only the per-post inputs change. Marked `[CACHED]` / `[NOT CACHED]` for the runtime cache splitter.
4. **No structural change** to the JSON shape. `SocialExtractionSchema.PROMINENCE` is extended additively; v1.0 outputs remain valid.

Existing rows keep their original prompt version; the new value flows in only on re-extraction.

## Inputs

Same as v1.0.

## Prompt

```
[CACHED — social_instructions_block]
You are extracting structured data from a social media post that mentions
(or may not mention) a company or person the user is doing PR for. Be
factual, neutral, and concise. If a field cannot be determined from the
content, return null — do not guess.

Output schema:
{
  "summary": "string — your 2-sentence neutral summary of how the subject is mentioned. Max 50 words. If the subject is not mentioned, say so plainly.",
  "key_excerpt": "string or null — the most newsworthy single sentence or phrase from the post, max 200 chars, verbatim. Null if the subject is missing.",
  "sentiment": "one of: positive | neutral | negative | mixed — sentiment toward the subject specifically. Use neutral when the subject is missing.",
  "sentiment_rationale": "string — one short sentence justifying the sentiment label, ideally referencing the specific phrase that drove the classification",
  "subject_prominence": "one of: feature | mention | passing | missing",
  "topics": "array of 1–3 lowercase topic tags (e.g. 'funding', 'product launch', 'criticism', 'amplification', 'thought leadership')",
  "is_amplification": "boolean — true if the post is primarily sharing or repeating something the subject originally said/posted/published, false otherwise. False when the subject is missing.",
  "media_summary": "string or null — if media is present, a brief description of what's shown; null if no media or no useful description"
}

subject_prominence definitions — pick the strictly correct value:
- feature: the post is primarily about the subject. Most of the post is about them.
- mention: the subject is named with substantial reference (a sentence or more of context).
- passing: the subject's name appears once or briefly, e.g. in a list.
- missing: the subject's name does NOT appear in the post body, OR (for replies / quote-posts)
  in the parent thread either. Use this even when the post is in the subject's industry,
  mentions adjacent topics, or names competitors. Topical adjacency is not mention. If you
  searched the post text and the subject's literal name (or a clear alias) is absent, the value
  is missing.

When subject_prominence is missing:
- Set sentiment to "neutral" and sentiment_rationale to "subject not mentioned in post."
- Set key_excerpt to null and is_amplification to false.
- Still extract media_summary as usual (it describes the post itself).

Return ONLY the JSON object, no surrounding text.
[/CACHED]

[NOT CACHED — per-post]
Platform: {{platform}}
Post URL: {{url}}
Author: {{author_display_name}} ({{author_handle}}) — {{author_follower_count}} followers
Author bio: {{author_bio}}
Posted: {{posted_at}}
Subject of coverage: {{subject_name}}

{{#if client_context}}
Relevant context about {{subject_name}}:
{{client_context}}
{{/if}}

{{#if is_reply}}
NOTE: This is a reply. The post being replied to said:
"{{parent_post_text}}"
{{/if}}

{{#if is_quote}}
NOTE: This is a quote-post. The post being quoted said:
"{{parent_post_text}}"
{{/if}}

{{#if has_media}}
Media attached: {{media_descriptions}}
{{/if}}

Engagement: {{engagement_likes}} likes, {{engagement_reposts}} reposts, {{engagement_replies}} replies{{#if engagement_views}}, {{engagement_views}} views{{/if}}

Post content:
---
{{post_text}}
---
```

## Eval set additions

v1.0 eval carries forward. New tests:

**Off-topic post scenarios.**
- 5 posts in the subject's industry that don't mention the subject — gate: 100% return `missing`.
- 5 posts mentioning competitors only — gate: 100% return `missing`.
- 5 posts where the subject is one of several brands listed — gate: `passing`, not `missing`.

**Reply / quote-post boundary.**
- 3 replies whose parent mentions the subject but the reply itself doesn't — gate: `mention` (the parent thread context counts).
- 3 replies where neither the reply nor the parent mention the subject — gate: `missing`.

## Migration plan

1. Land v1.1 (this prompt) + schema extension. v1.0 stays loadable.
2. Wire the `beat.prompts.social-extraction.version` flag (default `v1_1`, fallback `v1_0`).
3. Re-extract any reports stuck on the legacy 'passing' interpretation under the new prompt. Old rows keep their version per docs/05.
