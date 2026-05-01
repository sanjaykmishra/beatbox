---
version: reply_classification_v1.1
model: claude-haiku-4
temperature: 0.0
max_tokens: 200
expected_output_format: json
prompt_cache:
  - classification_instructions_block
escalation:
  to_model: claude-sonnet-4
  trigger: confidence == "low"
pre_filter:
  regex_handles: [auto_reply, bounce]
---

# Reply classification prompt (v1.1)

Classifies inbound replies to pitches into one of six classes (per `docs/12-phase-3-pitch-tracker.md` §12.2): `interested | declined | more_info | auto_reply | unrelated | unclear`.

This is v1.1 of the prompt; v1.0 ships first as part of Phase 3 Part 1 (pitch tracker). The migration to v1.1 is gated on the eval set in `docs/06-evals.md` showing Haiku-tier accuracy ≥ 90% on the labeled set, with regex pre-classifier handling auto-replies and bounces deterministically.

This is part of the Phase 3 cost engineering pass. See `docs/18-cost-engineering.md`.

## How the cost engineering applies here

Three layers stack:

1. **Regex pre-classifier.** Auto-replies have telltale headers (`Auto-Submitted: auto-replied`, `X-Autoreply: yes`, common subjects "Out of office", "I'm currently away", "Thanks for your email"). Bounces have characteristic structure (`Mail Delivery Subsystem`, `postmaster@`, `Returned mail`). Regex catches 30-40% of replies with no LLM call at all.
2. **Haiku for the rest.** Reply classification is a 6-class task on short text — Haiku's wheelhouse. Sonnet escalation only when Haiku reports low confidence.
3. **Prompt-cached instructions.** Stable across all classification calls.

Combined: ~$1-3/month per workspace projection on full-Sonnet → ~$0.10-0.40/month. Effectively free.

## Pre-classifier (deterministic, no LLM)

Before any LLM call, run regex matchers:

```
auto_reply patterns:
  - Header: Auto-Submitted: auto-replied
  - Header: X-Autoreply: yes / X-Autorespond: ...
  - Header: Precedence: auto_reply / Precedence: junk
  - Subject: ^(Out of office|I'm out of office|OOO|Auto[- ]?reply|Vacation|Away)
  - Body: starts with "Thanks for your email. I'm currently..." or similar
    (~10 well-known phrasings; full list in code)

bounce patterns:
  - From: Mail Delivery Subsystem | postmaster@ | mailer-daemon@
  - Subject: ^(Returned mail|Delivery (Status )?Notification|Undeliverable)
  - Body: contains "550 5.1.1" or "address rejected" or "user unknown"

If any pattern matches, classify deterministically and skip the LLM.
```

Per `docs/12-phase-3-pitch-tracker.md` the auto-reply class suppresses notifications, so getting these right matters operationally. Regex is more reliable than LLM for these — the patterns are stable and bounded.

## Inputs (when LLM is invoked)

- `{{reply_subject}}`, `{{reply_body}}` — the inbound reply
- `{{pitch_subject}}`, `{{pitch_summary}}` — the original pitch (1-line summary, not full body, to keep input compact)
- `{{journalist_name}}` — for context

## Prompt — Haiku tier

