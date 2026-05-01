# 12 — Phase 3 (months 7–15): Pitch tracker

## What and why

Phase 1 made the firm great at *reporting on coverage that already happened*. Phase 1.5 extended that to social. Phase 2 deepened the reporting workflow with templates, analytics, and a client portal. None of that helps the agency answer the question that drives their business: **which of our pitches actually worked?**

Phase 3 closes the loop. Every pitch the agency sends becomes trackable; every reply gets classified; every piece of coverage gets attributed back to the pitch (or correctly identified as organic) that caused it. From this data three compounding advantages emerge:

1. **Per-journalist intelligence.** "Sarah Perez replied to 3 of our last 4 pitches" becomes a knowable fact, not folklore.
2. **Per-workspace pattern learning.** "Pitches under 200 words got 23% reply rate; over 300, 8%" becomes the kind of statistic agencies use to coach junior practitioners.
3. **A real journalist database, compiled from real outcomes.** Every workspace's data improves the cross-customer ranking signal, which improves every workspace's targeting on the next campaign.

The wedge against Cision/Muck Rack tightens here. Their journalist databases are static contact dumps; ours becomes a living model of who responds to what.

## Scope and timing

Phase 3 is the largest single phase in the roadmap. Total duration: **7-8 months** of build work split into two parts.

**Part 1 — pitch tracker core (months 7-12, ~12 weeks).** This document. Covers data model, capture mechanics, reply tracking, attribution, analytics, and journalist database expansion. Required before campaigns can be built on top.

**Part 2 — campaign workflow (months 12-15, ~10 weeks).** See `docs/12a-phase-3-campaign-workflow.md`. The strategic + creative work that produces pitches: brief intake, AI-generated strategy, target ranking, personalized drafting, send mechanics, retrospective insights. Sits on top of the pitch tracker and reuses every primitive it establishes.

The build order is strict: nothing in Part 2 can ship before Part 1 is stable. Most importantly, the AI ranking and drafting in Part 2 cold-start poorly without the data Part 1 accumulates (per-author response rates, per-workspace attribution patterns, cross-customer aggregate signals).

## Critical guardrails introduced in this phase

These join the existing CLAUDE.md guardrails and apply to every Phase 3 surface.

**Pitches never send automatically.** Every individual pitch requires a human click on a "Send" button before it leaves Beat. There is no auto-send mode, no scheduled bulk-send, no "approve and forget" workflow. The cost of one wrong auto-sent pitch (sent to the wrong journalist, with the wrong client name, with a hallucinated quote) is potentially permanent damage to a relationship. The cost of a human click is ~2 seconds. This is non-negotiable.

**Journalist opt-out is honored end-to-end.** A journalist can request removal via `beat.app/journalist-optout`. The opt-out flow soft-deletes the `authors` row (`deleted_at` set), removes them from search and ranking, and prevents future pitches. The opt-out ID is the apex truth — it overrides any per-workspace contact list.

**Attribution suggestions require human confirmation by default.** When the system identifies a likely pitch→coverage match, it surfaces it as a *suggestion*. The user confirms or rejects. High-confidence matches (score ≥0.85) can auto-attribute under a workspace setting that's off by default; rejecting an auto-attribution must roll back the attribution cleanly.

**Cross-customer aggregate data is anonymized.** The journalist response rate the system shows ("typically replies to 14% of pitches across all PR users") is computed from aggregated data and never reveals which workspaces those pitches came from. No workspace ever sees another workspace's specific pitches, replies, or outcomes.

## Features

This phase ships five features in this order:

1. **Pitch CRM core** — data model, manual logging, BCC capture, browser extension capture
2. **Reply tracking** — inbound routing, threading, classification
3. **Coverage attribution** — matching pitches to coverage, suggestion surfacing, manual confirmation
4. **Pitch analytics** — dashboards, per-journalist outcomes, per-workspace patterns
5. **Journalist database expansion** — per-author enrichment, profile pages, opt-out flow

