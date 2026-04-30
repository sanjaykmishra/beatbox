# Beat — Phases 2–4 supplement

This package adds post-Phase-1 specifications to the existing `beat-spec/` project. Extract the contents of this archive directly into the existing `beat-spec/` directory:

```
unzip beat-spec-phases-2-4.zip -d /path/to/beat-spec/
```

The new files merge alongside the existing ones:

```
beat-spec/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── 01-product-and-users.md       (existing)
│   ├── ...                           (existing)
│   ├── 09-discovery-script.md        (existing)
│   ├── 10-roadmap-overview.md        ← new
│   ├── 11-phase-2-features.md        ← new
│   ├── 12-phase-3-pitch-tracker.md   ← new
│   └── 13-phase-4-platform.md        ← new
├── prompts/
│   ├── extraction-v1.md              (existing)
│   ├── outlet-tier-v1.md             (existing)
│   ├── executive-summary-v1.md       (existing)
│   ├── reply-classification-v1.md    ← new (Phase 3)
│   └── pitch-attribution-v1.md       ← new (Phase 3)
└── assets/                           (existing)
```

## Update CLAUDE.md routing

After merging, append the following block to the "Where to find things" section of `CLAUDE.md`:

```markdown
- **Roadmap overview, phase gates:** `docs/10-roadmap-overview.md`
- **Phase 2 (months 4–7) — expand the wedge:** `docs/11-phase-2-features.md`
- **Phase 3 (months 7–12) — pitch tracker:** `docs/12-phase-3-pitch-tracker.md`
- **Phase 4 (year 2) — platform:** `docs/13-phase-4-platform.md`
```

## Starter prompt for kicking off Phase 2

Once Phase 1 is shipped and the acceptance criteria in `docs/08-build-plan.md` week 11–12 are met, paste this into a fresh Claude Code session:

> Phase 1 is complete. Read CLAUDE.md, then docs/10-roadmap-overview.md, then docs/11-phase-2-features.md. Confirm Phase 1 acceptance is met (we have ≥5 paid customers using the product weekly) before starting Phase 2 work. Once confirmed, propose a sequencing of the Phase 2 features ranked by customer-pull evidence from the design partner calls. Wait for me to approve the sequence before writing code.

The sequencing-with-approval step is intentional: Phase 2 features are roughly independent and the right order depends on which one your design partners are loudest about — that signal won't exist until Phase 1 is real.
