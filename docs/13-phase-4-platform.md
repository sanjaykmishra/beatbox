# 13 — Phase 4 (year 2): Platform

## What and why

Phase 4 turns Beat from a product into a platform. We add the surfaces that big customers expect (SSO, audit logs, integrations) and the live-data layer that competes head-on with Cision/Meltwater (coverage monitoring).

By the time we start Phase 4, we have:

- $40K+ MRR
- A real journalist database compiled from real data
- Pitch attribution data nobody else has
- A team capable of shipping multiple parallel workstreams (3+ engineers)

This is the first phase that requires headcount. Don't start it with two people.

## Features in this phase

1. Coverage monitoring (Google Alerts replacement, real-time)
2. Slack and Microsoft Teams integration
3. Salesforce and HubSpot integration
4. Google Drive auto-export
5. Enterprise tier (SSO, SAML, audit log export, RLS)
6. Public API for customers
7. Possibly: in-house comms team segment

(7) is intentionally a maybe. It changes the buyer profile and the sales motion. Decide based on inbound demand at month 18.

---

## 13.1 — Coverage monitoring

### Motivation

Today our customers use Google Alerts and Mention.com to catch new coverage. They forward URLs into Beat (Phase 2 inbox) or paste them in. The friction is real and the latency is brutal — Google Alerts can be a day late, and it misses paywalled outlets entirely.

Phase 4 brings monitoring into Beat. New coverage gets discovered, deduped, sentiment-classified, and proposed for the next report — without anyone forwarding emails.

### Data model

```sql
CREATE TABLE monitoring_searches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID REFERENCES clients(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    query           TEXT NOT NULL,                 -- e.g. "Acme Corp" OR "@acmecorp"
    excluded_terms  TEXT[] NOT NULL DEFAULT '{}', -- to filter out "Acme Brick"
    languages       TEXT[] NOT NULL DEFAULT '{en}',
    sources         TEXT[] NOT NULL DEFAULT '{news,blogs}',
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_monitoring_searches_active ON monitoring_searches(workspace_id, is_active)
    WHERE is_active = true;

CREATE TABLE monitored_mentions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monitoring_search_id UUID NOT NULL REFERENCES monitoring_searches(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,  -- denormalized
    source_url      TEXT NOT NULL,
    headline        TEXT,
    outlet_id       UUID REFERENCES outlets(id) ON DELETE SET NULL,
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    publish_date    DATE,
    snippet         TEXT,
    coverage_item_id UUID REFERENCES coverage_items(id) ON DELETE SET NULL,  -- linked when added to a report
    is_dismissed    BOOLEAN NOT NULL DEFAULT false,
    is_duplicate_of UUID REFERENCES monitored_mentions(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (monitoring_search_id, source_url)
);

CREATE INDEX idx_monitored_mentions_workspace_undismissed
    ON monitored_mentions(workspace_id, discovered_at DESC)
    WHERE is_dismissed = false AND coverage_item_id IS NULL;
```

### Discovery sources

Multiple data sources combined for breadth:

| Source | What it covers | Cost | Priority |
|---|---|---|---|
| **NewsAPI / Newscatcher** | Mainstream news in 50+ languages | $200–500/mo | Primary |
| **SerpAPI Google News** | Google News results | per-query | Secondary, high-value queries only |
| **Common Crawl + custom index** | Long-tail blogs, niche pubs | infrastructure | Future |
| **Twitter/X search** | Real-time mentions on X | API access | Phase 4.5 if budget allows |
| **Reddit search** | Discussion mentions | free API | Phase 4.5 |

Plan: launch with NewsAPI + SerpAPI for high-value queries. Add others incrementally.

### Job scheduling

Each active `monitoring_search` runs every 30 minutes (faster for higher-tier plans). Worker:

1. Query NewsAPI with the search's query + excluded_terms + language filter.
2. For each result, check `monitored_mentions` for an existing URL — skip if found.
3. Run dedup against existing `coverage_items` for the workspace (URL match + LLM dedup for paraphrased re-publications).
4. Insert new mentions.
5. If a mention is a strong match for an active client, fire a notification.