The campaign workflow (Part 2, `12a`) follows after.

---

## 12.1 — Pitch CRM core

### Motivation

Today agencies track pitches in spreadsheets, email drafts folders, and senior practitioners' heads. The status of "which journalists got the Acme Series B pitch" is a question someone has to think about for ten minutes to answer. Beat's job here is to make that knowable in one click.

The pitch CRM is the substrate for everything else in Phase 3 — reply tracking, attribution, analytics, the campaign workflow. Get this right and the rest follows.

### Data model

```sql
CREATE TABLE pitches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- Source of truth for the pitch
    subject         TEXT NOT NULL,
    body            TEXT NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL,
    sender_email    TEXT NOT NULL,                  -- the user's email address used to send
    -- Capture metadata
    source          TEXT NOT NULL CHECK (source IN (
      'manual','gmail_addon','outlook_addon','browser_extension','imported','campaign'
    )),
    external_message_id TEXT,                       -- RFC 822 Message-ID for thread matching
    -- Optional linkage to campaigns (Part 2)
    campaign_id     UUID REFERENCES campaigns(id) ON DELETE SET NULL,
    -- Status
    status          TEXT NOT NULL DEFAULT 'sent' CHECK (status IN (
      'sent','replied','covered','no_response','withdrawn'
    )),
    -- Metadata
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_pitches_workspace_client ON pitches(workspace_id, client_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_pitches_sent_at ON pitches(workspace_id, sent_at DESC);
CREATE INDEX idx_pitches_external_message_id ON pitches(external_message_id) WHERE external_message_id IS NOT NULL;
CREATE INDEX idx_pitches_campaign ON pitches(campaign_id) WHERE campaign_id IS NOT NULL;

CREATE TABLE pitch_recipients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pitch_id        UUID NOT NULL REFERENCES pitches(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Who got it
    author_id       UUID REFERENCES authors(id) ON DELETE SET NULL,
    recipient_email TEXT NOT NULL,                  -- raw email; author_id may be null if we haven't resolved
    recipient_name  TEXT,
    role            TEXT NOT NULL CHECK (role IN ('to','cc','bcc')),
    -- Per-recipient state
    delivery_status TEXT CHECK (delivery_status IN (
      'sent','delivered','bounced','blocked','unknown'
    )),
    opened_at       TIMESTAMPTZ,                    -- via tracking pixel when Beat-sent
    replied_at      TIMESTAMPTZ,                    -- set by reply matcher
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pitch_recipients_pitch ON pitch_recipients(pitch_id);
CREATE INDEX idx_pitch_recipients_author ON pitch_recipients(author_id) WHERE author_id IS NOT NULL;
CREATE INDEX idx_pitch_recipients_email ON pitch_recipients(workspace_id, recipient_email);
```

Two things worth highlighting:

**`external_message_id` is the bridge between the user's email infrastructure and Beat's records.** When the user sends a pitch from their own Gmail/Outlook (the common case), Beat captures the message via BCC or browser extension and stores the RFC 822 `Message-ID` header. When a reply lands, the reply's `In-Reply-To` and `References` headers identify which pitch it threads against. This is how reply matching works without requiring users to forward replies manually.

**`author_id` is nullable on `pitch_recipients`.** Many pitches go to email addresses that don't yet match anyone in our `authors` table. The orchestration layer attempts resolution at ingest time (looking up by email, then by name + outlet); if no match, the recipient is preserved with raw email + name and the `author_id` is set later when the journalist enters the database through other paths.

### Capture modes

Three capture paths, supporting how the user actually sends pitches:

**Manual logging.** A form in the UI: who, when, subject, body, recipients. Useful for backfill and for users who don't trust automated capture. Lowest fidelity but always available.

