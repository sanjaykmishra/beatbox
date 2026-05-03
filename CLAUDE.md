# Beat — project memory for Claude Code

This file is the entry point. Read it fully, then read `docs/` in order. Wireframes are in `assets/`. Prompt sources are in `prompts/`.

## What we're building

**Beat** is an AI-powered coverage report generator for solo and boutique PR teams. The single MVP job: paste a list of press coverage URLs, get back a polished, branded, client-ready PDF report — in under 60 seconds.

**Target user:** boutique PR agency owners (2–10 people) and solo freelance PR pros, currently rebuilding monthly client reports from scratch in PowerPoint and dreading it.

**Why we win:** 10x cheaper than Cision/Muck Rack/Meltwater. Reporting-first, not database-first. LLM-native (better summaries, sentiment, narrative arcs).

**Pricing:** $59/mo solo, $179/mo agency, $349/mo studio. No free tier. 14-day trial. Existing customers grandfathered at $39/$99 for 12 months from price change. See `docs/18-cost-engineering.md` for the cost analysis underpinning these figures.

## Build context

- Bootstrapped, small team. Optimize for shipping the wedge, not building the platform.
- Phase 1 target: first paying customer in 10–12 weeks.
- We are NOT building Cision. We are building the reporting layer that closes a ticket. Saying no to scope is the most important skill on this project.

## Tech stack (decided — don't re-litigate without good reason)

| Layer | Choice | Why |
|---|---|---|
| Backend | Spring Boot 3.x (Java 21) | Team strength is Java |
| DB | Postgres 16 | JSONB + reliability + cheap hosting |
| Queue | Postgres LISTEN/NOTIFY for v1; RabbitMQ when we outgrow it | Avoid infra sprawl early |
| Frontend | React 18 + Vite + TypeScript + Tailwind | Team strength is JS |
| PDF rendering | Puppeteer microservice (Node) | Best-in-class HTML→PDF; worth the extra service |
| LLM | Anthropic API. Haiku for high-volume mechanical work, Sonnet for judgment, Opus reserved for keystone customer-facing prose. | See `docs/05-llm-prompts.md` and `docs/18-cost-engineering.md` |
| Article fetching | Mercury Parser → Readability → paid scraper API fallback (e.g. ScrapingBee) | Layered for paywalls |
| Auth | Email/password + session cookies (no SSO until enterprise tier) | Boring is fine |
| Billing | Stripe Checkout + Customer Portal | Don't build billing UI |
| Hosting | Fly.io or Railway | One-line deploys, no AWS yet |
| Object storage | Cloudflare R2 (S3-compatible) | Cheap egress for PDFs |
| Observability | Sentry + Logtail. Postgres slow-query log on. | Enough for v1 |

## Repository layout (target)

```
beat/
├── api/                      # Spring Boot backend
│   ├── src/main/java/app/beat/
│   │   ├── auth/             # Auth controllers + session management
│   │   ├── workspace/        # Workspaces, members, branding
│   │   ├── client/           # Client CRUD
│   │   ├── report/           # Report lifecycle, generation, sharing
│   │   ├── coverage/         # Coverage items, extraction
│   │   ├── outlet/           # Outlet directory + tier classification
│   │   ├── author/           # Author directory (the future media DB)
│   │   ├── template/         # Report templates
│   │   ├── billing/          # Stripe integration + webhooks
│   │   ├── llm/              # Anthropic client wrapper, prompt loader, eval harness
│   │   ├── extraction/       # Article fetchers + extraction worker
│   │   ├── render/           # PDF render worker (calls Puppeteer service)
│   │   └── infra/            # Config, db, queue, observability
│   └── src/test/
│       ├── unit/
│       ├── integration/
│       └── eval/             # LLM golden-set runner — see docs/06-evals.md
├── web/                      # React frontend
│   ├── src/
│   │   ├── routes/
│   │   ├── components/
│   │   ├── lib/api/          # Generated API client
│   │   └── styles/
├── render/                   # Node + Puppeteer microservice
│   ├── templates/            # HTML report templates (Handlebars)
│   └── server.ts
├── prompts/                  # Versioned LLM prompts (mirrored from /prompts here)
├── migrations/               # Flyway SQL migrations
└── infra/                    # Deploy configs (Fly.io toml, Dockerfiles)
```

