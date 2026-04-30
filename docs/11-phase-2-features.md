# 11 — Phase 2 (months 4–7): Expand the wedge

## What and why

Phase 1 proved one person can produce a great client report fast. Phase 2 makes Beat the system of record for an agency's reporting work — not just a tool one person opens once a month.

Customer evidence that motivates Phase 2 (collected in design partner calls during Phase 1):

- "I love the report but my account exec needs to see it before I send."
- "Can I make this look like our deck template?"
- "My client keeps asking 'how does this month compare?'"
- "I get coverage links forwarded to me from junior staff and Google Alerts. Can your tool just eat those?"
- "My client wants to see the report. Can I share a live link?"

Each maps directly to a feature below.

## Features in this phase

1. Template editor and multiple templates
2. Team workspaces with real RBAC
3. Email-forwarding inbox
4. Smart screenshot cropping
5. Per-client analytics
6. Annual billing and referrals
7. Read-only client portal

The order above is a logical grouping, NOT a build order. See `docs/10-roadmap-overview.md` "Sequencing principle."

---

## 11.1 — Template editor

### Motivation

Every agency wants their report to look like their PowerPoint deck. In Phase 1 we ship one template that's "good enough." In Phase 2 we ship a template engine that doesn't make us hand-build a template for every customer.

### Data model

The `report_templates` table already exists from Phase 1 with a `structure JSONB` column. Phase 2 formalizes that structure:

```typescript
type TemplateStructure = {
  schema_version: 1;
  cover: {
    show_agency_logo: boolean;
    show_client_logo: boolean;
    title_format: string;        // e.g. "{{client.name}} — {{period.month}} {{period.year}}"
    subtitle_format?: string;
    background_style: "white" | "agency-color" | "gradient";
  };
  sections: TemplateSection[];   // ordered
};

type TemplateSection =
  | { type: "exec_summary"; editable: boolean }
  | { type: "stats_grid"; metrics: Array<"total" | "tier_1" | "positive" | "reach" | "share_of_voice">; }
  | { type: "highlights"; count: number; layout: "2x2" | "1x4" | "list" }
  | { type: "all_coverage"; columns: Array<"date" | "outlet" | "headline" | "sentiment" | "reach"> }
  | { type: "sentiment_chart"; chart: "donut" | "bar" }
  | { type: "share_of_voice"; competitors: string[] }     // requires Phase 2.5
  | { type: "page_break" }
  | { type: "custom_text"; markdown: string };
```

Add migration `V010__template_structure_v2.sql`:
- Migrate existing system templates to the v2 structure (one-time).
- Add a `schema_version` field check constraint.
- Backfill `structure` for existing rows where empty.

### API additions

- `POST /v1/templates` — create a custom template. Body matches `TemplateStructure`.
- `PATCH /v1/templates/:id` — update structure. Validates against schema.
- `POST /v1/templates/:id/duplicate` — clone (useful for "start from system template, modify").
- `GET /v1/templates/:id/preview?client_id=...` — render with sample or real data.
- `DELETE /v1/templates/:id` — soft delete (and reject if any reports use it).

### UI surface

- New route `/templates` — list of system + workspace templates with previews.
- New route `/templates/:id/edit` — drag-and-drop section editor.
  - Left panel: section library (drag a section type onto the canvas).
  - Center: live preview re-rendered on change (debounced 500ms).
  - Right panel: section properties (e.g. for stats_grid, choose which 4 metrics).
- New route `/templates/new` — start from blank or duplicate existing.

The render service `render/server.ts` needs to consume the new structure; refactor `templates/standard.hbs` to be data-driven from `structure.sections` rather than a fixed layout.

### LLM/prompt impact

None directly. The exec_summary section still uses `prompts/executive-summary-v1.md`. Templates change layout, not content generation.

### Key risks

- **Render performance** — naive rendering of complex templates can blow Puppeteer memory. Set a per-render time budget (15s) and max sections per template (20).
- **Template versioning vs. report regeneration** — when a user edits a template, do existing reports using it rerender? Decision: **NO**. Reports are historical artifacts. Edits to a template only affect new reports. Document this prominently in the UI.
- **Schema drift** — agencies will request increasingly elaborate sections. Resist. The section types listed above are the v2 set; new types require an explicit schema bump.

### Acceptance criteria