**BCC capture.** The user BCCs a workspace-specific address (e.g., `acme-corp.bcc@capture.beat.app`) on every pitch. Beat receives the message via Postmark inbound, extracts the metadata, creates the `pitches` and `pitch_recipients` rows. Works with any email client, no plugin required. The BCC address is per-workspace, not per-user, so the system can attribute correctly when a junior sends on behalf of a senior.

**Browser extension.** A small Chrome/Firefox extension that detects when the user is composing email in Gmail/Outlook web, surfaces a "log to Beat" button, and captures the metadata at send time. Higher fidelity than BCC (catches drafts, attaches to client immediately), no email-routing needed, but requires extension install. Optional for the user who finds BCC sufficient.

The BCC address is the pragmatic default. The browser extension is for power users.

### API surface

The pitch CRM exposes:

- `GET /v1/pitches` — list, filterable by client, campaign, status, date range
- `GET /v1/pitches/:id` — full detail with recipients, replies, attributions
- `POST /v1/pitches` — manual creation
- `PATCH /v1/pitches/:id` — edit subject, body, recipient list (only allowed for not-yet-sent or recently-sent pitches; aged pitches are immutable to preserve attribution truth)
- `DELETE /v1/pitches/:id` — soft delete
- `POST /v1/inbound/bcc-capture` — Postmark webhook target for BCC inbound
- `POST /v1/extensions/log-pitch` — browser extension target

Activity events fire per `docs/15-additions.md` §15.2: `pitch.sent`, `pitch.edited`, `pitch.deleted`.

---

## 12.2 — Reply tracking

### Motivation

A pitch without a reply is lost work. A pitch with a reply that the agency missed is worse — the journalist remembers, the agency doesn't. Phase 3's second job is to surface every reply, route it to the right context, and classify it so the agency can act fast.

### Data model

```sql
CREATE TABLE pitch_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    pitch_id        UUID NOT NULL REFERENCES pitches(id) ON DELETE CASCADE,
    pitch_recipient_id UUID REFERENCES pitch_recipients(id) ON DELETE SET NULL,
    -- The reply itself
    received_at     TIMESTAMPTZ NOT NULL,
    from_email      TEXT NOT NULL,
    from_name       TEXT,
    subject         TEXT,
    body            TEXT NOT NULL,
    external_message_id TEXT,                       -- the reply's Message-ID
    in_reply_to     TEXT,                           -- the pitch's Message-ID, for threading
    -- Classification
    classification  TEXT CHECK (classification IN (
      'interested','declined','more_info','auto_reply','unrelated','unclear'
    )),
    classification_confidence TEXT CHECK (classification_confidence IN ('high','medium','low')),
    classification_prompt_version TEXT,
    classified_at   TIMESTAMPTZ,
    -- Workflow
    is_user_reviewed BOOLEAN NOT NULL DEFAULT false,
    suggested_followup TEXT,                        -- AI-generated suggested response
    -- Metadata
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pitch_replies_pitch ON pitch_replies(pitch_id);
CREATE INDEX idx_pitch_replies_workspace_unreviewed ON pitch_replies(workspace_id, is_user_reviewed) WHERE is_user_reviewed = false;
```

### Threading

Replies match to pitches via the standard email thread headers:

1. The reply's `In-Reply-To` header should equal the original pitch's `Message-ID`. This is the canonical match.
2. The reply's `References` header is a chain that includes the original pitch's `Message-ID`. Match via this when `In-Reply-To` is missing or replaced (some clients break threading).
3. As a fallback, fuzzy-match on `(workspace_id, from_email, subject_normalized)` within a 30-day window. Subject normalization strips `Re:` prefixes and quoted-text markers.

The system tracks confidence in the match. Headers-based match is high-confidence. Fuzzy fallback is medium-confidence and surfaces a "review match" prompt to the user.

### Classification

Inbound replies run through `prompts/reply-classification-v1.md` (or its cost-engineered successor `reply-classification-v1-1.md`). The classifier returns one of six classes:

- **interested** — the journalist wants to engage. Time-sensitive; surfaces with high priority.
- **declined** — politely turning down. Capture the reason if extractable.
- **more_info** — wants additional materials, dates, or clarification before deciding.
- **auto_reply** — out-of-office, holiday, etc. Surfaces as low-priority; suppress notifications.
- **unrelated** — the reply is from the journalist but doesn't address the pitch (rare but happens).
- **unclear** — model isn't confident; user reviews.

Classification respects the cost engineering pass (see `docs/18-cost-engineering.md`): a regex pre-classifier catches obvious auto-replies and bounces before any LLM call, and Haiku handles the bulk of the remaining classification with Sonnet escalation only when Haiku reports low confidence.

### API surface

- `GET /v1/replies` — list, filterable by pitch, classification, reviewed state
- `GET /v1/replies/:id` — full detail with the threaded pitch and recipient
- `PATCH /v1/replies/:id` — update classification (user override), mark reviewed, edit suggested_followup
- `POST /v1/inbound/reply-capture` — Postmark webhook target for reply inbound

Activity events: `pitch.replied`, `reply.classified`, `reply.reviewed`.

---

## 12.3 — Coverage attribution

### Motivation

The killer question: "which of our pitches turned into coverage?" Today this is unanswerable except by the senior practitioner who remembers. Phase 3 makes it computable.

The hard part is judgment. Some attributions are obvious (a journalist who replied "interested" two days before publishing a piece on the client). Some are inferred (the journalist was on the pitch list, never replied, but published two weeks later — pitched-into-the-stream is a real pattern). Some are noise (the journalist would have written about the client regardless). The system surfaces *suggestions*; humans confirm.

### Data model

```sql
CREATE TABLE pitch_coverage_attributions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    pitch_id        UUID NOT NULL REFERENCES pitches(id) ON DELETE CASCADE,
    coverage_item_id UUID NOT NULL REFERENCES coverage_items(id) ON DELETE CASCADE,
    -- Suggestion vs confirmed
    status          TEXT NOT NULL DEFAULT 'suggested' CHECK (status IN (
      'suggested','confirmed','rejected'
    )),
    confidence      NUMERIC(3,2) NOT NULL,          -- 0.00-1.00
    rationale       TEXT,                           -- AI-generated explanation
    attribution_prompt_version TEXT,
    -- Audit
    suggested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at     TIMESTAMPTZ,
    reviewed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pitch_id, coverage_item_id)
);

CREATE INDEX idx_attribution_workspace_status ON pitch_coverage_attributions(workspace_id, status);
CREATE INDEX idx_attribution_pitch ON pitch_coverage_attributions(pitch_id);
CREATE INDEX idx_attribution_coverage ON pitch_coverage_attributions(coverage_item_id);
```

### How attribution runs

When a new `coverage_items` row appears (via Phase 1 ingestion or Phase 4 monitoring), the attribution worker runs:

1. **Find candidate pitches.** SQL filter: pitches sent to the same author OR pitches sent within 30 days for the same client. Typically narrows to 0-10 candidates.
2. **Embedding pre-filter.** Embed the article and embed each candidate pitch (or use cached embeddings). Top candidates by cosine similarity proceed.
3. **LLM judgment.** For top candidates, the attribution prompt produces a confidence score and rationale per (pitch, article) pair. See `prompts/pitch-attribution-v1.md`.
4. **Persistence.** Suggestions with confidence ≥0.6 are written as `suggested`; below that, no row is created.

The cost engineering pass (see `docs/18-cost-engineering.md`) layers Haiku for clear matches, Sonnet only for the medium-similarity ambiguous cases. Most attributions resolve at Haiku.

### Surfacing and confirmation

The dashboard surfaces unconfirmed suggestions as an alert (`attribution.pending`). The user reviews each suggestion and confirms, rejects, or edits. Confirmed attributions update the pitch's status to `covered` and roll into the analytics layer.

