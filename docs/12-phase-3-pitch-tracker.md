# 12 — Phase 3 (months 7–12): Pitch tracker

## What and why

Phase 3 is the platform expansion. We add a lightweight pitch CRM that closes the loop between outreach and outcomes: every pitch sent, every reply received, every piece of coverage attributed back to a pitch.

This is the feature that turns Beat from a reporting tool into a system of record. Once an agency tracks pitches in Beat, switching costs become real. Once we can answer "which pitches actually worked?" we have data nobody else has.

### Customer evidence motivating Phase 3

(All collected during Phase 1 and 2 from real customers.)

- "I have a Google Sheet with 600 pitches in it. I never look at it because it's too messy."
- "My junior pitches journalists. I don't know who he's contacted or what he said."
- "When I get coverage I have no idea which pitch caused it."
- "I want to know which subject lines work."
- "My biggest skill is knowing which journalist covers what — your tool already kind of knows this from the reports."

### Why this is Phase 3, not Phase 1

Pitch tracking is bigger than reporting. Building it first would have meant a worse reporting tool and a worse pitch tracker, both. Building it after reporting means:

- We already have an authors table that's been accumulating real journalist data for 6 months.
- We already have outlet tier data — pitch hit rates can be analyzed by outlet quality.
- Customers trust us with reporting; selling them a second module is much easier than selling them a unified product cold.

## The conceptual model

```
Pitch
  ├── Recipients (one or more journalists/contacts)
  ├── Subject + Body
  ├── Sent at, by whom
  ├── Related Client
  └── Outcomes
       ├── Replies (matched from inbound emails)
       └── Coverage attributions (suggested by us, confirmed by user)
```

A single pitch can be sent to multiple journalists. Each recipient has independent status. Replies and coverage attach to the recipient level.

## Features in this phase

1. Pitch data model and CRUD
2. Email capture: browser extension + native Gmail addon
3. Reply tracking
4. Coverage attribution (LLM-assisted)
5. Pitch analytics
6. Journalist database expansion
7. Reporting integration (pitches → coverage section in reports)

---

## 12.1 — Data model

### Core tables

```sql
CREATE TABLE pitches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID REFERENCES clients(id) ON DELETE SET NULL,
    sent_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    subject         TEXT NOT NULL,
    body_plain      TEXT,
    body_html       TEXT,
    sent_at         TIMESTAMPTZ NOT NULL,
    source          TEXT NOT NULL CHECK (source IN ('manual','gmail_addon','outlook_addon','browser_extension','imported')),
    external_message_id TEXT,                     -- for matching replies
    thread_id       TEXT,                          -- email thread ID
    tags            TEXT[] NOT NULL DEFAULT '{}',
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_pitches_workspace_sent ON pitches(workspace_id, sent_at DESC);
CREATE INDEX idx_pitches_client ON pitches(client_id, sent_at DESC);
CREATE INDEX idx_pitches_thread ON pitches(thread_id) WHERE thread_id IS NOT NULL;

CREATE TABLE pitch_recipients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pitch_id        UUID NOT NULL REFERENCES pitches(id) ON DELETE CASCADE,
    author_id       UUID REFERENCES authors(id) ON DELETE SET NULL,
    email           CITEXT NOT NULL,
    name            TEXT,
    status          TEXT NOT NULL DEFAULT 'sent' CHECK (status IN ('sent','opened','replied','bounced','no_response')),
    first_opened_at TIMESTAMPTZ,
    first_reply_at  TIMESTAMPTZ,
    last_status_change_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pitch_recipients_pitch ON pitch_recipients(pitch_id);
CREATE INDEX idx_pitch_recipients_author ON pitch_recipients(author_id);
CREATE INDEX idx_pitch_recipients_email ON pitch_recipients(email);

CREATE TABLE pitch_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pitch_recipient_id UUID NOT NULL REFERENCES pitch_recipients(id) ON DELETE CASCADE,
    received_at     TIMESTAMPTZ NOT NULL,
    body_plain      TEXT,
    body_html       TEXT,
    classification  TEXT CHECK (classification IN ('interested','declined','more_info','auto_reply','unrelated','unclear')),
    classification_rationale TEXT,
    classification_prompt_version TEXT,
    raw_email       JSONB,                        -- redacted; see notes
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pitch_replies_recipient ON pitch_replies(pitch_recipient_id);

CREATE TABLE pitch_coverage_attributions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pitch_recipient_id UUID NOT NULL REFERENCES pitch_recipients(id) ON DELETE CASCADE,
    coverage_item_id UUID NOT NULL REFERENCES coverage_items(id) ON DELETE CASCADE,
    confidence      TEXT NOT NULL CHECK (confidence IN ('suggested','confirmed','rejected')),
    rationale       TEXT,                          -- LLM-generated explanation
    suggested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ,
    confirmed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (pitch_recipient_id, coverage_item_id)
);

CREATE INDEX idx_attributions_coverage ON pitch_coverage_attributions(coverage_item_id);
```

