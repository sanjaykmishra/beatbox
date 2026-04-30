---
version: social_extraction_v1.0
model: claude-sonnet-4
temperature: 0.1
max_tokens: 1500
expected_output_format: json
schema_class: app.beat.llm.schema.SocialExtractionSchema
---

# Social mention extraction prompt

Used by the social extraction worker to pull structured data from a single social media post about a client.

## Inputs

- `{{platform}}` — one of: x, linkedin, bluesky, threads, instagram, facebook, tiktok, reddit, substack, youtube, mastodon
- `{{url}}` — source URL of the post
- `{{author_handle}}` — social handle (e.g. "@perez", "u/example")
- `{{author_display_name}}` — display name if available
- `{{author_follower_count}}` — followers at time of post
- `{{author_bio}}` — bio if available
- `{{posted_at}}` — ISO timestamp of post
- `{{subject_name}}` — the company or person the agency is doing PR for
- `{{client_context}}` — relevant client style notes (per `prompts/extraction-v1.md` §15.1)
- `{{post_text}}` — full text of the post
- `{{is_reply}}`, `{{is_quote}}` — booleans indicating thread context
- `{{parent_post_text}}` — if `{{is_reply}}` or `{{is_quote}}`, the text being replied to or quoted (truncated to 500 chars)
- `{{has_media}}` — boolean
- `{{media_descriptions}}` — array of brief textual descriptions if media is present (e.g., from alt text or platform metadata)
- `{{engagement_likes}}`, `{{engagement_reposts}}`, `{{engagement_replies}}`, `{{engagement_views}}` — numbers if available

## Prompt

```
You are extracting structured data from a social media post that mentions a
company or person the user is doing PR for. Be factual, neutral, and concise.
If a field cannot be determined from the content, return null — do not guess.

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

Return a JSON object matching this schema:
{
  "summary": "string — your 2-sentence neutral summary of how {{subject_name}} is mentioned. Max 50 words. If the post is short, the summary may largely paraphrase the post itself.",
  "key_excerpt": "string or null — the most newsworthy single sentence or phrase from the post, max 200 chars, verbatim",
  "sentiment": "one of: positive | neutral | negative | mixed — sentiment toward {{subject_name}} specifically",
  "sentiment_rationale": "string — one short sentence justifying the sentiment label, ideally referencing the specific phrase that drove the classification",
  "subject_prominence": "one of: feature | mention | passing — feature = the post is primarily about {{subject_name}}, mention = named with substantial reference, passing = name appears once or briefly",
  "topics": "array of 1–3 lowercase topic tags (e.g. 'funding', 'product launch', 'criticism', 'amplification', 'thought leadership')",
  "is_amplification": "boolean — true if the post is primarily sharing or repeating something {{subject_name}} originally said/posted/published, false otherwise",
  "media_summary": "string or null — if media is present, a brief description of what's shown ('CEO at podium', 'screenshot of pricing page'); null if no media or no useful description"
}

Return ONLY the JSON object, no surrounding text.
```

## Design notes

### How this differs from article extraction

- **No headline.** The summary IS the headline equivalent. We use the first sentence-ish of the summary in cards.
- **No author parsing.** The author handle is given.
- **No publish date inference.** Always provided by the platform.
- **Subject prominence rules differ.** A 12-word post about your client is `feature`, not `mention`. Articles have hundreds of words; social posts have dozens. The prominence ladder is recalibrated for social-typical lengths.
- **`is_amplification` is new.** When a journalist quotes-shares a client's post, that's earned coverage of a specific kind — they amplified an owned message rather than writing their own piece. Worth tracking distinctly.
- **`media_summary` is new.** Social posts often carry meaningful images/video; we capture a textual summary to make reports useful even when the image isn't rendered.

### Sentiment on social

Sentiment classification is structurally the same four categories as articles, but:

- Sarcasm is more common on social than in journalism. The eval set includes sarcastic posts (positive surface, negative meaning) explicitly.
- Reply/quote context matters. A neutral-on-its-own reply may be negative when read against what it's replying to. The prompt provides parent context for this reason.
- Engagement weights are NOT used for sentiment — a popular negative post is still negative, not "more negative." Engagement is captured separately for reach/amplification metrics.

### Why temperature 0.1, not 0

Same reasoning as article extraction. Allows minor flexibility in summary phrasing without inviting drift.

### Why we feed parent post context

A reply to "We just launched our new product, what do you think?" might say "Honestly, this is great." Without parent context, the LLM might classify it as neutral commentary. With parent context, it's clearly a positive reaction to the launch.

### Why no attempt at thread synthesis

A thread of 200 replies could be summarized as a unit. Phase 1.5 doesn't do this — each reply is an independent mention with its own row. Phase 3+ may add thread-level synthesis once we know whether agencies actually want it (some prefer the granular view for sentiment analysis).

## Eval set

20 items in the eval golden set:

- 5 X posts (mix of original posts, replies, quote-posts)
- 5 LinkedIn posts (long-form thought leadership, brief announcements, reshares)
- 4 Bluesky posts
- 3 Reddit posts (one root, two comments)
- 3 mixed: Substack note, Mastodon post, Threads post

For each: ground truth content, expected sentiment, must-include facts, must-not-include hallucinations (especially: never fabricate quotes that aren't in the post).

Hard gates parallel to article extraction:
- Schema compliance: 100%
- Hallucination rate: 0
- Sentiment accuracy: ≥ 90%
- Subject prominence accuracy: ≥ 90%

Specific edge cases the eval verifies:
- Sarcastic positive-surface negative-meaning posts classified as negative
- Replies to client-original posts correctly identified as `is_amplification=true` when applicable
- Thread context changes sentiment classification when parent context flips meaning

## Known limitations

- Non-English posts: same as article extraction. Return null for fields requiring deep language understanding; fail explicitly rather than confidently misclassify.
- Image-heavy posts (Instagram-style) where text is minimal but the image carries meaning: `media_summary` helps but isn't a substitute for vision-based extraction. Phase 3+ may add multimodal extraction.
- Platform-specific norms: a one-line post on X is a complete thought; a one-line post on LinkedIn is unusual. The prompt doesn't currently weight platform conventions; we lean on broad LLM training instead. Watch eval scores per platform for drift.