Auto-confirmation is opt-in per workspace and gated by a confidence threshold (default 0.85). Even with auto-confirm on, the activity feed shows the attribution and the user can revoke.

### API surface

- `GET /v1/attributions` — list pending/confirmed/rejected, by client or campaign
- `PATCH /v1/attributions/:id` — confirm, reject, or edit confidence
- `POST /v1/attributions/:id/revoke` — undo a confirmed attribution

Activity events: `attribution.suggested`, `attribution.confirmed`, `attribution.rejected`.

---

## 12.4 — Pitch analytics

### Motivation

The data accumulates fast — a single agency runs hundreds of pitches per quarter once they're using the tool. The analytics layer makes that data useful: reply rate trends, per-journalist responsiveness, per-pattern win rates. This is the surface the founder of the agency reads on Friday afternoon to understand what's working.

### Data model

The analytics layer is mostly **derived** from the operational tables (`pitches`, `pitch_recipients`, `pitch_replies`, `pitch_coverage_attributions`). A nightly batch job pre-computes rollups for fast dashboard rendering:

```sql
CREATE TABLE pitch_analytics_rollups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    scope           TEXT NOT NULL CHECK (scope IN (
      'workspace','client','campaign','user','author','outlet'
    )),
    scope_id        UUID,                           -- references depend on scope
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    -- Volumes
    pitches_sent    INT NOT NULL DEFAULT 0,
    pitches_with_reply INT NOT NULL DEFAULT 0,
    pitches_with_coverage INT NOT NULL DEFAULT 0,
    -- Rates
    reply_rate      NUMERIC(5,4),
    coverage_rate   NUMERIC(5,4),
    -- Pattern breakdowns (JSONB to keep schema flexible)
    by_subject_length JSONB NOT NULL DEFAULT '{}'::jsonb,
    by_body_length  JSONB NOT NULL DEFAULT '{}'::jsonb,
    by_send_dow     JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Versioning
    rollup_version  INT NOT NULL DEFAULT 1,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rollups_lookup ON pitch_analytics_rollups(workspace_id, scope, scope_id, period_start);
CREATE UNIQUE INDEX uq_rollups_scope ON pitch_analytics_rollups(workspace_id, scope, COALESCE(scope_id::text, 'null'), period_start, period_end);
```

The rollup table lets us answer "reply rate for this client over the last 90 days, broken down by subject length" without scanning the operational tables. Rollups recompute nightly and on-demand when a campaign closes.

### Surfaces

Three primary analytics surfaces in Phase 3:

**Pitch analytics dashboard.** Workspace-level overview: total pitches, reply rate, coverage rate, top journalists by responsiveness, pattern observations. The default time window is 90 days; users can shift to per-client, per-campaign, or per-user.

**Per-client pitch panel.** Folds into the client dashboard from `docs/16-client-dashboard.md`. Adds a "pitch activity" tab showing pitches in flight, awaiting reply, attributed to coverage.

**Per-campaign retro.** Auto-generated when a campaign closes via `prompts/campaign-insights-v1.md` (see `docs/12a-phase-3-campaign-workflow.md`). The retro pulls from the rollups plus the campaign-specific outcome data.

### API surface

- `GET /v1/analytics/pitches` — workspace-level rollups with filters
- `GET /v1/analytics/pitches/by-client/:client_id` — per-client cuts
- `GET /v1/analytics/pitches/by-campaign/:campaign_id` — per-campaign cuts
- `GET /v1/analytics/pitches/by-author/:author_id` — per-journalist cuts

---

## 12.5 — Journalist database expansion

### Motivation

Phase 1's `authors` table accumulated journalists as a side effect of coverage extraction — every byline became a row. By the start of Phase 3, the agency has thousands of authors per workspace, sparsely enriched. The campaign workflow (Part 2) needs them densely enriched: recent bylines, beats, response patterns, contact methods, social presence.

