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
| http://localhost:8080/v1/healthz | api (Spring Boot)     |
| http://localhost:3000/healthz    | render (Puppeteer)    |
| `psql -h localhost -U beat -d beat` (password `beat`) | postgres |

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
│   └── 09-discovery-script.md
├── prompts/                  ← versioned LLM prompts as standalone files
│   ├── extraction-v1.md
│   ├── outlet-tier-v1.md
│   └── executive-summary-v1.md
├── assets/                   ← wireframes
│   ├── wireframe-1-create.png
│   ├── wireframe-2-review.png
│   └── wireframe-3-report.png
├── migrations/               ← Flyway SQL migrations (forward-only)
│   └── V001__init.sql
├── api/                      ← Spring Boot 3 + Java 21
├── web/                      ← Vite + React 18 + TS + Tailwind
├── render/                   ← Node + Express + Puppeteer
├── infra/                    ← Dockerfiles + Fly.io configs + nginx
├── docker-compose.yml        ← local stack (postgres + api + render + web)
├── Makefile                  ← shortcuts: up / down / logs / db / test
└── .github/workflows/        ← CI + deploy
```

## Phase 1 in one paragraph

Ten weeks. Ship the smallest tool that turns a list of coverage URLs into a polished PDF report. Java + Spring Boot backend, React + Vite frontend, Node + Puppeteer for PDF rendering, Postgres for storage, Anthropic API for extraction and summary. No coverage monitoring, no journalist database, no pitch tracker — those are Phase 2 and 3. Land the first paying customer in week 11–12.

Full week-by-week breakdown in `docs/08-build-plan.md`.
