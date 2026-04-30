# 08 — Build plan (Phase 1, weeks 1–10)

Goal: ship a reporting flow that one boutique PR agency uses to send one real client report to one real client by week 11. Land first paid customer week 11–12.

Each week below has explicit deliverables and acceptance criteria. If a week's work isn't acceptance-criteria green, do NOT roll forward — fix it before moving on.

---

## Week 1 — Foundations

**Goal:** repo bootstrapped, CI green on hello-world, deploys land in production.

Deliverables:

- Monorepo created with `api/`, `web/`, `render/` per the layout in CLAUDE.md.
- Spring Boot 3.x project with Java 21, Gradle, Spotless, JUnit 5 wired.
- Vite + React 18 + TS + Tailwind project with React Router and React Query installed.
- Node + Express + Puppeteer skeleton in `render/`.
- Postgres database provisioned on Fly. Connection string in env.
- Flyway configured. `V001__init.sql` migration containing the schema from `docs/03-data-model.md`.
- GitHub Actions CI for `api`, `web`, `render`: lint + test + build on every PR.
- Fly.io apps for `beat-api`, `beat-render`, `beat-web` deploying on merge to `main`.
- Sentry + Logtail integrated in all three services.
- `.env.example` complete per `docs/02-architecture-and-stack.md`.

Acceptance:

- `git push` to `main` results in a deploy to production within 10 minutes.
- A health-check endpoint returns 200 in production.
- Migrations run on deploy without manual steps.

---

## Week 2 — Auth, workspaces, clients

**Goal:** a user can sign up, get a workspace, log in, create clients.

Deliverables:

- `POST /v1/auth/signup`, `POST /v1/auth/login`, `POST /v1/auth/logout` per `docs/04-api-surface.md`.
- Session token storage (hashed) in `sessions` table.
- Auth middleware that resolves session → user → workspace and attaches to request.
- Workspace endpoints: `GET /v1/workspace`, `PATCH /v1/workspace`.
- Client CRUD: `GET/POST/PATCH/DELETE /v1/clients[/:id]`.
- Frontend: signup, login, logout flows. Client list and client detail pages.
- Logo upload to R2 (presigned PUT URL pattern).
- Audit events written for signup, login, client.created/updated/deleted.
- `activity_events` table created (per `docs/15-additions.md` §15.2). `ActivityRecorder` service implemented. First events fire from auth and client CRUD endpoints.
- Workspace client list with badges (per `docs/16-client-dashboard.md`): `GET /v1/clients` returns per-client `alerts_summary` + workspace summary. Frontend renders badges with severity colors, sorts by `total_score DESC`. (In Phase 1 the alerts table doesn't exist yet — Week 2 returns a stub `alerts_summary` with all-zero counts. Real alerts land in Week 8.)

Acceptance:

- A new user can sign up via the web UI, log in on a fresh browser, create three clients, edit one, and log out.
- Signup creates exactly one workspace, one user, one workspace_member with role `owner`.
- Logo upload works and the logo renders in the header on the next page load.

---

## Week 3 — URL ingestion + article fetcher

**Goal:** paste a URL, get HTML and clean text reliably.

Deliverables:

- Article fetcher service in `api/src/main/java/app/beat/extraction/` with a layered strategy:
  1. Mercury Parser (Java port or HTTP service)
  2. Readability fallback
  3. ScrapingBee fallback for paywalled or blocked sites
- Returns: `{ cleanText, headline, byline, publishDate, screenshotUrl }`. Each field nullable.
- Headline screenshot captured via the Puppeteer service: `POST /screenshot` endpoint added in `render/`.
- `extraction_jobs` queue and worker dispatch via Postgres `LISTEN/NOTIFY`.
- `POST /v1/clients/:id/reports` and `POST /v1/reports/:id/coverage` endpoints. Coverage items created in `queued` state, jobs dispatched.
- Worker: dequeues, fetches article (without LLM yet — placeholder data), updates row to `done`.
- Activity events fire from extraction worker with `kind='report.coverage_extracted'`, `duration_ms` set.
- `llm.call_completed` events fire from the Anthropic client wrapper with model, prompt_version, tokens, cost.

Acceptance:

- Posting 5 URLs from common publications (TechCrunch, NYT, Verge, WSJ, a generic blog) results in 5 coverage_items reaching `done` status with non-empty cleanText.
- Worker handles failures gracefully (paywall, 404, timeout).
- Screenshots render and upload to R2.

---

## Week 4 — LLM extraction + golden eval set

**Goal:** end-to-end extraction returning real, validated structured data. Eval harness runs and is honest.

Deliverables:

- Prompt loader that reads `prompts/*.md`, validates frontmatter, exposes versioned templates.
- Anthropic client wrapper with retry, structured logging, cost metering per workspace.
- Extraction worker now calls Anthropic Sonnet using `prompts/extraction-v1.md` and validates JSON output against `ExtractionSchema`.
- Outlet resolution: lookup in curated table; cache miss → call Sonnet using `prompts/outlet-tier-v1.md`.
- Author upsert: every byline becomes an `authors` row.
- 50-item golden set assembled per `docs/06-evals.md`. Articles cached locally.
- Eval harness in `api/src/test/eval/` with `EvalRunner`, `ExtractionEvalTest`, `LlmJudge`.
- CI workflow `eval.yml` running on prompt/LLM-touching PRs.

Acceptance:

- A real-world report of 14 URLs extracts cleanly with sentiment, summary, and key quote.
- The eval harness runs end-to-end and produces a markdown report.
- Hard gates pass: schema compliance 100%, hallucination rate 0 on the golden set.
- Sentiment accuracy ≥ 90%.

---

## Week 5 — Report builder UI

**Goal:** the Step 1 + Step 2 flow from `docs/07-wireframes.md` works end-to-end in the browser.

Deliverables:

- Frontend `routes/clients/:id/reports/new` page (Step 1 wireframe).
- Frontend `routes/reports/:id` page (Step 2 wireframe).
- Polling `GET /v1/reports/:id` every 2 seconds while any item is non-terminal.
- Inline edit drawer on coverage items. Saving calls `PATCH` and updates local cache via React Query.
- "Retry" and "Remove" actions on failed items.
- Header counts update live.
- Skeleton loading states for `queued` and `running` items.
- Client context UI and API per `docs/15-additions.md` §15.1. `client_context` table created. Edit form on `/clients/:id/context`. Context renders on report builder page (collapsed). Extraction worker reads context and renders into the prompt.
- Client dashboard scaffolding (per `docs/16-client-dashboard.md`): route `/clients/:id`, header strip, stats row (computed on-the-fly), recent activity from `activity_events`. Two-column layout. No alerts data yet — "Needs attention" shows a placeholder.

Acceptance:

- A user can paste 14 URLs, watch them extract live, edit a couple of fields, and have the edits stick on a forced refresh.
- The "Generate report" button correctly disables until all items are terminal and at least one is `done`.
- Context can be saved for a client and is used in the next extraction's LLM call (verifiable in `activity_events.metadata.prompt_version` showing the new context-aware version).

---

## Week 6 — Report rendering pipeline

**Goal:** click "Generate report" → get a real branded PDF.

Deliverables:

- One handcrafted HTML+Handlebars template in `render/templates/standard.hbs` matching the Step 3 wireframe.
- Template uses workspace branding (logo, primary color) and client name.
- `render/server.ts` POST `/render` endpoint: receives report payload, returns PDF buffer.
- API render worker: dispatched on report `processing` status, calls render service, uploads to R2, updates report row.
- "At a glance" stats computed in API code (deterministic).
- "Highlights" selection logic: top 4 by `tier × prominence × log(reach)`.
- Frontend `routes/reports/:id/preview` page (Step 3 wireframe) — server-rendered HTML preview using the same template.
- Download PDF + Share link UI.

Acceptance:

- Generated PDF renders correctly across Adobe Reader, macOS Preview, and Chrome's PDF viewer.
- Branding is correct: agency logo on cover, no Beat references anywhere.
- A 15-item report generates in under 30 seconds end-to-end.

---

## Week 7 — Executive summary + share links

**Goal:** the executive summary works and is safe to ship.

Deliverables:

- Executive summary generated via Anthropic Opus using `prompts/executive-summary-v1.md`.
- Eval harness extended with `SummaryEvalTest`: hyperbole detection, fact coverage via LLM judge.
- Eval harness extended to cover client context paths per `docs/15-additions.md` §15.1. Five new golden items with context. Regression test on the no-context golden set. Context-bleed test.
- Inline editor for executive summary on the preview page. Saving sets `executive_summary_edited=true`.
- `POST /v1/reports/:id/share` and `DELETE /v1/reports/:id/share`.
- Public share view at `/r/:token` — read-only HTML rendering of the same template.
- Rate limiting on the public endpoint.

Acceptance:

- Summary eval gates pass on the golden set: hallucination 0, hyperbole 0, fact coverage ≥ 80%.
- A shared link viewed in incognito renders correctly and respects expiry.
- Editing the summary preserves the edit across re-renders (no re-LLM call).

---

## Week 8 — Billing + plan limits

**Goal:** real money flows.

Deliverables:

- Stripe products + prices configured (Solo $39/mo, Agency $99/mo, plus annual variants at 15% off).
- `POST /v1/billing/checkout` returns Stripe Checkout session URL.
- `POST /v1/billing/portal` returns Stripe Customer Portal URL.
- `POST /v1/webhooks/stripe` handles subscription lifecycle events.
- Workspace plan field updates on subscription state changes.
- Plan limits enforced: client count cap, monthly report cap. Enforcement returns 402 with clear upgrade copy.
- Trial state with explicit "trial expired — add card to continue" UX.
- Email notifications on key billing events (Resend or Postmark, transactional only).
- **Alert engine and `client_alerts` table (per `docs/16-client-dashboard.md`)**: migration + `client_alerts` table; six alert computations (`report.overdue`, `extraction.failed`, `inbox.pending`, `context.stale`, `client.setup_incomplete`, `client.healthy` — pitch- and attribution-related alerts deferred to Phase 3); event-driven invalidation hooks on the corresponding `activity_events` writes; 30-minute scheduled job for time-based alerts; `GET /v1/clients/:id/dashboard` endpoint returning the unified payload; frontend "Needs attention" column populated; client-list badges populated. (Phase 1 implements 5 of the 6 — `inbox.pending` is deferred until inbox tables exist.)

Acceptance:

- A new user can upgrade from trial to Solo via Stripe Checkout and immediately get higher limits.
- Trial expiry blocks new report generation but preserves access to existing reports.
- A failed payment downgrades the workspace and surfaces a clear remediation flow.
- Resolving an alert (e.g., generating an overdue report) removes the corresponding card from the dashboard and the badge from the list within 60 seconds.

---

## Week 9 — Polish, error states, onboarding

**Goal:** no rough edges. A new user signing up cold can find their first report-completed flow without help.

Deliverables:

- Onboarding flow on first login: "Welcome — let's create your first client and report" wizard.
- Empty states everywhere (no clients, no reports, no coverage items, no failed extractions).
- Error states: failed payments, extraction failures, render failures, quota exceeded, share link expired.
- Loading skeletons and optimistic UI where it improves perceived speed.
- Accessibility pass: keyboard nav, focus states, contrast, alt text on images, aria-labels on interactive elements.
- Settings page: workspace name/logo/color, members management, billing.
- Founder dashboard at `/admin/dashboard` (gated to internal users only). Pulls from `activity_events`. Shows daily extractions, daily reports generated, cost per workspace, P95 extraction latency, top error classes.
- Polish for the client dashboard states (per `docs/16-client-dashboard.md`): empty states (healthy, no recent activity, no upcoming items), loading skeletons, dismiss action on the new-client setup checklist, tooltips on badges.
- 404 and error boundary pages.
- Mobile responsive (the editing experience can degrade gracefully — agencies build reports on desktop).

Acceptance:

- An external person (founder's spouse, friend, etc.) signs up cold and produces a real report without help.
- Lighthouse accessibility score ≥ 90 on key pages.
- All known error paths display useful copy, not stack traces.

---

## Week 10 — Internal dogfood

**Goal:** the founder builds a real report for a friendly agency, end-to-end. Fix everything that breaks.

Deliverables:

- Build 3 real reports for 3 different friendly agencies using only the production product.
- Document every friction point. Fix the high-impact ones immediately.
- Generate the executive summary for a real client and have a senior PR pro review it for tone, accuracy, and shippability.
- Final eval harness run on the full golden set — all hard gates green.
- Production observability sanity check: alerts fire correctly, logs are useful, costs per workspace are visible.
- Pricing page and public marketing site (single landing page is fine).
- Privacy policy and terms of service (use a generator, get a lawyer review later).

Acceptance:

- Three real reports shipped to three real agencies for use with their clients.
- Founder can articulate, in writing, the top 3 product weaknesses and a plan to fix them.
- Eval harness green.
- Founder is willing to charge real money for what exists.

---

## Weeks 11–12 — Launch the trial

**Goal:** first paying customer.

- Reach out to the design partners from Phase 0 discovery (`docs/09-discovery-script.md`).
- Onboard them at the design partner pricing ($20/mo, locked in).
- Hold weekly feedback calls.
- Triage and fix. Don't add features unless three customers ask for the same thing.

Acceptance for "Phase 1 done": 5 paid design partners actively using the product weekly.

---

## After Phase 1

`docs/01-product-and-users.md` covers Phases 2–4 at a high level. Don't start Phase 2 work until Phase 1 acceptance is met. Resist the urge.