- An agency can take a screenshot of their existing PowerPoint template and reproduce 80% of the visual hierarchy in our editor in under 30 minutes.
- Render time stays under 10 seconds for a template with 12 sections and 30 coverage items.
- Editing a template never breaks a report that was generated from it.

---

## 11.2 — Team workspaces with real RBAC

### Motivation

Phase 1 supports multiple users per workspace at the data model level but treats everyone as an owner in practice. Phase 2 makes roles real, adds invites, and adds a basic activity feed.

### Data model

Existing `workspace_members` table has `role` already. Add:

```sql
CREATE TABLE workspace_invites (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    email           CITEXT NOT NULL,
    role            TEXT NOT NULL CHECK (role IN ('member','viewer')),
    token           TEXT NOT NULL UNIQUE,
    invited_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invites_workspace ON workspace_invites(workspace_id);
CREATE INDEX idx_invites_email ON workspace_invites(email);
```

`audit_events` already exists from Phase 1. Phase 2 starts populating it consistently and exposes a UI surface for it.

### API additions

- `POST /v1/workspace/invites` — create an invite, sends email.
- `GET /v1/workspace/invites` — list pending invites.
- `DELETE /v1/workspace/invites/:id` — rescind.
- `POST /v1/auth/accept-invite` — body `{ token, password?, name? }`. Creates user (if new) or attaches to existing user. Resolves the invite.
- `PATCH /v1/workspace/members/:user_id` — update role (owner-only).
- `GET /v1/workspace/activity` — paginated audit feed for the workspace.

### Permission matrix

| Action | Owner | Member | Viewer |
|---|---|---|---|
| Create/edit/delete clients | ✓ | ✓ | — |
| Create/edit/generate/share reports | ✓ | ✓ | — |
| View reports | ✓ | ✓ | ✓ |
| Edit templates | ✓ | ✓ | — |
| Invite members | ✓ | — | — |
| Change member roles | ✓ | — | — |
| Manage billing | ✓ | — | — |
| Delete workspace | ✓ (with confirmation) | — | — |

Enforced at the controller layer via a `@RequiresRole(Role.OWNER)` annotation. Every non-public endpoint needs an explicit role declaration.

### UI surface

- `/settings/team` — members list, pending invites, invite form, role-change dropdown.
- `/settings/activity` — feed of recent actions (who created, edited, generated, shared).
- "Read-only" badge on viewer accounts in the chrome.

### Key risks

- **The "last owner" footgun** — if the only owner removes themselves, the workspace becomes unmanageable. Block this server-side and surface a clear message.
- **Invite spam** — rate-limit invite creation per workspace (10/hour).

### Acceptance criteria

- A 5-person agency can have 1 owner, 3 members, 1 viewer with sensible isolation between them.
- An invited user lands directly on the workspace they were invited to (not a "pick your workspace" page) after accepting.
- Activity feed surfaces enough information that an owner can trust it for compliance audits.

---

## 11.3 — Email-forwarding inbox

### Motivation

Coverage links arrive in an agency's inbox via Google Alerts, Mention.com, account execs forwarding URLs, journalist replies, and the agency's own monitoring. Today they get re-typed (or pasted) into Beat. The friction is real.

The fix: a magic email address per workspace. Forward anything with URLs in it; we file the URLs into a per-client inbox for one-click addition to a draft report.

### Data model

```sql
CREATE TABLE coverage_inbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID REFERENCES clients(id) ON DELETE SET NULL,  -- nullable; user assigns later
    source_url      TEXT NOT NULL,
    received_via    TEXT NOT NULL CHECK (received_via IN ('email','manual','browser_extension')),
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_from   TEXT,                          -- sender email if via email
    raw_subject     TEXT,
    notes           TEXT,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','assigned','dismissed')),
    assigned_to_report_id UUID REFERENCES reports(id) ON DELETE SET NULL,
    assigned_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbox_workspace_status ON coverage_inbox(workspace_id, status, received_at DESC);
CREATE UNIQUE INDEX idx_inbox_workspace_url ON coverage_inbox(workspace_id, source_url);
```

```sql
ALTER TABLE workspaces ADD COLUMN inbox_token TEXT UNIQUE;
-- Backfill: generate random tokens for existing workspaces
UPDATE workspaces SET inbox_token = encode(gen_random_bytes(8), 'hex') WHERE inbox_token IS NULL;
ALTER TABLE workspaces ALTER COLUMN inbox_token SET NOT NULL;
```

