# 15 — Additions: client context, weekly digest, activity instrumentation

This doc adds three features to the original plan. They were originally scoped as "dogfood-phase" features but have product-grade value at every phase, so they're being promoted into the canonical roadmap.

| Feature | Phase | Why this phase |
|---|---|---|
| Client context ("second brain") | Phase 1 | Improves LLM extraction quality; prompt integration is highest value if shipped from day one |
| Activity instrumentation | Phase 1 | Infrastructure-shaped; painful to retrofit, trivial to add early |
| Weekly digest | Phase 2 | Notification feature; benefits from team scopes added in Phase 2 |

Each feature has a full spec below. Build-plan slot-ins are at the end of this doc.

---

## 15.1 — Client context (Phase 1)

### Motivation

Every client has context that lives in the agency's head: key messages, embargo dates, competitor names, journalists with bad history, the spelling of the CEO's preferred nickname, the pronunciation of the company name. Today this lives in scattered Google Docs, Slack threads, and the back of someone's brain.

This matters for Beat for two reasons:

1. **LLM output quality jumps when prompts have context.** A summary written knowing "Acme recently pivoted from B2C to B2B and the CEO prefers 'Mike' over 'Michael'" is materially better than a generic one.
2. **Codifying client knowledge is the first step toward scaling beyond a single person.** The moment an agency hires a junior, the senior's knowledge has to live somewhere.

This is the feature most likely to surprise customers with how much value it creates. Real agency knowledge is mostly contextual.

### Data model

```sql
CREATE TABLE client_context (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL UNIQUE REFERENCES clients(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Structured fields used in prompts and surfaced in UI
    key_messages    TEXT,
    do_not_pitch    TEXT,             -- journalists, outlets, or topics to avoid
    competitive_set TEXT,             -- comma-separated competitor names
    important_dates TEXT,             -- embargo dates, earnings, launches
    style_notes     TEXT,             -- preferred names/spellings, tone preferences
    -- Free-form notes
    notes_markdown  TEXT,
    -- Versioning
    version         INT NOT NULL DEFAULT 1,
    last_edited_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_client_context_workspace ON client_context(workspace_id);
```

The `version` field increments on every save. We don't store full history in v1 (the row itself is the source of truth), but the version number lets us track whether the context used at extraction time matches the current context — useful for debugging "why did this report use stale info."

### Why structured fields plus free-form

The structured fields (key_messages, do_not_pitch, etc.) are deliberately chosen to map directly into the extraction prompt. They're the fields most likely to change LLM output quality. The free-form `notes_markdown` is the catch-all for everything else — agency norms, anecdotes, internal jokes, anything that doesn't fit the structure but matters.

Resist the urge to add more structured fields in v1. Watch what customers actually write in `notes_markdown` for 3 months, then promote patterns into structure if they show up consistently.

### API additions

- `GET /v1/clients/:id/context` — returns the context (or 404 if never set).
- `PUT /v1/clients/:id/context` — upsert. Increments version. Records `last_edited_by_user_id`.
- `GET /v1/clients/:id/context/history` — Phase 2: returns previous versions.

### LLM prompt impact

The article extraction prompt gets a new optional `{{client_context}}` block. Update `prompts/extraction-v1.md` to `extraction-v1.1`:

```
Subject of coverage: {{subject_name}}

{{#if client_context}}
Relevant context about {{subject_name}}:
{{client_context}}
{{/if}}

Article text:
---
{{article_text}}
---
```

Where `{{client_context}}` is rendered as:

```
- Key messages: {{key_messages}}
- Style notes (preferred names/spellings): {{style_notes}}
- Competitive set: {{competitive_set}}
- Recent context: {{notes_markdown_excerpt_300_chars}}
```

Only fields that are non-empty get rendered. If no fields are set, the entire `{{#if}}` block omits.

### Critical: what NOT to put in the prompt

Some fields shouldn't influence extraction:

- `do_not_pitch` — this is for the user's reference, not the LLM's. Including it could bias sentiment.
- `important_dates` — embargo dates aren't relevant to a published article.