## Conventions

**Code style.** Java: standard Google Java style, formatted with Spotless on commit. JS/TS: Prettier + ESLint. No bikeshedding.

**Naming.** Tables snake_case plural (`coverage_items`). Java classes PascalCase singular (`CoverageItem`). REST paths kebab-case where multi-word.

**IDs.** All primary keys are UUIDs from `gen_random_uuid()`. No sequential integers exposed in URLs.

**Timestamps.** Every table has `created_at` and `updated_at` (NOT NULL, default `now()`). User-facing entities also have `deleted_at` (NULL = active). Use `timestamptz`, never `timestamp`.

**API errors.** RFC 7807 problem-details. Every response includes a `request_id` header for support.

**UI page chrome.** Every protected route in `web/src/routes/` wraps its content in `BrowserFrame` with a `crumbs` array. Every intermediate crumb has a `to:` field so users can navigate up the hierarchy (e.g. `[{label: '${slug}.beat.app', to: '/clients'}, {label: 'clients', to: '/clients'}, {label: clientName}]`); the final crumb (the current page) has no `to`. The chrome strip is the canonical breadcrumb; do not also render an in-body `<nav>` breadcrumb. Each page's `<h1>` is followed by a one-line muted help subtitle (`<p className="mt-1 text-xs text-gray-500">…</p>`) explaining what the page does and any non-obvious behavior (e.g. "Field changes save automatically when you click away.", "Click any item to edit before generating. Edits stick across re-runs."). Auth pages (`AuthShell`) and the 404 are exempt — they're outside the workspace shell.

**User-visible feedback.** Two primitives:
- **`<Alert tone="danger|warning|success|info" title=… onDismiss=… action=…>`** in `web/src/components/ui.tsx` — banner shape, persistent until dismissed. Use for backend rejections, validation errors, non-blocking concerns (trial ending, grandfathered pricing), and page-level guidance. Anchor it inside the page near the action that produced it.
- **`useToast()` from `web/src/components/Toast.tsx`** — transient confirmations (`toast.success('Saved.')`, `toast.error('Couldn\'t save.')`). Top-right stack, auto-dismisses after ~3.5 seconds (errors get 6s). Mounted once via `<ToastProvider>` at the app root in `main.tsx`.

Never use a bare `<p className="text-red-600">` or similar for user-facing feedback — those are easy to miss, don't dismiss, and don't stack. Pick `Alert` for "this needs attention" and `useToast()` for "this just happened."

**Destructive confirmations.** Use **`useConfirm()` from `web/src/components/ConfirmDialog.tsx`** — never the native `window.confirm()`. The browser default renders unstyled chrome ("localhost:5173 says…") that breaks the product's visual identity. Pattern: `const ok = await confirm({title: 'Delete this post?', body: 'This cannot be undone.', tone: 'danger', confirmLabel: 'Delete post'}); if (!ok) return;`. Provider is mounted once at the app root via `<ConfirmDialogProvider>` in `main.tsx`. Use `tone: 'danger'` for delete/destroy actions; default for benign confirmations.

**Migrations.** Flyway. Numbered files (`V001__init.sql`). Migrations are forward-only; never edit a merged migration.

**Tests.** Unit tests next to code. Integration tests in `src/test/integration/`. The LLM eval harness lives in `src/test/eval/` and runs nightly + on every PR that touches `prompts/` or LLM client code. See `docs/06-evals.md`.

**Secrets.** Never in code. Use `.env` locally, Fly secrets in production. Required env vars are listed in `docs/02-architecture-and-stack.md`.