Schedule via Spring's @Scheduled with workspace fan-out, or move to a dedicated scheduler (Quartz) if scale demands.

### Dedup

Two passes:

1. **URL dedup** — exact URL match. Handles 80% of cases.
2. **LLM dedup** — for borderline cases (similar headlines, same outlet, within 24h), use Claude Sonnet to determine if it's the same article. Cache per pair.

### Notifications

When a new mention arrives:

- High-confidence match for an active client → notify (Slack/email/in-app, per workspace settings).
- Low-confidence → silently file in the monitoring inbox.

User triages in the monitoring inbox: "add to current report," "save for later," "dismiss," "this isn't us" (excludes from future similar matches).

### UI surface

- `/monitoring` — list of searches, last run time, mention count.
- `/monitoring/searches/new` — create a search.
- `/monitoring/inbox` — pending mentions, similar to Phase 2 coverage inbox.
- Notifications surface in the chrome via a bell icon.

### Key risks

- **Costs explode if not capped.** Per-workspace search count caps. NewsAPI usage tracked per workspace; alert at 80% of budget.
- **Noise.** "Apple" returns thousands of irrelevant matches. The `excluded_terms` field is critical. Provide good UX for refining a search after it runs once.
- **Source quality.** Long-tail blogs are noisy. Allow per-search source filters (e.g., "only outlets I've previously covered").

### Acceptance criteria

- A new mention of a tracked client surfaces in the inbox within 30 minutes of publication on a tier-1 outlet.
- Dedup eliminates 95%+ of true duplicates and < 1% of true unique items.
- Per-workspace cost stays under the configured monthly cap.

---

## 13.2 — Slack and Teams integration

### Motivation

Two use cases: (1) notification routing — coverage alerts, monitoring hits, weekly digests; (2) acknowledgement — celebrate big hits in team channels.

### Data model

```sql
CREATE TABLE workspace_integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    type            TEXT NOT NULL CHECK (type IN ('slack','teams','salesforce','hubspot','google_drive')),
    config          JSONB NOT NULL,                -- type-specific config (channel IDs, tokens, etc.)
    encrypted_secrets JSONB NOT NULL,               -- OAuth tokens, encrypted at rest
    is_active       BOOLEAN NOT NULL DEFAULT true,
    installed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    installed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,
    UNIQUE (workspace_id, type)
);
```

### Slack

OAuth via Slack's Bolt SDK. Permissions requested: `chat:write`, `channels:read`, `im:write`. No reading user messages.

Triggers (each can be enabled/disabled per workspace):

- **New tier-1 coverage** — "🎉 Hayworth PR landed Acme Corp in TechCrunch: [Headline]"
- **Pitch reply** — "Sarah Perez replied to your pitch about Acme Corp"
- **Weekly digest** — Monday morning summary of last week's coverage
- **Report ready** — "December report for Acme Corp is ready: [link]"

UI: `/settings/integrations/slack` — toggle each trigger, choose target channel.

### Microsoft Teams

Similar structure via Microsoft Graph API. Lower priority than Slack (smaller share of customer base). Defer to Phase 4 month 4 unless customer demand surfaces earlier.

### Acceptance criteria

- Slack install completes in < 60 seconds end-to-end.
- Notifications appear within 30 seconds of triggering event.
- Toggling a trigger off stops future notifications immediately.

---

## 13.3 — Salesforce and HubSpot integration

### Motivation

Mid-size and enterprise customers track PR activities in their CRM. They want pitches and coverage to flow into Salesforce campaigns or HubSpot custom objects without manual data entry.

### Approach

Outbound sync only — Beat → CRM. We don't read from CRMs except to fetch object schemas for mapping. This keeps the trust surface small.

Two sync directions:

- **Pitches → Campaigns/Activities.** Each pitch becomes a Campaign Member or Activity record. Recipients become Contacts (created if not present).
- **Coverage → Custom objects.** Coverage items written to a custom Salesforce/HubSpot object. Includes outlet, headline, sentiment, reach, attributed pitch.

### Implementation

OAuth to the customer's instance. Field mappings configurable per workspace via a setup wizard. Sync runs every 15 minutes via worker. Failures retry with backoff; persistent failures notify the workspace owner.

### Data model

`workspace_integrations` covers credentials. Add:

```sql
CREATE TABLE crm_sync_state (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    entity_type     TEXT NOT NULL CHECK (entity_type IN ('pitch','coverage_item','client')),
    entity_id       UUID NOT NULL,
    crm_type        TEXT NOT NULL,                 -- 'salesforce' | 'hubspot'
    crm_record_id   TEXT NOT NULL,                  -- e.g. Salesforce ID
    last_synced_at  TIMESTAMPTZ NOT NULL,
    sync_error      TEXT,
    UNIQUE (workspace_id, entity_type, entity_id, crm_type)
);
```

### Acceptance criteria

- A pitch sent in Beat appears in Salesforce as a Campaign Activity within 15 minutes.
- Field mapping wizard works without engineering involvement for standard customizations.
- Sync failures surface in the UI with actionable error messages.

---

## 13.4 — Google Drive auto-export

### Motivation

A common request: "I want my reports automatically saved in our team's Google Drive folder." Especially common for agencies that have folder-per-client structures their clients can also access.

### Implementation

OAuth to Google. On each report finalization with auto-export enabled:

1. Determine the target folder (per-client config; supports `{{client_name}}` template variables in folder paths).
2. Upload the PDF to that folder.
3. Optionally also upload a flat HTML version (for searchability in Drive).
4. Record the Drive file ID on the report row.

Per-client configuration: each client can have a target folder ID. Workspace-level fallback if not set.

### Acceptance criteria

- A finalized report appears in the configured Drive folder within 60 seconds of generation.
- Permission errors (folder deleted, OAuth expired) surface clearly with a one-click reauth.

---

## 13.5 — Enterprise tier

### What's new

- **SSO via SAML/OIDC.** Okta, Auth0, Google Workspace, Microsoft Entra. Pre-integrated for top providers.
- **SCIM provisioning.** User and group sync from IdP. Auto-deprovisioning when an employee leaves.
- **Audit log export.** API endpoint and scheduled S3 dumps. Required for SOC 2.
- **Postgres Row-Level Security.** Belt-and-suspenders tenant isolation. Currently enforced in app code only.
- **Custom data residency.** EU-only, US-only, or specific-region deployments. Multi-region infrastructure setup.
- **Custom contracts.** Annual MSA, custom SLA, custom DPA. Sales-led, not self-serve.
- **Dedicated CSM.** Human, not feature.

### Pricing

Custom — $800/mo to $2000/mo+ depending on user count, support level, and contract length. No public pricing page; sales handles.

### Data model additions

```sql
CREATE TABLE sso_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL UNIQUE REFERENCES workspaces(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL CHECK (provider IN ('saml','oidc')),
    metadata_url    TEXT,
    metadata_xml    TEXT,
    config          JSONB NOT NULL,                -- IdP-specific config
    is_required     BOOLEAN NOT NULL DEFAULT false, -- if true, password login disabled
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scim_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    scopes          TEXT[] NOT NULL,
    last_used_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);
```

### Postgres RLS

Roll out RLS policies for tenant tables (clients, reports, coverage_items, pitches, etc.). The pattern:

```sql
ALTER TABLE clients ENABLE ROW LEVEL SECURITY;

CREATE POLICY clients_tenant_isolation ON clients
    FOR ALL
    TO app_role
    USING (workspace_id = current_setting('app.current_workspace_id')::uuid);
```

App connection pool sets `app.current_workspace_id` per-request. This means a bug in app code can no longer leak tenant data — the database refuses to return cross-tenant rows.