The prompt template explicitly excludes these. If someone adds them later, the eval harness should catch the regression (extraction quality drops on the existing golden set when these fields are included).

### Executive summary impact

Update `prompts/executive-summary-v1.md` to `executive-summary-v1.1` to optionally include style notes:

```
Style notes for this client: {{style_notes}}
(e.g., preferred CEO names, brand pronunciation, specific terminology)
```

This catches the "Mike not Michael" class of error that otherwise requires user editing on every report.

### UI surface

- New route `/clients/:id/context` — edit form with the structured fields above.
- Context preview surfaces on every report builder page (collapsed by default, expandable).
- "Updated 3 days ago by Sarah" attribution in the header.
- Markdown editor for `notes_markdown` (use a simple library — TipTap or Milkdown). Render preview alongside.

### Eval impact

The golden set in `docs/06-evals.md` needs:

1. **5 new items with associated client context** — verify the extraction respects style notes (e.g., "Mike" vs "Michael" appears correctly in the summary).
2. **A regression test** — the existing 50 items still pass when context is empty. We can't accidentally break the no-context path.
3. **A "context bleed" test** — providing irrelevant context for an article shouldn't change extraction outputs that the article itself dictates (the headline, the publish date, etc.). Only judgment fields (summary, sentiment, prominence) are allowed to vary.

Hard gate: no regression on the existing golden set. New context-aware tests must score ≥ 90% on style-note adherence.

### Acceptance criteria

- A user can write a markdown notes block in under 60 seconds for a client they know well.
- An extraction with context produces measurably better summaries than without — verified by the eval harness, not vibes.
- The "Mike not Michael" class of error happens < 5% as often as it did pre-context.
- Context UI loads on the report builder without adding > 100ms to page load.

### Risks to watch

- **Stale context drifts silently.** Users update key_messages once, then forget. Surface "context last updated 47 days ago" warnings on the report builder.
- **Context contradicts reality.** A user writes "we never work with TechCrunch" in do_not_pitch, but the article being extracted is from TechCrunch. The prompt explicitly excludes do_not_pitch from extraction (see above), but watch for confusion in UI surfacing.
- **PII / confidential info in notes.** The notes field could become a dumping ground for things that shouldn't be there (client passwords, internal contracts). Add a UI warning: "this content is sent to our AI processing pipeline and stored." Don't be sneaky about it.

---

## 15.2 — Activity instrumentation (Phase 1)

### Motivation

Two reasons this is Phase 1 infrastructure, not a Phase 2 feature:

1. **Internal: data-driven product decisions.** Without activity data, every roadmap conversation is "I think users want X." With it, "users open the report builder 4.2x per report and edit 23% of extracted items" — much better priors.
2. **External: customer-visible analytics.** Phase 2 ships per-client analytics; Phase 4 ships pitch analytics. Both need historical data. If we don't instrument from day one, those features launch with empty charts and weak insights for the first 6 months.

The painful-to-retrofit nature: every controller, every worker, every UI action that matters needs to fire an event. Adding event firing across 50 endpoints later is a slog. Adding it from the first endpoint is one extra line per handler.

### Data model

```sql
CREATE TABLE activity_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_type      TEXT NOT NULL CHECK (actor_type IN ('user','system','worker','api','portal_user')),
    kind            TEXT NOT NULL,             -- e.g. 'report.created', 'coverage.extracted'
    target_type     TEXT,                      -- e.g. 'report', 'coverage_item'
    target_id       UUID,
    duration_ms     INT,                       -- for actions that have a duration (e.g. extraction)
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_workspace_time ON activity_events(workspace_id, occurred_at DESC);
CREATE INDEX idx_activity_kind_time ON activity_events(kind, occurred_at DESC);
CREATE INDEX idx_activity_target ON activity_events(target_type, target_id);
```

### How this differs from `audit_events`

`audit_events` (already in Phase 1 from `docs/03-data-model.md`) and `activity_events` look similar but have different jobs:

| | `audit_events` | `activity_events` |
|---|---|---|
| Purpose | Compliance, security, "who did what" | Product analytics, behavior, performance |
| Granularity | One per significant action | Many per workflow |
| Retention | Permanent | 18 months (older rolls into aggregates) |
| Includes IP / UA | Yes | No |
| Includes durations | No | Yes |
| Customer-visible | Phase 4 export | Powers customer-facing analytics |

Both fire on the same actions in many cases, but the schemas serve different consumers. Don't conflate them.

### Standard event taxonomy

Phase 1 events to fire (incomplete list — every PR adding a meaningful action adds events):

```
# Auth & workspace
user.signed_up
user.logged_in
workspace.created
workspace.branding_updated

# Clients
client.created
client.updated
client.deleted
client.context_updated         # for §15.1

# Reports
report.created
report.urls_added              # metadata: count
report.coverage_extracted      # per-item; duration_ms = extraction time
report.coverage_edited         # metadata: fields_edited
report.coverage_retried
report.coverage_dismissed
report.summary_generated       # metadata: prompt_version
report.summary_edited
report.generated               # metadata: total_items, duration_ms = full pipeline time
report.pdf_downloaded
report.shared
report.share_revoked

# System / cost tracking
llm.call_completed             # metadata: model, prompt_version, input_tokens, output_tokens, cost_usd
extraction.failed              # metadata: stage, error_class
render.failed                  # metadata: stage, error_class
```

Define these as constants in `app.beat.activity.EventKinds` so they're not stringly-typed throughout the codebase.

### Implementation

A single `ActivityRecorder` service injected wherever events fire:

```java
@Service
public class ActivityRecorder {
    public void record(String kind, UUID targetId, String targetType,
                       Map<String, Object> metadata) { ... }

    public void record(String kind, UUID targetId, String targetType,
                       Duration duration, Map<String, Object> metadata) { ... }
}
```

Events write asynchronously (fire-and-forget into a small in-memory buffer flushed every few seconds). A bug in the recorder must not break the user's request. Failures are logged, not propagated.

### Aggregation strategy

Raw events stay in `activity_events` for 18 months. After that, aggregates roll up into:

- `activity_metrics_daily` — counts by `(workspace_id, kind, day)`. Phase 2.
- `activity_metrics_monthly` — same, monthly. Phase 2.

These power dashboards without scanning the raw table. We don't build them in Phase 1 — just the raw event capture. The aggregations land alongside `client_metrics_monthly` in Phase 2 §11.5.

### What this enables across phases

| Phase | Use |
|---|---|
| 1 | Internal: cost per workspace, extraction P95, error rates by stage. Founder dashboard. |
| 2 | Per-client analytics charts populated with real history. Weekly digest data. |
| 3 | Pitch analytics populated with real history; pitch funnel charts. |
| 4 | Customer-facing usage dashboards, audit log export, anomaly detection. |

### Privacy & compliance

`activity_events` does NOT contain:

- PII beyond `user_id` (which is itself a UUID).
- Article content, summaries, or any extracted content.
- Pitch bodies or reply text.
- Email addresses.

It contains: action kinds, target IDs, timing, and small structured metadata (counts, model names, error classes).

If a workspace deletes itself, all their `activity_events` rows go too (FK cascade). Same for users. This is GDPR-clean by default.

### Acceptance criteria

- Every endpoint that mutates customer state fires at least one event.
- Async recording: a recorder failure adds < 5ms to request latency and never propagates.
- Founder dashboard shows: daily extractions, daily reports generated, cost per workspace, P95 extraction latency, top error classes.
- Schema migration to add new event kinds requires zero application code changes (just add a constant).

---

## 15.3 — Weekly digest (Phase 2)

### Motivation

The feature that makes the tool stick. Without it, Beat is a tool the agency *remembers* to use. With it, Beat reaches out to them with useful information they didn't know to ask for.

Format: an automated email every Monday morning summarizing the past week across all clients in a workspace. Personalized per recipient.

### What's in the digest

Each section is conditional — empty sections are omitted, not shown as "0 items":