**Commits.** Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`). One logical change per commit.

## Engineering rules of thumb (lessons learned)

These exist because each one represents a real bug that already shipped. When in doubt, check the named exemplar file before writing new code.

**Mirror existing conventions before writing new infra code.** Before creating a new `*Repository`, `*Controller`, or `*Service`, grep for an analogous one and copy its parameter-binding patterns rather than improvising. `app.beat.report.ReportRepository` and `app.beat.auth.SessionRepository` are the reference implementations for JdbcClient usage.

**Postgres JDBC parameter binding.** Two foot-guns hit us in the same week:
- `java.time.Instant` cannot be passed via `setObject()`. Always wrap with `java.sql.Timestamp.from(instant)` (handle null). See `OwnedPostRepository.ts(Instant)`.
- Inside `COALESCE(:param, column)`, every bound parameter needs an explicit cast — including scalars when the value is null. Use `CAST(:p AS text)`, `CAST(:p AS timestamptz)`, `CAST(:tp AS text[])`, `CAST(:variants AS jsonb)`. Without the cast, Postgres throws "could not determine data type of parameter $N". Pattern reference: `OwnedPostRepository.update`.

**Validation annotations are type-specific.** `@NotBlank` only validates `CharSequence` and throws `UnexpectedTypeException` (→ 500) on `UUID`/numeric types. Use `@NotNull` for non-string required fields.

**Bundled JAR resources need both gradle config AND Dockerfile copy.** `api/build.gradle` bundles `migrations/` and `prompts/` via `processResources { from('../X') }`. The Dockerfile must `COPY` each of those source directories into the build context, or the JAR ships without them and `PromptLoader.get()` / Flyway fail at runtime. Locally everything works because the directories exist on disk. See `infra/api/Dockerfile`.

**Smoke-test LLM calls with a real key before claiming a feature is done.** Prompt frontmatter is opaque — model IDs, temperature, max_tokens — and the only way to catch typos is one real call. Run the endpoint once locally with `ANTHROPIC_API_KEY` set before merging.

**Test the path the SPA actually exercises.** Auto-save-on-blur paths usually patch with mostly-null scalars; happy-path tests usually create with all fields set. They're different SQL paths. Integration tests should mirror the request shape the frontend sends, not just the canonical happy path.

**Surface 5xx errors in logs.** `ProblemDetailHandler` and `RequestLogFilter` log unhandled exceptions and per-request access lines. Don't add a swallow-and-return-500 path that bypasses them — every 5xx should leave a stack trace.

**Cross-layer changes need a touch-point checklist before commit.** Adding a value to a domain enum touches at minimum: (1) the prompt(s) that ask the LLM for it, (2) the Java schema validator that parses LLM output, (3) the controller `@Pattern` validators on PATCH/PUT endpoints, (4) the Postgres `CHECK` constraint on the column, (5) the SPA TypeScript union type, (6) any UI dropdown that lists the values. Lint + tests + tsc passing means the *types* line up; it does not mean the DB or the LLM contract does. Real example: the `subject_prominence = 'missing'` rollout shipped (1)(2)(3)(5)(6) and broke at runtime on (4) when the worker tried to write the new value. Before claiming a schema/enum change is done, grep the old value across `prompts/`, `api/src/main/java/`, `migrations/`, and `web/src/` and verify each hit is updated or intentionally legacy.

**Same idiom in two places means check both when changing one.** Repository-layer COALESCE patterns are the most common offender. `screenshot_url = COALESCE(screenshot_url, :ss)` ("keep old, fall back to new") was wrong for re-extract but right for user-edit-preservation; the same idiom appeared in adjacent fields with the opposite required direction. When touching one COALESCE direction in `applyFetched` (or any repository), audit every COALESCE in the same method and decide each one independently. Pattern reference: `CoverageItemRepository.applyFetched` after V012.

**Visual changes aren't verified until they're loaded in a browser.** Type-check + lint + render-template-compiles is not the same as "this looks right." For SPA changes that affect layout, screen-mode CSS (`@media screen` blocks in `render/templates/`), iframe-embedded HTML, or anything cross-origin (public share, srcDoc), explicitly state in the report-back: "I haven't loaded this in a browser; please verify." Don't paper over the gap by claiming it works when it hasn't been seen.

**For prompt-version changes, trace dispatch from the worker, not from the prompt loader.** It's possible to update a prompt file, bump the schema, wire the loader, and ship — yet have no production code path actually call it. Article extraction has two services (`ExtractionService` legacy + `TwoTierExtractionService` two-tier), with a `beat.prompts.extraction.tier` flag dispatching between them; updating the v1.3 prompt while the flag still pointed to legacy meant every "re-extract" cycle re-ran v1.0 and burned API budget. Before claiming a prompt change works: open the worker (e.g. `ExtractionWorker.handleJob`), follow the call into the service, and verify the path you edited is the one that actually runs under the current config. Pattern reference: `ExtractionService.extract` dispatch by `mode`.

**Every AI integration must comply with `docs/18-cost-engineering.md` before merge.** New LLM call sites and changes to existing ones (new prompt, new service, new endpoint, new feature flag) are not done until they apply the relevant levers from the doc and the cost-engineered prompt is the production default — not just code that exists behind a flag nobody flips. Failure mode that has shipped twice: cost-engineered prompts (`post-variant-v1-1`, `executive-summary-v1-1/v1-2`) were built with cache markers and Sonnet-tier models, but the calling service kept loading the legacy stem (`post-variant-v1`) or the YAML default kept pointing at `v1` Opus — so the doc's projected savings were entirely on paper while production paid full Opus rates. Pre-merge checklist for any AI integration:
- (1) **Model tiering** — does this call default to the cheapest acceptable tier (Haiku → Sonnet → Opus)? If escalation is needed, is the threshold confidence-based and cached?
- (2) **Prompt caching** — does the prompt mark stable instructions with `[CACHED — …]` / `[NOT CACHED — per-X]`? Does the call site use `AnthropicClient.callMaybeCached(...)` (or pass the cached system block explicitly)?
- (3) **Context compression** — is the input trimmed to what the prompt actually needs (top-N items, aggregated counts) instead of the full row dump?
- (4) **Batching** — is this user-async (summary generation, ranking, attribution)? If so, route via Anthropic Batches when latency allows.
- (5) **Deterministic pre-filter** — can a regex / SQL / hash check eliminate this call entirely? URL prefilter, content-hash dedup, prominence-aware short-circuit (`SummaryService.hasNoSubstantiveCoverage`) are existing examples.
- (6) **Default-flip discipline** — when shipping a v1.1 / v1.2 successor of an existing prompt, the YAML default in `application.yml` AND the Java `@Value` fallback in the service AND the `normalizeMode` unknown-input fallback all need to point at the new version. Grep all three before claiming the migration is done. Reference fix: `git show 12059bb 6c27acc`.

If a lever doesn't apply, say so explicitly in the commit message — silent omission is the same failure mode as not knowing about the lever.

## Critical guardrails

These exist because failure here is high-cost. Don't relax them without explicit human sign-off.

1. **Never hot-swap a prompt.** Bump the version string (`extraction_v1.3` → `extraction_v1.4`), store the version on the `coverage_items` row, and go through eval review. See `docs/05-llm-prompts.md`.
2. **Never ship if the eval harness is failing.** The pre-launch gate in `docs/06-evals.md` is a hard gate. Hallucination rate must be 0 on the golden set.
3. **Never invent attributions in summaries.** If the LLM returns a quote or fact not in the source article, that's a sev-1 bug. The whole product trades on agency trust.
4. **Cell-level edits always win.** When a user edits an extracted field on a coverage item, that edit is sticky across re-runs. Worker code must respect `is_user_edited` flags.
5. **Reports lock at generation time.** Once a report is finalized, coverage_items are frozen. Re-runs require explicit user action.
6. **Brand-safe by default.** Every PDF carries the agency's branding (their logo, their colors), never Beat's. Beat is invisible to the agency's clients.
7. **Activity events fire on every meaningful action.** When adding new mutations, add the corresponding `activity_events` write. The founder dashboard, customer analytics, and Phase 3+ features depend on this discipline. See `docs/15-additions.md` §15.2.
8. **Social mentions are first-class.** When new features reason about "what's been written about a client," they include both `coverage_items` and `social_mentions`. Don't accidentally regress to article-only logic.
9. **Pitches never auto-send.** Every individual pitch requires a human click. There is no auto-send mode, no scheduled bulk-send, no "approve and forget" workflow. See `docs/12a-phase-3-campaign-workflow.md` for full reasoning. Non-negotiable.
10. **Cost engineering disciplines apply across every AI surface.** Model tiering (Haiku/Sonnet/Opus by signal value), prompt caching, context compression, batching async work, and deterministic pre-filters are not optional optimizations — they're how the cost-to-revenue ratio works. See `docs/18-cost-engineering.md` for the per-feature lever map and per-month cost targets. Every new or changed AI integration runs through the **"Every AI integration must comply with `docs/18-cost-engineering.md` before merge"** checklist in the engineering rules of thumb above before commit; silent omission is treated as non-compliance. Eval gates are non-negotiable; quality preservation gates every cost migration.

## Where to find things

- **Product overview, target users, scope:** `docs/01-product-and-users.md`
- **Architecture, services, env vars, deploy:** `docs/02-architecture-and-stack.md`
- **Postgres schema with DDL:** `docs/03-data-model.md`
- **Full REST API surface:** `docs/04-api-surface.md`
- **LLM prompts and versioning:** `docs/05-llm-prompts.md` (sources in `prompts/`)
- **Eval set construction and metrics:** `docs/06-evals.md`
- **Wireframes for the core flow:** `docs/07-wireframes.md` (PNGs in `assets/`)
- **Phase 1 build plan, week by week:** `docs/08-build-plan.md`
- **Customer discovery script (founder reference, not for code):** `docs/09-discovery-script.md`
- **Roadmap overview, phase gates:** `docs/10-roadmap-overview.md`
- **Phase 1.5 (months 3–4) — social wedge:** `docs/17-phase-1-5-social.md`
- **Phase 2 (months 4–7) — expand the wedge:** `docs/11-phase-2-features.md`
- **Phase 3 (months 7–12) — pitch tracker (Part 1):** `docs/12-phase-3-pitch-tracker.md`
- **Phase 3 (months 12–15) — campaign workflow (Part 2):** `docs/12a-phase-3-campaign-workflow.md`
- **Phase 4 (year 2) — platform:** `docs/13-phase-4-platform.md`
- **Multi-tenancy rules + new-table pre-flight checklist:** `docs/14-multi-tenancy.md`
- **Additions to canonical roadmap (client context, instrumentation, digest):** `docs/15-additions.md`
- **Client dashboard and workspace badges:** `docs/16-client-dashboard.md`
- **Phase 1.5 (months 3–4) — social wedge:** `docs/17-phase-1-5-social.md`
- **AI cost engineering across all surfaces:** `docs/18-cost-engineering.md`

## Getting started with Claude Code

The first session should bootstrap the repo. A reasonable starter prompt:

> Read CLAUDE.md and all of docs/ in order. Then read prompts/ and look at the wireframes in assets/. Confirm you understand the scope before writing code. Then bootstrap the repo per docs/02-architecture-and-stack.md and start week 1 of docs/08-build-plan.md.

Subsequent sessions can be scoped to a specific week or feature, e.g.:

> We're on week 4 of docs/08-build-plan.md. Read prompts/extraction-v1.md and docs/05-llm-prompts.md and docs/06-evals.md. Implement the article extraction worker and the golden-set eval harness. Stop after the harness runs green on a 5-article seed set; we'll grow it next session.

## Open decisions (call out before coding)

These are things I haven't fully decided. If you hit one, raise it — don't pick silently.

- **Outlet curation source.** ~500 hand-curated outlets. Where does the seed list come from? Probably scraped from Cision/Muck Rack public lists + manual review.
- **Sentiment model.** Pure LLM, or LLM + a separate FinBERT-style classifier as a sanity check? Starting LLM-only; revisit if eval scores plateau.
- **Reach numbers.** Domain Authority (Moz/Ahrefs) vs. SimilarWeb visit estimates. Need to pick one source and stick with it for consistency. Defaulting to DA via DataForSEO until proven inadequate.
- **Multi-tenant isolation strategy.** Row-level (workspace_id on every table, enforced by app code) for v1. Postgres RLS later if/when enterprise tier demands it. See `docs/14-multi-tenancy.md` for current rules and the pre-flight checklist for new tenant tables.