Each workspace gets a unique address: `clip-{inbox_token}@clip.beat.app`.

### Infrastructure

Use Postmark's **inbound** processing (or AWS SES + Lambda, but Postmark is simpler). Configure MX records on `clip.beat.app` to point at Postmark. Postmark POSTs each parsed email to `POST /v1/webhooks/inbound-email`.

### API additions

- `POST /v1/webhooks/inbound-email` — receives parsed email JSON from Postmark. Verifies signature. Extracts URLs from the body (text + html), creates `coverage_inbox` rows.
- `GET /v1/inbox` — list pending items, filtered by client (or unassigned).
- `POST /v1/inbox/:id/assign` — body `{ client_id, report_id? }`. If `report_id` provided, also creates a coverage_item in that report. Marks inbox item as `assigned`.
- `POST /v1/inbox/:id/dismiss` — soft-mark as dismissed.
- `GET /v1/workspace/inbox-address` — returns the magic email for the current workspace.

### URL extraction logic

Given a forwarded email, parse URLs from both plain text and HTML. Normalize, deduplicate, drop tracking-parameter junk. Filter out:

- Image URLs (`*.png`, `*.jpg`, etc.)
- Tracking pixels and unsubscribe links (`*.list-manage.com`, `*.sendgrid.net`)
- Internal forwarding URLs (`*.proofpoint.com`, etc.)
- URLs that aren't articles (heuristic: must have a path beyond just the domain)

Implementation in `app.beat.inbox.UrlExtractor`. Unit tested against a corpus of real-world forwarded emails.

### Client assignment