1. **Coverage roundup** — new coverage tracked last week, grouped by client. Tier-1 placements highlighted.
2. **Pitch outcomes** (Phase 3+) — replies received, positive responses, attribution suggestions awaiting confirmation.
3. **Inbox queue** — uncategorized inbox items that have been pending > 5 days.
4. **Reports** — reports generated last week; reports overdue (period closed but not yet generated).
5. **Notable activity** — first-time mentions, surprise tier-1s, journalists reaching out.
6. **Next week** — reports due in the coming 7 days, scheduled monitoring searches.

### Tone

The digest is *informational*, not promotional. No "🎉 Amazing week!" copy. No engagement metrics. The voice mirrors the executive summary in `prompts/executive-summary-v1.1`: confident, factual, neutral. Hayworth PR's owner shouldn't have to flinch reading it.

### Data model

```sql
CREATE TABLE digest_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    cadence         TEXT NOT NULL DEFAULT 'weekly' CHECK (cadence IN ('daily','weekly','monthly','off')),
    delivery_day    INT,                          -- 0=Sunday..6=Saturday for weekly
    delivery_hour_local INT NOT NULL DEFAULT 8,   -- 0..23
    timezone        TEXT NOT NULL DEFAULT 'America/Los_Angeles',
    scope           TEXT NOT NULL DEFAULT 'all_clients' CHECK (scope IN ('all_clients','my_clients','specific')),
    scoped_client_ids UUID[],                     -- only when scope='specific'
    sections        TEXT[] NOT NULL DEFAULT '{coverage,inbox,reports,activity,next_week}',
    last_sent_at    TIMESTAMPTZ,
    last_send_status TEXT,                        -- 'delivered','failed','skipped_empty'
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, workspace_id)
);

CREATE INDEX idx_digest_due ON digest_subscriptions(cadence, last_sent_at)
    WHERE cadence != 'off';
```

### Scheduling

Spring `@Scheduled` job runs every 15 minutes. For each subscription due (based on cadence + delivery_day + delivery_hour_local + timezone), enqueue a digest job. The job:

1. Computes content for the workspace + scope + period.
2. Renders HTML email via the same Handlebars approach as PDFs.
3. Sends via Resend (or Postmark — both work; pick one).
4. Records send status; updates `last_sent_at`.

Skip-conditions:

- The week's content is empty across all configured sections → mark `skipped_empty`, don't send. Empty digests train people to ignore the feed.
- The user is on a paused/expired subscription → don't send.
- Send failures retry once after 1 hour, then `failed`.

### Personalization

Three scope options:

- **all_clients** (default for owner) — everything in the workspace.
- **my_clients** — only clients the user has been assigned to (assignment is a Phase 2 concept; for now, defaults to "all_clients" if not set).
- **specific** — manually picked client IDs.

This matters at agencies with > 3 staff. The owner wants the firm-wide view; account leads want their slice.

### API additions

- `GET /v1/digest/subscription` — current user's subscription for the active workspace.
- `PUT /v1/digest/subscription` — upsert.
- `POST /v1/digest/preview` — generate the digest as it would arrive *now*; useful for setup and debugging.
- `POST /v1/digest/send-now` — trigger an immediate send (admin-only).

### UI surface

- `/settings/digest` — subscription form: cadence, day, time, scope, sections.
- Send-test-now button.
- Last-sent timestamp.

### LLM/prompt impact

For the **notable activity** section, an LLM call generates a 2–3 sentence narrative summarizing the week. A new prompt:

```
prompts/digest-narrative-v1.md
```

Inputs: structured stats from the week (coverage counts, sentiment shifts, new outlets). Output: a brief narrative paragraph that acknowledges the week honestly. Same anti-hyperbole rules as the executive summary prompt.

This is the lowest-stakes LLM call in the system (digest, not client-facing), so it's a good place to test prompt refinements before they land in higher-stakes places.

### Risks