### Notes

- `pitches.workspace_id` is denormalized for query performance (same pattern as `reports`).
- `pitch_replies.raw_email` stores the parsed email payload but **redacted** of sensitive headers (no `Received:` chain, no `DKIM-Signature`, etc.). Bodies stored only for replies, not full pitches — pitches are stored as their composed content. Retention: 365 days, then automatic redaction to remove body content and keep metadata only.
- `pitch_coverage_attributions` is the join table. Confidence levels: `suggested` (LLM proposed), `confirmed` (user clicked yes), `rejected` (user clicked no — we track rejections so we don't suggest the same thing twice).

### Author enrichment

The existing `authors` table grows in importance. Add columns:

```sql
ALTER TABLE authors
  ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN bio TEXT,
  ADD COLUMN latest_articles JSONB NOT NULL DEFAULT '[]'::jsonb,  -- recent bylines [{url, headline, published_at}]
  ADD COLUMN preferred_topics TEXT[] NOT NULL DEFAULT '{}',       -- inferred from bylines
  ADD COLUMN coverage_count INT NOT NULL DEFAULT 0,                -- denormalized; updated on coverage ingest
  ADD COLUMN pitch_response_rate NUMERIC(4,3);                     -- workspace-scoped? no — global; subtle
```

The `pitch_response_rate` is global by design — if a journalist replies to most pitches across all our customers, that's a signal worth surfacing.

---

## 12.2 — Email capture

### The capture problem

Customers send pitches from their own email (Gmail, Outlook). We need to know what they sent, when, to whom — without forcing them to BCC us or paste pitches into our UI.

Three options, ranked by effort and effectiveness:

| Option | Effort | UX | Coverage |
|---|---|---|---|
| **BCC capture** (`track-{token}@beat.app`) | Low | OK; users forget | ~30% of pitches |
| **Browser extension** | Medium | Good; persistent | ~70% of pitches |
| **Native Gmail addon** | High | Excellent | ~90% of Gmail users |
| **Outlook addon** | High | Excellent | Outlook users |

Plan: ship BCC capture in week 1 of Phase 3 (it reuses Phase 2's inbound email infra). Ship browser extension in weeks 2–6. Native Gmail addon in weeks 7–12. Outlook addon deferred to Phase 4.

### BCC capture

Each workspace already has an inbox_token from Phase 2. Add a second magic address: `track-{inbox_token}@track.beat.app`. Users BCC this address when sending pitches.

Inbound webhook handler (`POST /v1/webhooks/inbound-pitch`) parses:

- From: → `pitches.sent_by_user_id` (matched against workspace members' emails; reject if no match)
- To, Cc: → `pitch_recipients` rows
- Subject, body → `pitches.subject`, `body_plain`, `body_html`
- Message-ID, In-Reply-To, References → `external_message_id`, `thread_id`

Reuses Postmark inbound infra from Phase 2.

### Browser extension

Manifest V3 extension for Chrome and Edge. Detects when the user sends an email from Gmail or Outlook web. On send:

- Captures From, To, Cc, Subject, Body.
- Asks the user (one-time): "Track this pitch in Beat?" with a "remember for this client" option.
- POSTs to `/v1/pitches/captured` with the captured data.

Auth: extension stores the user's session token after a one-time login flow. Token rotated.

Implementation in a separate `extension/` directory. Build via Vite + CRX plugin.

### Native Gmail addon

Built using Google Workspace Add-ons framework. Surfaces a sidebar in Gmail compose:

- "Track in Beat" toggle.
- Client picker (auto-suggested from email content).
- Tags input.
- Notes field.

When the user sends, the addon makes a server-side capture with rich metadata (the addon has more access than an extension does).

Implementation in `gmail-addon/` directory. Apps Script + TypeScript via clasp.

### API additions

- `POST /v1/pitches/captured` — endpoint for extension/addon. Body: full pitch data. Validates user, dedupes by `external_message_id`.
- `POST /v1/webhooks/inbound-pitch` — Postmark BCC capture webhook.
- `POST /v1/pitches` — manual creation in UI.
- `GET /v1/pitches` — paginated list, filterable by client, status, date range.
- `GET /v1/pitches/:id` — full pitch with recipients, replies, attributions.
- `PATCH /v1/pitches/:id` — edit subject, body, tags, notes (only if no replies received).
- `DELETE /v1/pitches/:id` — soft delete.

---

## 12.3 — Reply tracking

### Approach

Replies arrive on the user's email, not ours. To capture them, the user has options:

1. **Forward replies manually** to `track-{token}@track.beat.app`. Works but high friction.
2. **Auto-forward filter** in Gmail/Outlook — set up once, captures all replies to threads we've seen. Medium friction, much better coverage.
3. **OAuth read access** to Gmail/Outlook — we read inbox via API, looking only at threads we know about. Low friction, but requires deep trust + tight scopes.

Plan: ship (2) on day one (provide setup guide). Add (3) later for users who want zero friction. Never read emails outside of threads where we sent the original pitch.

### Matching logic

For each inbound email:

1. Check `In-Reply-To` and `References` headers against `pitches.external_message_id`.
2. If thread match: create `pitch_replies` row, link to recipient.
3. If no thread match: discard (this is not a reply to a tracked pitch).
4. Update `pitch_recipients.status` to `replied`, set `first_reply_at`.

### Reply classification

Run each reply through Claude Sonnet using `prompts/reply-classification-v1.md`:

- `interested` — journalist wants to follow up
- `declined` — politely passing
- `more_info` — asking for materials, dates, more details
- `auto_reply` — out-of-office, vacation, system bounce
- `unrelated` — accidentally got onto the thread, not relevant
- `unclear` — ambiguous; surface to user

Store classification + rationale + prompt version on `pitch_replies`.

### UI surface

`/pitches/:id`:
- Pitch metadata at top.
- Per-recipient timeline: sent → opened → replied → coverage suggested → coverage confirmed.
- Reply text rendered with classification badge.
- Quick actions: confirm/reject classification, mark as covered manually.

---

## 12.4 — Coverage attribution

### The headline feature

When a coverage item enters the system (via existing extraction flow), we run an attribution check:

1. Find candidate pitches: same client, same author/journalist, sent within the last 30 days, recipient status was `replied` or `interested`.
2. If 1 candidate: surface as "suggested" with high confidence.
3. If multiple candidates: rank by recency + reply classification + topic match (LLM-assisted via `prompts/pitch-attribution-v1.md`).
4. Insert `pitch_coverage_attributions` rows with `confidence='suggested'`.

User confirms or rejects in the UI. Confirmed attributions feed reports.

### LLM-assisted matching

When ranking candidates, we use the prompt to compare:

- Pitch subject + body
- Coverage headline + lede + key quote
- Reply text (if any)

Output: ranked list with rationales. We surface the top candidate as suggested; user can pick a different one.

### UI surface

In the report builder (Step 2 from `docs/07-wireframes.md`), each coverage item now shows attribution status:

- "✓ Pitched by Alex on Dec 3" — confirmed
- "Possible match: pitch from Dec 3 to Sarah Perez" — suggested, click to confirm/reject
- (no badge) — no candidate found

In `/pitches/:id`, recipients show their downstream coverage if any.

### Acceptance criteria

- For a known set of pitch→coverage pairs (50 in eval set), suggested attribution accuracy ≥ 80%.
- False positive rate (incorrectly suggesting a wrong attribution) < 5%.
- The "rationale" surfaced to the user is plain English and convincing.

---

## 12.5 — Pitch analytics

New page: `/analytics/pitches`. Aggregates across all pitches in a workspace.

Charts and tables:

- Pitch volume over time
- Reply rate by month
- Hit rate (pitches → coverage) by month
- Top-performing journalists (replies, coverage)
- Top-performing subject lines (with statistical significance markers)
- Per-client breakdown

Pre-compute monthly rollups (`pitch_metrics_monthly`, similar to `client_metrics_monthly`).

---

## 12.6 — Journalist database expansion

By Phase 3, the `authors` table contains thousands of journalists, accumulated from coverage ingestion since Phase 1. Phase 3 makes this a navigable, queryable database.

### New surface

`/journalists` — searchable list with filters:

- Outlet
- Beat / topic
- Recently active (last published within X days)
- Reply rate to our pitches (workspace-scoped)
- Reply rate to all pitches on Beat (global, hidden behind opt-in)

`/journalists/:id` — profile page:

- Name, primary outlet, bio, social handles
- Recent bylines (from `latest_articles`)
- Inferred topics
- Pitches sent (workspace-scoped)
- Coverage they've given our clients
- "Compose pitch" button → prefilled compose modal

### Enrichment

Authors are seeded with bylines but lack contact info. Add an enrichment job:

- For high-value authors (Tier 1 outlet + recently active), look up email via Hunter.io or Apollo (paid, ~$0.10/lookup).
- Mark `email_verified=true` on success.
- Record source for compliance.

Enrichment is opt-in per workspace and rate-limited. Budget: ~$50/month per workspace, hard cap.

### Privacy

Journalists are public figures publishing under bylines, but we still:

- Honor opt-out requests (a public form on `beat.app/journalist-optout`).
- Don't share workspace-scoped pitch data across workspaces by default.
- Allow journalists to claim their profile and edit their preferred topics, bio.

### Acceptance criteria

- Search across 10K authors returns in < 200ms.
- Enrichment success rate ≥ 60% for tier-1 authors.
- Opt-out works end-to-end and removes the author from search results within 24 hours.

---

## 12.7 — Reporting integration

The big payoff: reports get smarter.

### New report sections

Add to the template structure schema (`docs/11-phase-2-features.md` 11.1):

```typescript
| { type: "pitch_attribution"; layout: "summary" | "detailed" }
| { type: "pitch_funnel"; period: "this_month" | "last_3_months" }
| { type: "earned_vs_pitched"; show_chart: boolean }
```

- `pitch_attribution`: "Of 47 pitches sent, 12 led to coverage (25.5% hit rate). Top performer: Sarah Perez at TechCrunch (3 hits in 4 pitches)."
- `pitch_funnel`: visualization of pitch → reply → coverage funnel.
- `earned_vs_pitched`: bar chart of pitched vs. coverage by topic.

### Executive summary integration

Update `prompts/executive-summary-v1.md` (bump to v1.1) to optionally include pitch funnel data when present:

- "December delivered 14 pieces of coverage from 47 pitches sent (29.8% hit rate, up from 22% in November)."

Make this conditional — if no pitch data exists for the period, the summary falls back to the existing format.

---

## Eval set additions

Phase 3 expands the golden set in `docs/06-evals.md`:

- 30 pitch→coverage pairs (verified manually) for attribution eval.
- 50 pitch replies labeled by classification for reply classifier eval.

Hard gates for Phase 3 launch:

- Reply classification accuracy ≥ 85%.
- Attribution suggested-match accuracy ≥ 80%, false positive rate < 5%.

---

## Migration / backwards compatibility

- All Phase 3 tables are new. No backfill needed.
- Existing reports are unchanged unless explicitly regenerated with a Phase 3 template.
- The browser extension and Gmail addon are opt-in installs — no impact on customers who don't use them.

## Phase gate to Phase 4

See `docs/10-roadmap-overview.md`. Most important: pitch tracker attach rate >50% on existing accounts. If it's lower, Phase 4 won't have the foundation it needs.
