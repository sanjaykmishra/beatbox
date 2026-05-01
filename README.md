# Beat

> AI-powered coverage report generator for solo and boutique PR teams.
> Paste URLs → get a branded, client-ready PDF in 60 seconds.

## What this repo is

The product specification and the Phase 1 codebase for Beat. Project memory for [Claude Code](https://claude.com/product/claude-code) is in `CLAUDE.md`.

If you're a human contributor, start with `docs/01-product-and-users.md`.
If you're Claude Code, start with `CLAUDE.md`.

## Local development

### One-command stack with Docker Compose (recommended)

Brings up Postgres, the api, the render service, and the static web behind nginx — built from the same Dockerfiles used for production deploys.

```bash
make up           # docker compose up -d --build
make logs         # tail everything
make ps           # status
make db           # psql shell into postgres
make down         # stop
make clean        # stop AND wipe the postgres volume
```

Then visit:

| URL                              | Service               |
|----------------------------------|-----------------------|
| http://localhost:5173            | web (nginx, proxies `/v1/*` to api) |
| http://localhost:8081/v1/healthz | api (Spring Boot)     |
| http://localhost:3000/healthz    | render (Puppeteer)    |
| `psql -h localhost -p 5433 -U beat -d beat` (password `beat`) | postgres |

API keys (Anthropic, Stripe, R2, etc.) are read from a `.env` file at the repo root if present — copy `.env.example` and fill in only what you want to exercise. The api boots with empty values for all of them; the upload endpoint returns 503 until R2 is configured, and the LLM features land in later weeks.

### Running services directly on the host

```bash
# Postgres (assuming you have one running locally)
createdb beat && createuser beat
cp .env.example .env

cd api && ./gradlew bootRun        # Spring Boot — port 8080
cd web && npm install && npm run dev   # Vite — port 5173, proxies /v1 → 8080
cd render && npm install && npm run dev   # Express — port 3000
```

`migrations/V001__init.sql` is bundled into the api JAR at `classpath:db/migration` and applied by Flyway on startup.

### Tests

```bash
make test           # api (gradle), web (typecheck + lint + build), render (typecheck + build)
make api-test       # backend only — integration tests use Testcontainers (needs Docker)
```

## Layout

```
.
├── CLAUDE.md                 ← entry point for Claude Code
├── README.md                 ← you are here
├── docs/                     ← detailed specifications
│   ├── 01-product-and-users.md
│   ├── 02-architecture-and-stack.md
│   ├── 03-data-model.md
│   ├── 04-api-surface.md
│   ├── 05-llm-prompts.md
│   ├── 06-evals.md
│   ├── 07-wireframes.md
│   ├── 08-build-plan.md
│   ├── 09-discovery-script.md
│   ├── 10-roadmap-overview.md
│   ├── 11-phase-2-features.md
│   ├── 12-phase-3-pitch-tracker.md      ← Phase 3 Part 1
│   ├── 12a-phase-3-campaign-workflow.md ← Phase 3 Part 2
│   ├── 13-phase-4-platform.md
│   ├── 14-multi-tenancy.md              ← isolation rules + pre-flight checklist
│   ├── 15-additions.md
│   ├── 16-client-dashboard.md
│   ├── 17-phase-1-5-social.md
│   └── 18-cost-engineering.md           ← system-wide AI cost discipline
├── prompts/                  ← versioned LLM prompts as standalone files
│   ├── extraction-v1.md, extraction-v1-1.md, extraction-v1-2.md
│   ├── outlet-tier-v1.md
│   ├── executive-summary-v1.md, executive-summary-v1-1.md
│   ├── reply-classification-v1.md, reply-classification-v1-1.md
│   ├── pitch-attribution-v1.md, pitch-attribution-v1-1.md
│   ├── post-variant-v1.md, post-variant-v1-1.md
│   ├── social-extraction-v1.md
│   ├── campaign-strategy-v1.md          ← Phase 3 Part 2
│   ├── journalist-ranking-v1.md         ← Phase 3 Part 2 (two-tier)
│   ├── pitch-tone-analysis-v1.md        ← Phase 3 Part 2
│   ├── pitch-draft-v1.md                ← Phase 3 Part 2 (confidence-routed)
│   └── campaign-insights-v1.md          ← Phase 3 Part 2
├── assets/                   ← wireframes (PNGs)
├── migrations/               ← Flyway SQL migrations (forward-only)
├── api/                      ← Spring Boot 3 + Java 21
├── web/                      ← Vite + React 18 + TS + Tailwind
├── render/                   ← Node + Express + Puppeteer
├── infra/                    ← Dockerfiles + Fly.io configs + nginx
├── docker-compose.yml        ← local stack (postgres + api + render + web)
├── Makefile                  ← shortcuts: up / down / logs / db / test
└── .github/workflows/        ← CI + deploy
```

The prompts directory shows the versioning lineage. v1.0 prompts are the originals; v1.1/v1.2 successors are the cost-engineered versions per `docs/18-cost-engineering.md`. Existing rows in `coverage_items`, `pitches`, etc. stay pinned to the prompt version they were created against — never re-extracted retroactively.

## Phase 1 in one paragraph

Ten weeks. Ship the smallest tool that turns a list of coverage URLs into a polished PDF report. Java + Spring Boot backend, React + Vite frontend, Node + Puppeteer for PDF rendering, Postgres for storage, Anthropic API for extraction and summary. No coverage monitoring, no journalist database, no pitch tracker — those are Phase 2 and 3.

**Phase 1 is built.** Phase 1.5 (social wedge — `docs/17-phase-1-5-social.md`) is also built. Both will need to be rebuilt to absorb the AI cost-engineering migrations described in `docs/18-cost-engineering.md` — the v1.0/v1.1 prompts they shipped against have v1.x successors that materially reduce per-workspace AI cost. The rebuild is sequenced into Phase 3 work so it doesn't block forward progress.

Full roadmap and phase gates in `docs/10-roadmap-overview.md`. Week-by-week Phase 1 build details in `docs/08-build-plan.md`.
