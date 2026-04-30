# 02 — Architecture and stack

## System diagram

```
                    ┌─────────────────┐
                    │   React + Vite  │
                    │     (web/)      │
                    └────────┬────────┘
                             │ JSON over HTTPS
                    ┌────────▼────────┐
                    │  Spring Boot    │
                    │     (api/)      │
                    └─┬──┬──┬──┬──────┘
        ┌─────────────┘  │  │  └────────────────┐
        │                │  │                   │
   ┌────▼────┐   ┌───────▼──▼────┐    ┌─────────▼─────────┐
   │Postgres │   │ Anthropic API │    │ Puppeteer service │
   │   (DB)  │   │ (Sonnet/Opus) │    │     (render/)     │
   └────┬────┘   └───────────────┘    └─────────┬─────────┘
        │                                        │
        │  LISTEN/NOTIFY                         │ outputs
        │                                        │
   ┌────▼─────────────┐               ┌──────────▼──────────┐
   │ Extraction worker│               │ Cloudflare R2 (PDFs)│
   │ (Spring app job) │               └─────────────────────┘
   └──────┬───────────┘
          │ fetches
          ▼
   ┌──────────────────┐
   │ Article fetcher  │
   │  Mercury → R'lty │
   │  → ScrapingBee   │
   └──────────────────┘
```

## Services

### `api/` — Spring Boot 3.x, Java 21

The monolith. Handles everything except PDF rendering: HTTP, auth, business logic, the extraction worker, the render dispatcher, billing webhooks. Single deployable unit.

Why a monolith: we're a small team. A microservice architecture for an MVP is premature optimization. We'll split when one of these services becomes a clear bottleneck or independently scaling concern.

Background work runs in the same JVM, dispatched via Postgres `LISTEN/NOTIFY`. When extraction queue depth or render parallelism becomes a problem we move to RabbitMQ. Not before.

### `render/` — Node 20 + Puppeteer

A small Express service that takes a JSON payload (report data + template ID) and returns a PDF buffer. Stateless. Internal-only — only `api/` calls it. Deployed alongside the API but as a separate service because:

1. Puppeteer is heavy and benefits from its own resource budget.
2. HTML→PDF is a Node-native problem, not worth fighting in Java.
3. Templates are HTML+Handlebars, much faster to iterate on than a Java templating system.

### `web/` — React 18 + Vite + TypeScript + Tailwind

SPA. Routes via React Router. State via React Query for server state, plain hooks/context for UI state. No Redux. No state management library debate.

API client generated from an OpenAPI spec exported by the backend. Source of truth is the Spring Boot annotations.

## Async patterns

Two worker types, both inside the Spring Boot app:

### Extraction worker

Triggered by inserts into `extraction_jobs (status='queued')`. One job per coverage URL. Per job:

1. Mark job `running`, increment `attempt_count`.
2. Fetch article HTML (Mercury → Readability → ScrapingBee fallback).
3. Call Anthropic Sonnet with the extraction prompt.
4. Parse JSON, validate against schema.
5. Lookup or create `outlets` and `authors` rows.
6. Update `coverage_items` with extracted fields.
7. Mark job `done`, or on failure `failed` with `last_error`.

Idempotent on `coverage_item_id`. Retries with exponential backoff (2s, 8s, 30s), max 3 attempts. After 3 failures, the item enters `failed` state; the user sees an "extraction failed — paste content manually?" prompt in the UI.

Worker concurrency: 4 per pod. Per-workspace rate limit enforced before dispatch.

### Render worker

Triggered when a report transitions to `processing` status. One job per report.

1. Load report + all coverage items.
2. Generate executive summary via Anthropic Opus.
3. POST report payload to `render/` service.
4. Receive PDF buffer.
5. Upload to R2 with a signed URL.
6. Update report status to `ready`, store PDF URL.

If executive summary generation fails, retry once. If render fails, retry once. After two failures, mark `failed` and notify the user.

## Data flow: a complete report cycle

```
User pastes 14 URLs → POST /reports/:id/coverage
                       creates 14 coverage_items (status=queued)
                       creates 14 extraction_jobs
                       returns 14 placeholder cards immediately

LISTEN/NOTIFY wakes 4 workers in parallel.

Each worker:
  fetch HTML → call Anthropic Sonnet → parse JSON
  → upsert outlet + author → update coverage_item
  → broadcast update over SSE (Phase 2; for v1, frontend polls /reports/:id every 2s)

User reviews, edits inline → PATCH /reports/:id/coverage/:item_id
                              sets is_user_edited=true on touched fields

User clicks "Generate report" → POST /reports/:id/generate
                                 sets report status=processing
                                 dispatches render job

Render worker:
  build summary input → call Anthropic Opus → render via Puppeteer
  → upload PDF to R2 → update report status=ready

User downloads or shares.
```

## Required environment variables

Listed here as the source of truth. Mirror in `.env.example`.

```
# Database
DATABASE_URL=postgres://...

# Anthropic
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_MODEL_EXTRACTION=claude-sonnet-4-...
ANTHROPIC_MODEL_SUMMARY=claude-opus-...

# Stripe
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PRICE_SOLO=price_...
STRIPE_PRICE_AGENCY=price_...

# Storage
R2_ACCOUNT_ID=...
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET=beat-pdfs
R2_PUBLIC_BASE_URL=https://...

# Article scraping
SCRAPINGBEE_API_KEY=...

# Outlet enrichment
DATAFORSEO_LOGIN=...
DATAFORSEO_PASSWORD=...

# Email (transactional only — Resend or Postmark, pick one)
RESEND_API_KEY=re_...

# Render service
RENDER_SERVICE_URL=http://render:3000
RENDER_SERVICE_TOKEN=...   # shared secret for internal auth

# Observability
SENTRY_DSN=...
LOGTAIL_TOKEN=...

# App
APP_BASE_URL=https://beat.app
SESSION_SECRET=...   # 64+ random bytes
```

## Deploy

Fly.io. Single org, three apps:

- `beat-api` — Spring Boot, 2 vCPU / 2 GB RAM, 2 instances minimum.
- `beat-render` — Node + Puppeteer, 2 vCPU / 4 GB RAM (Puppeteer is RAM-hungry), 1 instance, autoscale to 3.
- `beat-web` — Static SPA served via Fly's static config. Single global instance.

Postgres: Fly Postgres cluster, 2 nodes, one primary + one replica. Daily snapshots to R2.

CI/CD: GitHub Actions. On merge to `main`: lint → test → eval → build → deploy. Eval failures block deploy. See `docs/06-evals.md`.

## Observability minimums

- Sentry for unhandled exceptions in both `api/` and `web/`.
- Logtail for structured logs (one log per request, JSON).
- Postgres slow-query log threshold 200ms.
- Custom metrics:
  - `extraction.duration_seconds` (histogram, by outcome)
  - `extraction.cost_usd` (counter, per workspace)
  - `report.generation_seconds` (histogram)
  - `report.generated_total` (counter, per workspace, per plan)
- Weekly cost review: Anthropic spend per workspace, with alerts on outliers.

## What's deliberately not here

- No service mesh.
- No Kubernetes.
- No Kafka.
- No separate read replicas for the app (one DB, primary writes, replica for backups only).
- No Redis (yet — Postgres covers caching at our scale via materialized views or unlogged tables if we need it).
- No GraphQL.
- No microservices for individual domains.

If any of these are needed, write a 1-page case before adding.
