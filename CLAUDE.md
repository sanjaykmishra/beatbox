# Beat — project memory for Claude Code

This file is the entry point. Read it fully, then read `docs/` in order. Wireframes are in `assets/`. Prompt sources are in `prompts/`.

## What we're building

**Beat** is an AI-powered coverage report generator for solo and boutique PR teams. The single MVP job: paste a list of press coverage URLs, get back a polished, branded, client-ready PDF report — in under 60 seconds.

**Target user:** boutique PR agency owners (2–10 people) and solo freelance PR pros, currently rebuilding monthly client reports from scratch in PowerPoint and dreading it.

**Why we win:** 10x cheaper than Cision/Muck Rack/Meltwater. Reporting-first, not database-first. LLM-native (better summaries, sentiment, narrative arcs).

**Pricing:** $39/mo solo, $99/mo agency. No free tier. 14-day trial.

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
| LLM | Anthropic API. Sonnet for extraction, Opus for executive summary | See `docs/05-llm-prompts.md` |
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

**Migrations.** Flyway. Numbered files (`V001__init.sql`). Migrations are forward-only; never edit a merged migration.

**Tests.** Unit tests next to code. Integration tests in `src/test/integration/`. The LLM eval harness lives in `src/test/eval/` and runs nightly + on every PR that touches `prompts/` or LLM client code. See `docs/06-evals.md`.

**Secrets.** Never in code. Use `.env` locally, Fly secrets in production. Required env vars are listed in `docs/02-architecture-and-stack.md`.

**Commits.** Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`). One logical change per commit.

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
- **Phase 3 (months 7–12) — pitch tracker:** `docs/12-phase-3-pitch-tracker.md`
- **Phase 4 (year 2) — platform:** `docs/13-phase-4-platform.md`
- **Additions to canonical roadmap (client context, instrumentation, digest):** `docs/15-additions.md`

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
- **Multi-tenant isolation strategy.** Row-level (workspace_id on every table, enforced by app code) for v1. Postgres RLS later if/when enterprise tier demands it.
