# Beat — Phase 1.5 social wedge

This package adds a half-phase between Phase 1 and Phase 2 of the existing `beat-spec/` project. It extends Beat from a traditional-PR reporting tool into one that handles the modern hybrid agency workflow: earned articles + earned social + owned content + client approvals.

## What's in the package

- **`docs/17-phase-1-5-social.md`** — full spec for six features:
  1. Social mentions as first-class coverage
  2. Editorial calendar (planning-only, not native publishing)
  3. Asset library
  4. Client approval workflow
  5. Social content in unified reports
  6. Manual social listening
- **`prompts/social-extraction-v1.md`** — extracts structured data from social posts (parallel to article extraction).
- **`prompts/post-variant-v1.md`** — generates platform-specific adaptations of master content for the editorial calendar composer.

## Merge

Extract directly into your existing `beat-spec/` directory:

```
unzip beat-spec-phase-1-5.zip -d /path/to/beat-spec/
```

The package adds:

```
beat-spec/
├── docs/
│   └── 17-phase-1-5-social.md           ← new
└── prompts/
    ├── social-extraction-v1.md          ← new
    └── post-variant-v1.md               ← new
```

## Update CLAUDE.md routing

Append to the "Where to find things" section of `CLAUDE.md`:

```markdown
- **Phase 1.5 (months 3–4) — social wedge:** `docs/17-phase-1-5-social.md`
```

Add a new critical guardrail:

```markdown
8. **Social mentions are first-class.** When new features reason about "what's been written about a client," they include both `coverage_items` and `social_mentions`. Don't accidentally regress to article-only logic.
```

## Why this is Phase 1.5, not Phase 2

Phase 2 in the canonical roadmap (`docs/11-phase-2-features.md`) focuses on deepening the reporting workflow: templates, teams, analytics, portal. Adding social into Phase 2 would either delay Phase 2 by months or force one of the existing Phase 2 features out. Better to declare a half-phase that stands on its own with a tight 6–8 week scope.

The phase gate from Phase 1 to Phase 1.5 is the same as Phase 1 → 2 in the original roadmap (20 customers, $2K MRR, NPS ≥ 40, eval green) plus one social-specific signal: at least 5 of 10 design partner calls confirming "we want to track social alongside earned media."

## What this does NOT include

Read `docs/17-phase-1-5-social.md` "Phase boundaries" for the full list, but the highlights:

- **No native publishing to platforms.** The calendar plans posts; the user publishes via their existing tool. Native publishing is Phase 2+ via Buffer/Later integration, deeper native APIs in Phase 3+.
- **No real-time monitoring or crisis detection.** Phase 4.
- **No influencer/creator outreach.** Phase 3 — extends pitch tracker.
- **No AI image generation.** Phase 3+.

## Starter prompt for Claude Code

Once Phase 1 acceptance is met:

> Read CLAUDE.md, then docs/10-roadmap-overview.md, then docs/17-phase-1-5-social.md. Confirm Phase 1 acceptance is met before starting (≥20 paying customers, $2K MRR, eval green). Then read prompts/social-extraction-v1.md and prompts/post-variant-v1.md. Propose a sequencing of the six Phase 1.5 features ranked by customer-pull evidence from your design partner conversations — the spec suggests an order but the right order depends on what your customers are loudest about. Wait for me to approve the sequence before writing code.