If the email subject or body contains a known client name (case-insensitive substring against workspace's clients), auto-assign. Otherwise leave as `client_id = NULL` and let the user assign in the UI.

### UI surface

- New nav item "Inbox" with a count badge.
- `/inbox` page: grouped by client. Each row: source URL, sent date, sender, "Add to current report" / "Assign to client" / "Dismiss".
- Empty state explains the feature, shows the workspace email address with a copy button.

### Key risks

- **Spam.** Anyone who guesses the address can drop URLs into a workspace's inbox. Mitigation: tokens are 8 bytes (16 hex chars) — unguessable. Also add a "trusted senders" list in workspace settings; default to "anyone whose email matches a workspace member's email." Untrusted senders go into a separate quarantined view.
- **Email parsing failures.** Forwarded HTML is a swamp. Test against real corpora before launch.
- **PII in emails.** Don't store the email body verbatim — only extracted URLs + sender + subject. Bodies get parsed and discarded.

### Acceptance criteria

- A user forwards a Google Alerts digest with 8 articles to their workspace address; within 30 seconds, 8 inbox items appear, deduped against any URLs already in their reports.
- Auto-assignment to client works when client name appears in subject (e.g. "Acme Corp coverage").
- Untrusted senders are quarantined and never automatically expand into the active inbox.

---

## 11.4 — Smart screenshot cropping

### Motivation

Phase 1 captures a screenshot of an article's headline area but uses a naive "scroll to top, capture viewport" approach. Results are inconsistent: ad banners, sticky headers, modal popovers all sneak in.

Phase 2 captures *just* the headline, byline, lede, and hero image. Reports look measurably better.

### Approach

In `render/`, extend the screenshot endpoint with a smart-crop pipeline:

1. Load the page in headless Chromium with `prefers-reduced-motion`.
2. Block ads and trackers via uBlock Origin extension (loaded into the headless browser).
3. Dismiss cookie banners via a list of common selectors (we can use the open-source "I-don't-care-about-cookies" list).
4. Heuristically locate the article container: try `<article>`, then `[role="main"]`, then `<main>`, then largest text block by reading-time heuristic.
5. Crop to the bounding box of the first `<h1>` and the next 200–400px below.
6. If cropping fails (no `<h1>`, container not found), fall back to viewport screenshot with a clear `is_fallback` flag.

Implementation: `render/screenshot/SmartCrop.ts`. Tested against a fixture set of 30 real article URLs with expected crop bounds.

### Data model

No schema change. The existing `coverage_items.screenshot_url` is reused.

Add a new column:

```sql
ALTER TABLE coverage_items ADD COLUMN screenshot_strategy TEXT CHECK (screenshot_strategy IN ('smart','fallback','manual'));
```

Useful for analytics (what % of articles do we crop well?) and debugging.

### Key risks

- **JavaScript-rendered articles** that load content via React. The headless browser handles these but they're slow. Set a timeout: 8 seconds for full hydration.
- **Paywalls.** WSJ/FT/NYT often serve a subscription wall. Mitigation: log in via shared accounts (legal and configurable per workspace) — Phase 2.5 if needed.
- **Site-specific quirks.** Some publications never resolve cleanly. Allow per-domain selector overrides in a config file.

### Acceptance criteria

- On a fixture set of 30 real articles, smart-crop produces a clean crop in 80%+ of cases (visual review).
- Average screenshot generation latency < 6s.
- Fallback crops still produce something usable (not a blank page or pure ad banner).

---

## 11.5 — Per-client analytics

### Motivation

The most common follow-up question after a report goes out: "how does this compare to last month?" Phase 1 has no answer. Phase 2 provides a per-client analytics page that the agency can show to their client.

### Data model

We don't need new tables — analytics roll up from `coverage_items`. But pre-computing helps performance:

```sql
CREATE TABLE client_metrics_monthly (
    client_id           UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    period_month        DATE NOT NULL,           -- first of month
    total_coverage      INT NOT NULL DEFAULT 0,
    tier_1_count        INT NOT NULL DEFAULT 0,
    tier_2_count        INT NOT NULL DEFAULT 0,
    tier_3_count        INT NOT NULL DEFAULT 0,
    sentiment_positive  INT NOT NULL DEFAULT 0,
    sentiment_neutral   INT NOT NULL DEFAULT 0,
    sentiment_negative  INT NOT NULL DEFAULT 0,
    sentiment_mixed     INT NOT NULL DEFAULT 0,
    estimated_total_reach BIGINT NOT NULL DEFAULT 0,
    top_topics          TEXT[] NOT NULL DEFAULT '{}',   -- top 5
    top_outlets         JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{outlet_id, name, count}]
    top_authors         JSONB NOT NULL DEFAULT '[]'::jsonb,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, period_month)
);
```

Recompute monthly on the 1st via a scheduled job. Also recompute when a report finalizes (in case mid-month coverage was added).

### API additions

- `GET /v1/clients/:id/analytics?from=YYYY-MM&to=YYYY-MM` — returns time series of metrics.
- `GET /v1/clients/:id/analytics/sov?competitors=X,Y,Z&from=...&to=...` — share of voice (Phase 2.5; needs configurable competitors).

### UI surface

New route `/clients/:id/analytics`:

- Header: client logo, "Analytics" title, period selector (defaults to last 6 months).
- Section: coverage volume over time (bar chart, monthly).
- Section: sentiment trend (stacked bar).
- Section: tier mix (donut + trend).
- Section: top outlets (bar chart, top 10 with click-through to coverage).
- Section: top topics (tag cloud or bar).
- Section: top journalists (table).
- "Add this to a report" button on every chart → exports as PNG into a report's custom_text section.

### Key risks

- **Misleading trend lines** — if a client cherry-picks the first month they had no coverage and compares to a great month, the slope looks dramatic. Show 6-month rolling averages prominently.
- **Sentiment volatility** — small months with one negative item swing sentiment percentages wildly. Annotate periods with N < 5 items as "low sample."

### Acceptance criteria

- Loading the analytics page for a client with 12 months of history is < 1 second.
- Recompute on report finalize is < 500ms (additive only — no full recompute).
- "Add to report" produces an embeddable chart that respects template branding.

---

## 11.6 — Annual billing and referrals

### Motivation

Reduce churn (annual customers churn at ~30% of monthly customers' rate) and accelerate growth via word-of-mouth.

### Data model

```sql
CREATE TABLE referral_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    code            TEXT NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE referrals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referrer_workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    referred_workspace_id UUID NOT NULL UNIQUE REFERENCES workspaces(id) ON DELETE CASCADE,
    code_used       TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('pending','converted','expired')),
    referrer_credit_applied_at TIMESTAMPTZ,
    referred_credit_applied_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Annual billing

In Stripe, create an annual price for each plan at 10x monthly (i.e. 16.7% off). Update the Checkout flow to offer monthly/annual toggle.

`workspace.plan_interval` field added to track the active interval. Display next renewal date prominently in billing UI.

### Referral mechanics

- Each workspace gets one auto-generated code on signup (e.g. `hayworth-pr`).
- Customer shares: `https://beat.app/?ref=hayworth-pr`.
- New signup with ref → workspace.referred_by recorded.
- On the new workspace's first paid month: referrer gets one month free credit (Stripe coupon), referee gets one month free.
- Cap: a workspace can earn at most 12 months of free credit per year.

### API additions

- `GET /v1/workspace/referrals` — code, list of referrals, credits earned.
- Public landing page with `?ref=...` captures into a cookie, applied at signup.

### Acceptance criteria

- Annual billing reduces month-1 churn (vs monthly cohort) by at least 50% in cohort analysis.
- Referral attribution works correctly through signup, even if the new user clears cookies between landing and signup (capture also via ref param on signup).
- Stripe credits apply correctly without double-billing on the first month.

---

## 11.7 — Read-only client portal

### Motivation

Today the agency sends a PDF or a public share link. Both are static. The client can't browse historical reports, can't comment, can't see anything between monthly cycles. A live portal makes the agency look modern, deepens stickiness, and — quietly — exposes the client (e.g. CMO at Acme Corp) to Beat. Two-sided product foundation.

### Data model

```sql
CREATE TABLE client_portal_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    email           CITEXT NOT NULL,
    name            TEXT,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (client_id, email)
);

CREATE TABLE client_portal_sessions (
    token_hash      TEXT PRIMARY KEY,
    portal_user_id  UUID NOT NULL REFERENCES client_portal_users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Authentication is **magic link only** — no passwords. Lower friction, better security for low-touch users.

### API additions

Distinct namespace `/v1/portal/*` — these endpoints are accessed by client_portal_users, not internal users. Different auth middleware, different rate limits.

- `POST /v1/portal/auth/request-link` — body `{ email }`. Always 204. Sends magic link if email matches a client_portal_user.
- `POST /v1/portal/auth/verify` — body `{ token }`. Returns session.
- `GET /v1/portal/reports` — list of published reports for this client.
- `GET /v1/portal/reports/:id` — read-only view (HTML, the same template as the share view).

Internal endpoints to manage portal users:

- `POST /v1/clients/:id/portal-users` — invite a portal user.
- `GET /v1/clients/:id/portal-users` — list.
- `DELETE /v1/clients/:id/portal-users/:user_id` — revoke.
- `PATCH /v1/reports/:id` — adds `published_to_portal: bool`. A report only appears in the portal once explicitly published.

### Security

The portal is a separate trust domain. Strict isolation:

- Portal users can ONLY access reports flagged `published_to_portal` AND belonging to their client_id.
- Portal users CANNOT access any other client, any unpublished reports, any internal data.
- A separate Spring Security configuration binds `/v1/portal/*` to portal session middleware, refusing internal sessions.
- Audit every portal access in `audit_events` with action `portal.report_viewed`.

### UI surface

Public-facing portal at `portal.beat.app` (separate domain) — visually distinct from the internal app:

- Login: magic link request.
- Home: list of published reports for this client, newest first.
- Report view: rendered HTML report, no edit affordances.
- Subtle "Powered by Beat" footer linking to `beat.app` — the only Beat branding the client sees.

Internal UI:

- On `/reports/:id/preview`: new "Publish to client portal" toggle. Defaults off.
- On `/clients/:id`: new section "Portal access" — manage portal users.

### Key risks

- **Cross-tenant leakage.** A bug here is catastrophic. Strict integration tests required. Pen-test before launch.
- **Magic link delivery.** Critical path. Use Postmark with high-priority transactional sending.
- **The "client never logs in" problem.** Many clients won't bother. Track activation rate; if low, add email digests as a fallback ("here's your December report — view in portal").

### Acceptance criteria

- A client portal user invited to Acme Corp can access only Acme Corp's published reports — verified with integration tests.
- Magic link emails arrive within 30 seconds.
- A bug-bounty-style internal review finds zero cross-tenant leaks.

---

## Migration / backwards compatibility

All Phase 2 features are additive at the data model level. Existing reports continue to work unchanged.

One-time backfills required:

- `workspaces.inbox_token` — generate for existing rows.
- `report_templates.structure` — migrate any Phase 1 templates to the v2 schema_version=1 structure (system-owned templates handled by the migration; if any custom templates exist, fail loudly and require manual intervention — there shouldn't be any in Phase 1).

## Phase gate to Phase 3

See `docs/10-roadmap-overview.md` for the criteria. The most important non-numeric one: at least 8 customers have spontaneously asked for pitch tracking. If they haven't, Phase 3 is wrong and we should go back to discovery.
