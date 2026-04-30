# Beat — Additions to canonical roadmap

This package adds three features that span phases:

- **Client context** ("second brain" per-client notes that flow into LLM prompts) — Phase 1
- **Activity instrumentation** (`activity_events` table for analytics + dashboards) — Phase 1
- **Weekly digest** (automated email summaries to make the tool sticky) — Phase 2

All three were originally scoped as dogfood-phase features, then promoted into the canonical roadmap because they have product-grade value at every phase.

## Merge

Extract directly into your existing `beat-spec/` directory:

```
unzip beat-spec-additions.zip -d /path/to/beat-spec/
```

The package adds one file:

```
beat-spec/
├── docs/
│   └── 15-additions.md           ← new
```

## Update existing docs after merging

`docs/15-additions.md` ends with explicit copy-paste instructions for updating:

- `CLAUDE.md` — routing entry + new critical guardrail
- `docs/08-build-plan.md` — slot-ins for weeks 2, 4, 5, 7, 9
- `docs/11-phase-2-features.md` — promote §15.3 into a new §11.8 when Phase 2 begins
- `docs/06-evals.md` — eval set additions

These are intentionally NOT auto-applied because the existing files in your repo may have evolved since the spec was last bundled. Open `docs/15-additions.md` and apply the edits manually (or hand the file to Claude Code with the instruction "apply the build-plan slot-ins and CLAUDE.md routing update from docs/15-additions.md").

## Why these features land where they do

**Client context (Phase 1)** — improves LLM extraction quality and is the foundation for codifying agency knowledge. The prompt integration is the high-leverage piece, so it ships from day one rather than getting bolted on later.

**Activity instrumentation (Phase 1)** — infrastructure-shaped. Painful to retrofit across dozens of endpoints; trivial to add as you build. Phase 2's per-client analytics and Phase 3's pitch analytics need historical data, so the capture must start with the first endpoint.

**Weekly digest (Phase 2)** — depends on activity history (Phase 1) and team scopes (Phase 2). Builds on existing data; no new schema for sources. Slots naturally alongside Phase 2's other notification/email work.