This feature does two things: enriches existing rows on a schedule, and adds the journalist profile UI surface that turns the database into something the agency *uses*, not just stores.

### Data model additions

The existing `authors` table from Phase 1 (`docs/03-data-model.md`) gets extended:

```sql
ALTER TABLE authors ADD COLUMN IF NOT EXISTS preferred_topics TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE authors ADD COLUMN IF NOT EXISTS pitch_response_rate_global NUMERIC(5,4);
ALTER TABLE authors ADD COLUMN IF NOT EXISTS pitch_response_rate_workspace_scoped JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE authors ADD COLUMN IF NOT EXISTS last_byline_at TIMESTAMPTZ;
ALTER TABLE authors ADD COLUMN IF NOT EXISTS bylines_last_90d INT NOT NULL DEFAULT 0;
ALTER TABLE authors ADD COLUMN IF NOT EXISTS opt_out_at TIMESTAMPTZ;

CREATE INDEX idx_authors_preferred_topics ON authors USING GIN (preferred_topics);
CREATE INDEX idx_authors_recent_active ON authors(workspace_id, last_byline_at DESC) WHERE deleted_at IS NULL;
```

Plus a new enrichment cache table so the campaign workflow can rank without expensive lookups:

```sql
CREATE TABLE journalist_enrichment_cache (
    author_id       UUID PRIMARY KEY REFERENCES authors(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Cached enrichment (refreshed by background job)
    recent_articles_summary JSONB NOT NULL DEFAULT '[]'::jsonb,
        -- [{date, headline, summary, url}, ...] last 90 days
    inferred_beats  TEXT[] NOT NULL DEFAULT '{}',
    tone_descriptor JSONB,                          -- output of pitch-tone-analysis prompt
    tone_analyzed_at TIMESTAMPTZ,
    -- Metadata
    refresh_window_starts_at TIMESTAMPTZ NOT NULL,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_refresh_at TIMESTAMPTZ
);

CREATE INDEX idx_enrichment_next_refresh ON journalist_enrichment_cache(next_refresh_at) WHERE next_refresh_at IS NOT NULL;
```

The `next_refresh_at` is set by frequency tier (per `docs/18-cost-engineering.md`): active journalists refresh monthly, mid-frequency quarterly, low-frequency on-demand.

### Profile UI surface

A journalist profile page accessible from any byline reference in the system:

- Bio synthesized from public sources
- Last 90 days of bylines, sorted reverse-chronological
- Beats (stated and inferred)
- Per-workspace pitch history with this journalist (if any)
- Aggregate response rate (anonymized cross-customer)
- Tone descriptor (from `prompts/pitch-tone-analysis-v1.md`)
- Social handles
- Contact methods

The profile drawer in the campaign target list (`docs/12a-phase-3-campaign-workflow.md`) shows a denser version of the same data.

### Opt-out flow

Public page at `beat.app/journalist-optout`:

1. Journalist enters their email address.
2. System matches against `authors.email` and confirms via emailed verification link.
3. On confirmation, the matching `authors` row(s) get `opt_out_at = now()` and `deleted_at = now()`.
4. SQL pre-filters in ranking and pitching exclude opted-out journalists. The campaign workflow worker double-checks at request time as defense in depth.
5. Confirmation email to the journalist; no further marketing or transactional email.

### API surface

- `GET /v1/authors/:id` — profile data
- `PATCH /v1/authors/:id` — workspace-scoped notes (per-workspace `author_notes` table, not described here for brevity)
- `POST /v1/journalist-optout/request` — public, sends verification email
- `POST /v1/journalist-optout/confirm` — public, processes verification token

---

## Build sequence

Approximately 12 weeks for Part 1 (this doc). Builder is one engineer + Claude Code, similar to other phases.