- **Email deliverability.** Resend is fine for transactional but you're now sending many emails on a schedule. Watch sender reputation. Use a dedicated subdomain (`digest.beat.app`) and set up DKIM/SPF/DMARC properly.
- **Notification fatigue.** Unsubscribe link in every email. One-click switch from weekly → monthly. Track open rates per cadence; if weekly drops below ~30% open after 6 weeks, default new users to monthly.
- **Empty-week problem in slow seasons.** Don't pretend a quiet week was busy. The skip-when-empty logic prevents this on really empty weeks; the narrative prompt prevents it on slow-but-not-empty weeks.

### Acceptance criteria

- A new user can configure their digest in < 30 seconds.
- First Monday after configuring, a digest arrives within their configured time window ± 30 minutes.
- Open rate ≥ 40% sustained over 6 weeks of testing (industry baseline for B2B transactional digests).
- Unsubscribe-to-pause rate < 10%.
- The digest content for a known sample week matches a manual review (no fabricated stats; tone matches voice guide).

---

## Build-plan slot-ins

Updates to `docs/08-build-plan.md`:

### Week 2 additions

After "Audit events written for signup, login, client.created/updated/deleted":

> - **`activity_events` table created (per `docs/15-additions.md` §15.2).** `ActivityRecorder` service implemented. First events fire from auth and client CRUD endpoints.

### Week 4 additions

After "Worker: dequeues, fetches article (without LLM yet — placeholder data), updates row to `done`":

> - **Activity events fire from extraction worker** with `kind='report.coverage_extracted'`, `duration_ms` set.
> - **`llm.call_completed` events fire from the Anthropic client wrapper** with model, prompt_version, tokens, cost.

### Week 5 additions

Add a new bullet to deliverables:

> - **Client context UI and API per `docs/15-additions.md` §15.1.** `client_context` table created. Edit form on `/clients/:id/context`. Context renders on report builder page (collapsed). Extraction worker reads context and renders into the prompt.

Update acceptance:

> - Context can be saved for a client and is used in the next extraction's LLM call (verifiable in `activity_events.metadata.prompt_version` showing the new context-aware version).

### Week 7 additions

After "Eval harness extended with `SummaryEvalTest`":

> - **Eval harness extended to cover client context paths per `docs/15-additions.md` §15.1.** Five new golden items with context. Regression test on the no-context golden set. Context-bleed test.

### Week 9 additions

Add a new bullet to deliverables:

> - **Founder dashboard** at `/admin/dashboard` (gated to internal users only). Pulls from `activity_events`. Shows daily extractions, daily reports generated, cost per workspace, P95 extraction latency, top error classes.

---

## Phase 2 build plan additions

The weekly digest lands in Phase 2. Suggested fit: alongside §11.6 annual billing (similar shape — scheduled jobs, transactional email).

Add a new feature to `docs/11-phase-2-features.md`:

### 11.8 — Weekly digest

Move §15.3 of this doc into `docs/11-phase-2-features.md` as section 11.8 when Phase 2 starts. The spec above is canonical; copy-paste the data model, API, scheduling, and acceptance criteria sections.

The activity_events foundation is already in place (Phase 1), so the digest builds on existing data with no schema changes for the data sources.

---

## CLAUDE.md routing update

Append to "Where to find things":

```markdown
- **Additions to canonical roadmap (client context, instrumentation, digest):** `docs/15-additions.md`
```

Also update the "Critical guardrails" section to add:

```markdown
7. **Activity events fire on every meaningful action.** When adding new mutations, add the corresponding `activity_events` write. The founder dashboard, customer analytics, and Phase 3+ features depend on this discipline. See `docs/15-additions.md` §15.2.
```

---

## Eval set additions summary

For convenience, all the eval-related changes implied by these features:

**From §15.1 (client context):**
- 5 new items with associated client context
- Regression test on existing 50-item set with empty context
- Context-bleed test (irrelevant context shouldn't change ground-truth fields)

**From §15.3 (digest narrative — Phase 2):**
- 10 example weeks with hand-written acceptable narratives
- Forbidden-word check (no hyperbole)
- Empty-week test (narrative gracefully handles slow weeks)

These fold into the eval harness in `docs/06-evals.md` as additional categories.