Performance impact: index every `workspace_id` column. Already the case from Phase 1.

### Compliance

Track toward SOC 2 Type II in year 2:

- Vanta or Drata to manage controls.
- Annual penetration test by external firm.
- Code review requirements (2 approvers for security-relevant code).
- Encryption at rest (Postgres TDE), in transit (TLS only).
- Background checks for engineers with production access.

### Acceptance criteria

- An enterprise customer can complete SSO setup with their IdP in < 2 hours of engineering time.
- SCIM provisioning end-to-end: a new user in Okta appears in Beat within 15 minutes; deprovisioned within 60 seconds.
- Audit log export contains all required events with cryptographic integrity (hash chain).
- RLS policies pass a red-team review: no SQL query returns cross-tenant data even with a hypothetical app bug.

---

## 13.6 — Public API for customers

### Motivation

Larger customers want to script Beat. "I want to auto-create a report on the 1st of every month for these 50 clients." "I want to pull pitch hit rate into our internal BI dashboard." Today they can't.

### Approach

Read-only API at first. Subset of the internal API; documented separately. Keys per workspace.

### Initial endpoints

- `GET /api/v1/clients`
- `GET /api/v1/clients/:id`
- `GET /api/v1/clients/:id/reports`
- `GET /api/v1/reports/:id` (read-only)
- `GET /api/v1/reports/:id/coverage`
- `GET /api/v1/pitches`
- `GET /api/v1/pitches/:id`
- `GET /api/v1/analytics/clients/:id`

Rate limited per API key. Documented at `developers.beat.app` (Mintlify or Redoc).

### Phase 4.5: write endpoints

Add `POST /api/v1/reports`, `POST /api/v1/pitches`, etc. once the read API has stable usage.

### Acceptance criteria

- Documentation includes runnable code examples in curl, Python, Node, Go.
- Time from "customer asks for API access" to "API key in their hands" < 24 hours.
- API breaking changes follow a deprecation policy (6-month minimum notice).

---

## 13.7 — In-house comms segment (maybe)

### Why "maybe"

In-house comms teams at corporates are a different buyer:

- Bigger team, more stakeholders to please
- Different trigger events (quarterly board reports, crisis comms, executive comms)
- Likely already pay for Cision or PRophet — they're switching, not net-new
- Different sales motion (longer cycles, RFPs, security reviews)

We may not want to be that company. Decide based on:

- Inbound demand: are 5+ in-house teams asking unprompted by month 18?
- Margin: is enterprise tier MRR per customer high enough to fund the new GTM motion?
- Strategic fit: does targeting in-house teams accelerate or distract from agencies?

If yes: add an in-house SKU with monitoring + share-of-voice + crisis comms templates emphasized. If no: stay focused on agencies.

This is a year-2 decision, not a year-2 build.

---

## Migration / backwards compatibility

Phase 4 introduces enterprise features that require infrastructure changes:

- **Multi-region deployment.** Up to now we ran in one region. Adding EU and APAC requires database replication strategy and per-region object storage. Plan a dedicated 2-week infra sprint at the start of Phase 4.
- **RLS rollout.** Enable RLS on tenant tables one at a time, with shadow-mode logging first to catch any cross-tenant queries that currently work but would fail under RLS. Estimated 3 weeks for full rollout.
- **API versioning.** Internal API at `/v1`, public API at `/api/v1`. Plan for `/api/v2` from day one — design with versioning in mind.

## What comes after Phase 4

This is intentionally undocumented. By the time we finish Phase 4 we'll know things we don't know now. Likely candidates:

- AI pitch composition (the wedge we declined to build initially)
- Crisis comms war room
- Multi-language reports + non-English coverage
- M&A — acquire a complementary tool to broaden the platform

But these are conjectures, not plans. The discipline of staying focused, the same one that made Phase 1 work, applies through Phase 5 and beyond. Don't build it because we can. Build it because customers are loud, the wedge holds, and we have the team.
