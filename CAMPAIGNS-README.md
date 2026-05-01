# Beat — Phase 3 campaign workflow spec (v2 with embedded wireframes)

This package extends the Beat Phase 3 spec with the campaign workflow (brief → strategy → targets → pitches → send → track → learn).

## What's in this package

```
docs/
  12a-phase-3-campaign-workflow.md    Updated spec with inline wireframe references

prompts/
  campaign-strategy-v1.md             Opus — generates the campaign strategy doc
  journalist-ranking-v1.md            Sonnet — ranks journalists against strategy
  pitch-tone-analysis-v1.md           Sonnet — per-author tone snapshot (cached monthly)
  pitch-draft-v1.md                   Opus — drafts the per-journalist pitch
  campaign-insights-v1.md             Sonnet — post-campaign retrospective

assets/
  wireframe-01-brief-intake.png         Stage 1 — paste/write the brief
  wireframe-02-strategy-review.png      Stage 1 — review AI-generated strategy
  wireframe-03-target-list.png          Stage 2 — ranked journalist list
  wireframe-04-pitch-review.png         Stage 4 — one-pitch-at-a-time review
  wireframe-05-overview-grid.png        Stage 4 — all pitches with status
  wireframe-06-close-insights.png       Stage 5 — campaign retrospective
  wireframe-07-edit-mode.png            Stage 4 — focused pitch editor
  wireframe-08-campaign-list.png        All campaigns across clients
  wireframe-09-reply-thread.png         Stage 5 — managing journalist replies
  wireframe-10-journalist-drawer.png    Stage 2 — slide-in journalist profile
  wireframe-11-rerank-modal.png         Stage 2 — confirmation when re-ranking
  wireframe-12-paused-state.png         Stage 4 — manually-paused campaign
  wireframe-13-template-picker.png      Campaign-start template picker
```

## How to use this package

Drop these directories on top of the existing `beat-spec/` directory. Files merge cleanly: the docs entry is new, the prompts are new, and the assets are new (the existing `beat-spec/assets/` folder previously held the dashboard wireframes from Phase 1).

After merging, the campaign workflow spec lives at `docs/12a-phase-3-campaign-workflow.md`. **Read that doc top to bottom before writing any campaign-related code.**

## Wireframes are the visual ground truth

The wireframes in `assets/` are not decorative — they encode design decisions the prose can't fully describe (visual hierarchy, color treatment, density, copy tone). The spec doc has a wireframes catalog at the top mapping each surface to its wireframe, plus inline image references at every structural moment in the doc.

**Required workflow when implementing a UI surface:**

1. Read the relevant section of `docs/12a-phase-3-campaign-workflow.md`.
2. View the wireframes that section references using the `view` tool — for example: `view /path/to/assets/wireframe-04-pitch-review.png`.
3. Build the surface to match the wireframe's layout, hierarchy, and copy. The prose tells you what data flows; the wireframe tells you how it's arranged.

If the wireframe and the prose disagree, the wireframe is right. The wireframes have been reviewed against design constraints the prose doesn't capture.

## What this changes about Phase 3

Phase 3 was originally ~5 months of work (the pitch tracker core). The campaign workflow adds another 8–10 weeks. **Total Phase 3 duration is now 7–8 months.** Plan funding, runway, and customer expectations accordingly.

The campaign workflow cannot be built first or in parallel with the pitch tracker — every stage depends on data and infrastructure earlier stages produce. Build the pitch tracker first, then layer the campaign workflow on top.

## Critical guardrails (non-negotiable)

These come from the main spec but apply with extra force in the campaign workflow:

1. **Pitches never auto-send.** Every pitch requires an individual human click. No batch send. No scheduled send. No "approve and forget." Not configurable.
2. **No fabricated journalist details.** Pitches reference real bylines or no bylines. Hallucinated "your recent piece on X" is worse than a generic pitch.
3. **Cell-level edits always win over re-runs.** When a user edits a pitch, regenerating preserves the edits. The `is_user_edited` + `edited_fields` discipline from Phase 1 coverage extraction applies here too.
4. **Sender reputation matters.** 30 sends/hour per workspace, smart spacing within the hour. Even if the user wants to send faster, the system won't.
5. **Insights are honest, not boosterish.** The retrospective surface should say "the EU angle didn't land" when it didn't. Confidence labels and sample-size disclaimers are required, not optional.

## Versioning

This is v2 of the campaign workflow spec. v1 (the original `beat-spec-campaigns.zip`) had the spec but no embedded wireframes. v2 adds inline wireframe references throughout. If you're reading this, use v2.