```
[CACHED — classification_instructions_block]
You are classifying a journalist's reply to a PR pitch into one of six classes.
Be honest about confidence; the system escalates "low confidence" to a more
capable model.

Classes:
- interested: the journalist wants to engage. Asks for materials, time, briefing,
  embargo confirmation, or expresses willingness to cover.
- declined: politely turning down. May offer reasons or referrals to colleagues.
- more_info: needs additional information before deciding (dates, materials,
  clarification, attribution permission).
- auto_reply: out-of-office, holiday, vacation. Should have been caught by
  pre-filter; if it reaches you, classify here and the system suppresses
  notifications.
- unrelated: the reply is from the journalist but doesn't address the pitch
  (rare — they may have meant to send to someone else, or are responding to
  unrelated correspondence in the same thread).
- unclear: genuinely ambiguous; user should review.

Output schema:
{
  "classification": "interested | declined | more_info | auto_reply | unrelated | unclear",
  "confidence": "high | medium | low",
  "rationale": "string — 1 sentence explaining the classification, citing specific text from the reply",
  "extracted_signals": {
    "decline_reason": "string or null — populate if declined and the reply states a reason",
    "info_requested": "string or null — populate if more_info and the reply specifies what",
    "next_step_implied": "string or null — populate if interested and the reply suggests a next action"
  }
}

Constraints:
- rationale must quote at least one phrase from the reply (in quotes or paraphrased
  with clear attribution).
- If the reply is short and ambiguous (e.g., just "thanks" or "interesting"),
  classify as "unclear" with low confidence.
- Set confidence to "low" if you're uncertain — the system will route to a
  better model.
- Do NOT invent context not in the reply.
[/CACHED]

[NOT CACHED — per-reply]
Original pitch:
- Subject: {{pitch_subject}}
- Summary: {{pitch_summary}}
- Journalist: {{journalist_name}}

The journalist's reply:
- Subject: {{reply_subject}}
- Body:
{{reply_body}}

Classify. Return ONLY the JSON.
```

## Prompt — Sonnet escalation tier

The Sonnet escalation prompt is identical to the Haiku tier; it receives only replies the Haiku tier flagged as low-confidence. Output schema matches.

## Orchestration

```
1. Run regex pre-classifier on the inbound reply.
   - If matches auto_reply: classify deterministically, suppress notifications, done.
   - If matches bounce: mark pitch_recipients.delivery_status = 'bounced', done.
   - Otherwise: proceed to LLM.

2. Run Haiku classifier.
   - If confidence in {"high", "medium"}: persist.
   - If confidence == "low": escalate to Sonnet.

3. Run Sonnet classifier (if escalated). Persist.

4. Surface to user via dashboard alert (per docs/16-client-dashboard.md).
   - "interested" replies: high-priority, surface immediately.
   - "more_info" replies: medium-priority.
   - "declined" / "unclear" / "unrelated": standard inbox.
   - "auto_reply": no surface; tracked for completeness.
```

## Eval set

50 hand-labeled (reply, expected_class) pairs across all six classes:

- 10 clear "interested" replies
- 10 clear "declined" replies
- 10 "more_info" replies (mix of materials requests, scheduling, clarifications)
- 5 "unrelated" replies (genuinely confusing — wrong-thread responses)
- 5 "unclear" replies (one-word, ambiguous tone)
- 10 auto-replies of varying phrasings (testing both regex pre-filter and LLM fallback)

Plus 10 bounces of varying provider formats (Gmail, Outlook, generic SMTP).

Hard gates:
- Regex pre-filter: 100% accuracy on auto-replies and bounces (these are deterministic; missing one is a regex bug, not a model issue)
- LLM-tier accuracy: ≥90% on the remaining classes
- Combined system: ≥92% accuracy across the full eval set
- Zero "interested" replies misclassified as "auto_reply" or "unrelated" (these would be missed opportunities; high cost of error)

## Migration plan

This prompt's v1.0 ships as part of Phase 3 Part 1 build (Haiku-tier with no regex pre-filter and no escalation discipline). v1.1 lands shortly after:

1. Build regex pre-filter rules behind a feature flag.
2. Run shadow mode for 1 week: both v1.0 and v1.1 classify every inbound reply; user sees v1.0; v1.1 logged.
3. Verify regex coverage on real traffic; tune patterns.
4. Promote v1.1.

## Known limitations

- Regex patterns drift as email providers update auto-reply formats. Monitor monthly; if regex hit rate drops below 25%, audit patterns.
- Edge case: a journalist who is interested but writes very tersely ("send the deck") may classify as "unclear" or "more_info" rather than "interested." Acceptable — the user reviews unclear replies anyway.
- Localization: all current eval cases are English. Non-English auto-replies fall through to the LLM, which handles them but at lower confidence.