| Week | Focus |
|---|---|
| 1 | Data model migrations (`pitches`, `pitch_recipients`, `pitch_replies`, `pitch_coverage_attributions`, `pitch_analytics_rollups`, `journalist_enrichment_cache`); base CRUD APIs |
| 2 | Manual pitch logging UI; pitches list and detail pages |
| 3 | BCC capture path: Postmark inbound webhook, parse, persist; address allocation per workspace |
| 4 | Browser extension MVP (Chrome): detect Gmail/Outlook compose, send-time hook, log to Beat |
| 5 | Reply tracking: inbound webhook, threading via Message-ID, `pitch_replies` persistence |
| 6 | Reply classification: prompt + eval set + classification UI; user-review flow for unclear cases |
| 7 | Coverage attribution: candidate finder, embedding pre-filter, attribution worker, prompt + eval |
| 8 | Attribution UI: suggestion review, confirm/reject, auto-confirm setting |
| 9 | Pitch analytics rollup batch job; analytics dashboard MVP |
| 10 | Journalist database expansion: enrichment cache, background refresh job, profile page |
| 11 | Opt-out flow: public page, verification, hard-filter integration |
| 12 | Polish, eval expansion, dogfood end-to-end on 3 real clients. Fix everything that breaks |

If anything slips, weeks 9-10 (analytics polish, profile UI polish) are cut points. Attribution and reply tracking are not negotiable — the campaign workflow in Part 2 depends on both.

## Eval set additions

`docs/06-evals.md` gets new tiers in Phase 3:

**Reply classification.** 50 hand-labeled (reply, expected_class) pairs across all six classes. Hard gate: 90% accuracy on the labeled set; 100% accuracy on the auto_reply class (regex pre-classifier handles most; LLM handles the rest).

**Coverage attribution.** 30 hand-labeled (pitch, coverage, expected_attribution) triples. Hard gate: zero false-confirmed attributions on the eval set when run with auto-confirm threshold 0.85; ≥80% recall on true attributions at confidence ≥0.6.

**Tone analysis.** Per `docs/12a-phase-3-campaign-workflow.md` and `docs/18-cost-engineering.md`. 20 journalist examples with expected descriptors.

The full prompt-by-prompt eval gates live in each prompt's file under `prompts/`.

## Cross-references

- `docs/12a-phase-3-campaign-workflow.md` — Part 2: the campaign workflow that builds on this foundation
- `docs/14-multi-tenancy.md` — pre-flight checklist applies to all new tenant tables (`pitches`, `pitch_recipients`, `pitch_replies`, `pitch_coverage_attributions`, `pitch_analytics_rollups`, `journalist_enrichment_cache`)
- `docs/15-additions.md` — `client_context` is consumed by attribution and (in Part 2) pitch drafting
- `docs/16-client-dashboard.md` — `pitch.awaiting_reply`, `attribution.pending` alerts surface here
- `docs/17-phase-1-5-social.md` — social mentions feed coverage attribution alongside articles
- `docs/18-cost-engineering.md` — model tiering, caching, and batching disciplines apply across every prompt in this phase

## What this phase deliberately does NOT include

- **Outbound pitch drafting on its own.** That's Part 2 (campaign workflow). Standalone pitch drafting outside a campaign is deferred — campaigns are the unit of strategic work agencies actually do.
- **Auto-send.** Always human-in-the-loop. No exceptions.
- **Coverage monitoring.** Phase 4. The pitch tracker assumes coverage arrives via Phase 1 ingestion or Phase 1.5 social capture.
- **Salesforce/HubSpot CRM integrations.** Phase 4.
- **Predictive coverage probability before send.** We don't have data to calibrate honest probabilities. The campaign workflow uses confidence labels (high/medium/low) instead.
- **AI pitch writer as a standalone product.** Folded into the campaign workflow only. Standalone pitch generation outside strategic context produces worse pitches and trains users badly.
