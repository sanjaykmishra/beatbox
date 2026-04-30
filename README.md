# Beat

> AI-powered coverage report generator for solo and boutique PR teams.
> Paste URLs → get a branded, client-ready PDF in 60 seconds.

## What this repo is

This is the **product specification** for Beat, structured as project memory for [Claude Code](https://claude.com/product/claude-code). The actual codebase will live alongside these docs once Phase 1 starts.

If you're a human contributor, start with `docs/01-product-and-users.md`.
If you're Claude Code, start with `CLAUDE.md`.

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
└── assets/                   ← wireframes
    ├── wireframe-1-create.png
    ├── wireframe-2-review.png
    └── wireframe-3-report.png
```

## Phase 1 in one paragraph

Ten weeks. Ship the smallest tool that turns a list of coverage URLs into a polished PDF report. Java + Spring Boot backend, React + Vite frontend, Node + Puppeteer for PDF rendering, Postgres for storage, Anthropic API for extraction and summary. No coverage monitoring, no journalist database, no pitch tracker — those are Phase 2 and 3. Land the first paying customer in week 11–12.

Full week-by-week breakdown in `docs/08-build-plan.md`.
