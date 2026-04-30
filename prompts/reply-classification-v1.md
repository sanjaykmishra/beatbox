---
version: reply_classification_v1.0
model: claude-sonnet-4-6
temperature: 0
max_tokens: 300
expected_output_format: json
---

# Reply classification prompt

Classifies a journalist's reply to a PR pitch into one of six categories. Used by the inbound email worker after a reply is matched to a tracked pitch.

## Inputs

- `{{pitch_subject}}` — original pitch subject line
- `{{pitch_body_summary}}` — first 500 chars of the original pitch body
- `{{reply_body}}` — the journalist's reply text (plain or stripped HTML, ≤ 4000 tokens)

## Prompt

```
A PR practitioner sent a pitch to a journalist. Below is the original pitch
context and the journalist's reply. Classify the reply into exactly one of
these categories:

- interested: the journalist wants to follow up, is intrigued, asks to set
  up a call, requests an interview, or otherwise indicates positive engagement
  with covering the story.
- declined: the journalist is politely passing — too busy, not the right
  beat, doesn't fit their coverage, or just "thanks but no."
- more_info: the journalist needs additional materials before deciding —
  asking for a deck, dates, more details, photos, embargo info, or
  introduction to a different person.
- auto_reply: an automated out-of-office, vacation responder, or system
  bounce. Not a real human reply.
- unrelated: the journalist replied but the content is unrelated to the
  pitch (e.g., they accidentally hit reply-all on something else, or are
  asking about a different topic entirely).
- unclear: genuinely ambiguous. Don't use this if the reply is just terse
  — pick the most likely category. Use only when the reply could
  reasonably be two or more categories.

Original pitch subject: {{pitch_subject}}

Original pitch (excerpt): {{pitch_body_summary}}

Reply:
---
{{reply_body}}
---

Return JSON:
{
  "classification": "interested | declined | more_info | auto_reply | unrelated | unclear",
  "rationale": "one sentence explaining your classification — quote the key phrase from the reply that drove your decision"
}

Return ONLY the JSON object.
```

## Design notes

- Temperature 0 because this is classification, not generation.
- The `rationale` is critical — users need to trust our classification, and showing them the phrase we keyed on builds trust. The eval harness verifies that the rationale references actual content from the reply.
- We deliberately don't use a `positive | negative | neutral` axis. Pitches don't have sentiment in the traditional sense; they have *next steps*. The categories above map to actions the user might take.
- `auto_reply` matters because we don't want to mark a journalist's status as `replied` based on an out-of-office bounce. The recipient status update should treat auto_reply as still-pending.
- `unrelated` matters for accidental thread crossover (rare but happens) — we shouldn't pollute the journalist's reply rate with these.

## Eval set

50 hand-labeled replies across the six categories, including edge cases:

- Curt declines that look terse but aren't unclear
- Auto-replies that contain real-looking content (some are quite chatty)
- Multi-message threads where the reply we're looking at is buried below quoted text
- Replies in non-English (should classify as unclear with rationale, never confidently claim interested/declined)

Hard gate: classification accuracy ≥ 85% on the eval set before this prompt ships.

## Known limitations

- Sarcasm and dry humor occasionally read as sincere. Journalists are generally direct, but not always.
- Very brief replies ("Sure — let's chat") can be hard to disambiguate from polite close-outs ("Sure — let's chat sometime"). Real reply intent often only resolves in the next email.
- Threaded replies where the pitch is partially quoted: ensure the prompt only sees the new reply text, not the quoted pitch (handled by email parsing upstream).
