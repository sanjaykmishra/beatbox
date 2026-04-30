# Beat — Client dashboard + workspace badges

This package adds two related Phase 1 features to the existing `beat-spec/` project:

- **Client dashboard** at `/clients/:id` — operations cockpit replacing the placeholder settings page.
- **Workspace client list badges** — same alert signals surfaced on the client list for at-a-glance triage.

## Merge

Extract directly into your existing `beat-spec/` directory:

```
unzip beat-spec-dashboard.zip -d /path/to/beat-spec/
```

The package adds:

```
beat-spec/
├── docs/
│   └── 16-client-dashboard.md                         ← new
└── assets/
    ├── wireframe-dashboard-healthy.png                ← new
    ├── wireframe-dashboard-attention.png              ← new
    ├── wireframe-dashboard-new-client.png             ← new
    └── wireframe-client-list-badges.png               ← new
```

## Update CLAUDE.md routing

After merging, append to the "Where to find things" section of `CLAUDE.md`:

```markdown
- **Client dashboard + workspace badges:** `docs/16-client-dashboard.md`
```

## Update build plan

`docs/16-client-dashboard.md` ends with explicit slot-ins for weeks 2, 5, 8, and 9 of `docs/08-build-plan.md`. Apply manually or hand the file to Claude Code with: "apply the build-plan slot-ins from `docs/16-client-dashboard.md`."

## Why this is Phase 1

The client dashboard depends on `activity_events` (`docs/15-additions.md` §15.2) and `client_context` (§15.1) — both Phase 1 deliverables. The alert engine is a single 30-minute job + a half-dozen event hooks, fitting comfortably in Phase 1. Deferring the dashboard to Phase 2 would mean shipping a placeholder settings page on the first paid customer's most-visited surface, which is the exact problem this feature exists to fix.
