# 12a — Phase 3 (Part 2, months 12–15): Campaign workflow

This doc extends `docs/12-phase-3-pitch-tracker.md`. It adds the campaign workflow: brief → strategy → target list → personalized pitches → send → track → learn.

The campaign workflow integrates tightly with the pitch tracker. Sent pitches become rows in the existing `pitches` table; replies and attribution flow through unchanged. Campaigns are the **front-end orchestration layer** that sits on top of the pitch tracker, not a parallel system.

## What and why

A "campaign" in PR is a coordinated outreach effort tied to a news moment: product launch, funding round, executive announcement, thought-leadership push. The workflow is universally familiar:

1. Write a brief describing what's being announced and the goals.
2. Develop a strategy: angles, audiences, hooks, timing.
3. Build a target media list — which journalists to contact.
4. Draft personalized pitches.
5. Send pitches; manage replies; track coverage.
6. Retrospective: what worked, what didn't.

Today this happens in spreadsheets, email drafts folders, and senior practitioners' heads. The pitch tracker (Part 1) handles steps 5-6 well. The campaign workflow extends to handle 1-4 — the strategic and creative work — with AI acceleration that respects human judgment.

### Why now (Phase 3, not earlier)

The campaign workflow needs:
- A populated `authors` database (Phase 1 + Phase 3 coverage ingest)
- Recent-articles enrichment per author (Part 1 §12.5)
- Per-author response-rate signals (Part 1 §12.2)
- Pitch→coverage attribution data (Part 1 §12.3)
- Per-workspace pitch history (Part 1 §12.1)
- Cross-customer aggregate patterns (Phase 1.5 social mentions extend the dataset)

Without these, the AI ranking and pitch drafting would cold-start poorly. By month 12, the data exists.

### Why not Phase 4

Phase 4 is enterprise + monitoring + integrations. Campaigns are a core agency workflow, not an enterprise add-on. Slotting them into Phase 4 would mean shipping the agency's most-needed feature behind enterprise pricing, which inverts the wedge logic.

## Critical guardrail: human-in-the-loop, always

**Pitches never send automatically.** Every individual pitch requires a human click on a "Send" button before it leaves Beat. There is no auto-send mode, no scheduled bulk-send, no "approve and forget" workflow. Configurability on this is **deliberately not exposed** — the simplest possible model is the safest.

The reasoning, briefly:

- The cost of one wrong auto-sent pitch (sent to the wrong journalist, with the wrong client name, with a hallucinated quote) is potentially permanent damage to a relationship.
- The cost of a human click is ~2 seconds. Across 50 pitches in a campaign, that's a few minutes of friction — well below the time saved by AI drafting.
- An "approve and send all" button looks similar but introduces a class of failure modes (a single bad pitch in the batch goes out unchecked) that the always-individual click pattern eliminates.

If, in a later phase, customer demand for higher-volume sending is loud and the data shows we can ship safe guardrails, we'll revisit. Not before.

This guardrail belongs in `CLAUDE.md`'s critical guardrails list as well.

## Architecture overview

The pipeline is built around five distinct AI calls. Each call produces editable output. The agency stays in control at every step; the AI accelerates without making decisions.

```
Campaign Brief → Strategy Doc → Target List → Per-target pitch drafts → Human review → Send → Track + Learn
                  (Opus)        (Haiku→Sonnet  (Opus or Sonnet by      (manual)       (Beat or
                                 escalation)    confidence tier)                       mailto:)
```

Each AI surface follows the cost engineering discipline in `docs/18-cost-engineering.md`. In particular: ranking is two-tier (Haiku first-pass, Sonnet escalation on borderline scores); drafting is confidence-routed (Opus for high-confidence targets, Sonnet for medium and low); ranking and drafting both run via the Anthropic Batches API for the 50% async discount.

## The five stages

### Stage 1 — Brief intake + strategy generation

The agency provides a brief. Three input modes:

1. **Free-form text.** Paste a memo, an email thread, a Google Doc — whatever exists. Most realistic option.
2. **Structured form.** Guided fields: announcement type, key facts, dates, materials, goals, constraints. Better data, more friction.
3. **Hybrid.** Paste free-form, then fill structured fields where data is missing. Best of both.

Whatever the input, the system extracts and generates a **campaign strategy document** with:

- **Key narratives** — the 2-4 storylines this campaign supports.
- **Target audiences** — who needs to read it (segmented).
- **Industries / topics** — beat tags relevant to the story.
- **News hooks** — why this matters now (competitive context, regulatory moments, trending topics, anniversaries, data points).
- **Angles per audience** — different framings for different reader types.
- **Suggested timing** — embargo strategy, day-of-week recommendation, sequencing across audiences.
- **Risks and considerations** — what could go wrong; sensitive context the AI noticed.
- **Missing information** — what the brief should have included but didn't.

The output is readable prose plus structured data. The agency can edit any section. Strategy edits trigger downstream re-runs of ranking and drafting (with explicit confirmation, so the user doesn't accidentally invalidate hours of editing).

The strategy prompt is `prompts/campaign-strategy-v1.md`. Opus, with prompt-cached client context and instruction blocks per the cost engineering pass.

### Stage 2 — Target list generation

Given the strategy, the system ranks journalists from `authors` by likelihood to cover. Multi-signal score:

- **Topic alignment** — do their recent bylines match the strategy's topics and industries?
- **Beat fit** — do their stated/inferred beats align? (`preferred_topics` from §12.5.)
- **Audience reach** — do they write for outlets matching the target audiences?
- **Recency** — have they written about adjacent stories recently?
- **Historical responsiveness** — do they reply to pitches generally? To this agency specifically? (Per-author and per-workspace signals from §12.2 reply tracking.)
- **Past coverage of this client** — covered them positively? More likely to again. Negatively? Skip. Multiple times recently? Probably saturated.
- **Coverage exhaustion** — has this journalist already covered the same angle this week?
- **Outlet tier preferences** — the brief specifies target outlet tier mix.

A SQL pre-filter narrows the pool from the workspace's full author database (potentially thousands) to the 80-120 high-priors that match the strategy's topics and recency. Only these reach the LLM.

Output: a ranked list with **confidence labels** (`high`/`medium`/`low`/`exploratory`) and a **"why they matter"** rationale per journalist — 2-3 sentences grounded in their actual recent bylines.

The agency can:

- Add a journalist who isn't on the list.
- Remove a journalist (with optional reason captured for learning).
- Reorder by their own judgment.
- See the full journalist profile (§12.5) inline before committing.
- Set a target list size (e.g., "top 25" or "top 75" or "all with score ≥ 60").

The ranking is rerunnable — strategy edits trigger a re-rank with confirmation.

The ranking prompt is `prompts/journalist-ranking-v1.md`. Two-tier per the cost engineering pass: Haiku scores all candidates first; Sonnet escalates only the 45-80 borderline band.

### Stage 3 — Personalized pitch drafting

For each target journalist, generate a personalized pitch grounded in:

- The campaign strategy.
- The journalist's recent articles (top 3-5 most relevant, selected by embedding similarity to campaign topics).
- The journalist's tone and structure preferences (inferred from past pieces — see `prompts/pitch-tone-analysis-v1.md`).
- Past successful pitches to that journalist from this workspace (if any).
- Aggregate patterns across customers (anonymized).
- Client style notes from `client_context` (`docs/15-additions.md`).
- Optional agency-specific signature/template.

The generated pitch includes:

- Subject line + 2 alternates.
- Body.
- Suggested follow-up timing.
- A **"why this for this journalist"** rationale visible to the user, never to the journalist.
- Confidence label (some pitches are weaker than others — surface that honestly).

The drafting prompt is `prompts/pitch-draft-v1.md`. Confidence-routed per the cost engineering pass: Opus for high-confidence targets (score ≥80), Sonnet for medium (60-79), Sonnet-short for low-confidence (40-59). Campaign strategy and brand voice are prompt-cached across the 50 pitches in a campaign.

### Stage 4 — Human review + send

A focused review surface — one journalist at a time — with their full profile and recent articles visible alongside the pitch. The user can:

- **Edit** subject and body inline.
- **Regenerate** (with optional steering: "make it shorter," "more curious tone," "lead with the data point").
- **Skip** (with optional reason captured for learning).
- **Send** — explicit click per pitch.

Two send modes, chosen per pitch:

- **Beat-sent.** Pitch goes via Beat's transactional infrastructure (Postmark). Full tracking — opens, replies routed back through threading, all the Part 1 mechanics. Lower deliverability than personal email; the journalist sees `agency-name@send.beat.app` (or workspace-configured custom domain) as the sender.
- **mailto:.** Opens the user's email client with the pitch pre-filled. The user reviews and clicks send in their own email client. Captured back via the BCC capture or browser extension from §12.1. Higher deliverability; some tracking fidelity loss (no open tracking, replies need explicit forwarding setup).

The user picks per pitch. A workspace-level default exists ("default to mailto:" or "default to Beat-sent") but the user always sees both options on the review surface.

The system enforces strict pacing: even Beat-sent pitches go out at 30 sends/hour per workspace, with smart spacing within that hour to avoid sending bursts that look like spam to email providers. mailto: sends bypass the rate limit since the user sends from their own infrastructure (but the BCC capture still pages them in for tracking).

### Stage 5 — Track + learn

Sent pitches become rows in the existing `pitches` table from §12.1. The `campaign_id` field on `pitches` ties them back to the campaign. From this point, all Part 1 mechanics work unchanged:

- Reply tracking via thread matching (§12.2).
- Reply classification (`prompts/reply-classification-v1.md` or its cost-engineered v1.1).
- Coverage attribution (`prompts/pitch-attribution-v1.md` or its cost-engineered v1.1).
- Pitch analytics rolled up (§12.4).

The campaign closes (manually or automatically based on send-window end + a 30-day reply window). At close, the system generates **campaign insights** via `prompts/campaign-insights-v1.md`:

- Reply rate vs. workspace baseline.
- Coverage hit rate vs. workspace baseline.
- Top-performing pitches by subject pattern, length, opening style.
- Per-journalist outcomes ranked by responsiveness.
- "What we learned about this client's news" — patterns specific to this campaign that might inform the next.

These insights are exposed to the user as **readable analysis**, not just charts. They feed back into:

- **Per-journalist scoring updates.** Sarah Perez replied to 3 of 4 pitches → strong positive signal next time.
- **Per-workspace pattern learning.** Pitches under 200 words got 23% reply rate; over 300, 8% → surfaces as a recommendation in the next pitch review.
- **Cross-customer aggregate patterns.** Some signals hold across all customers; feed the global ranking model.

The learning is **transparent**: users see the patterns the system identified. Nothing happens silently in the background.

## Data model

### `campaigns` table

```sql
CREATE TABLE campaigns (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft' CHECK (status IN (
      'draft','strategy_review','targeting','drafting','reviewing','sending','live','paused','closed','archived'
    )),
    -- Brief (Stage 1 input)
    brief_text      TEXT,
    brief_structured JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Strategy (Stage 1 output)
    strategy_text   TEXT,
    strategy_structured JSONB NOT NULL DEFAULT '{}'::jsonb,
    strategy_prompt_version TEXT,
    strategy_generated_at TIMESTAMPTZ,
    strategy_edited_at TIMESTAMPTZ,
    -- Timing
    send_window_start TIMESTAMPTZ,
    send_window_end   TIMESTAMPTZ,
    embargo_at        TIMESTAMPTZ,
    -- Outcomes (denormalized; updated when campaign closes)
    final_targets_count INT,
    final_pitches_sent  INT,
    final_replies_count INT,
    final_coverage_count INT,
    closed_at       TIMESTAMPTZ,
    insights_text   TEXT,
    insights_generated_at TIMESTAMPTZ,
    -- Metadata
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_campaigns_workspace_status ON campaigns(workspace_id, status);
CREATE INDEX idx_campaigns_client_recent ON campaigns(client_id, created_at DESC);
```

### `campaign_targets` table

```sql
CREATE TABLE campaign_targets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id     UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    author_id       UUID NOT NULL REFERENCES authors(id) ON DELETE CASCADE,
    -- Ranking output
    rank            INT,
    score           NUMERIC(5,2),
    confidence      TEXT CHECK (confidence IN ('high','medium','low','exploratory','user_added')),
    why_they_matter TEXT,
    score_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    ranking_prompt_version TEXT,
    ranking_model_tier TEXT CHECK (ranking_model_tier IN ('haiku','sonnet')),
    -- Status
    status          TEXT NOT NULL DEFAULT 'ranked' CHECK (status IN (
      'ranked','accepted','removed','manually_added'
    )),
    removed_reason  TEXT,
    -- Snapshot at ranking time (so re-ranking later doesn't change historical context)
    journalist_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (campaign_id, author_id)
);

CREATE INDEX idx_campaign_targets_campaign_rank ON campaign_targets(campaign_id, rank);
CREATE INDEX idx_campaign_targets_status ON campaign_targets(campaign_id, status);
```

The `ranking_model_tier` column is new in this version of the spec — added to support the cost engineering audit trail. When debugging unusual scores, knowing whether Haiku or Sonnet produced them is necessary.

### `campaign_pitches` table

```sql
CREATE TABLE campaign_pitches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id     UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    campaign_target_id UUID NOT NULL UNIQUE REFERENCES campaign_targets(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Generated content
    subject         TEXT,
    body            TEXT,
    alternate_subjects TEXT[] NOT NULL DEFAULT '{}',
    -- Metadata about the generation
    confidence      TEXT CHECK (confidence IN ('high','medium','low')),
    why_this_pitch  TEXT,
    suggested_followup_at TIMESTAMPTZ,
    drafting_prompt_version TEXT,
    drafting_model_tier TEXT CHECK (drafting_model_tier IN ('opus','sonnet','sonnet_short')),
    drafted_at      TIMESTAMPTZ,
    -- Edit tracking (mirrors is_user_edited from coverage extraction)
    is_user_edited  BOOLEAN NOT NULL DEFAULT false,
    edited_fields   TEXT[] NOT NULL DEFAULT '{}',
    last_edited_at  TIMESTAMPTZ,
    last_edited_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Workflow status
    draft_status    TEXT NOT NULL DEFAULT 'drafting' CHECK (draft_status IN (
      'drafting','drafted','edited','approved','sent','skipped','failed'
    )),
    skipped_reason  TEXT,
    -- Send mechanics
    send_method     TEXT CHECK (send_method IN ('beat_sent','mailto')),
    sent_at         TIMESTAMPTZ,
    sent_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Linkage to canonical pitch row once sent
    pitch_id        UUID UNIQUE REFERENCES pitches(id) ON DELETE SET NULL,
    -- Audit trail
    regeneration_count INT NOT NULL DEFAULT 0,
    regeneration_history JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaign_pitches_campaign_status ON campaign_pitches(campaign_id, draft_status);
CREATE INDEX idx_campaign_pitches_pitch ON campaign_pitches(pitch_id) WHERE pitch_id IS NOT NULL;
```

### Augmentations to existing Phase 3 tables

`pitches` from §12.1 already has a `campaign_id` column. The campaign workflow uses it to link sent pitches back to their originating campaign. `pitches.source` accepts the value `'campaign'` for pitches sent through this workflow.

### Why some fields exist

- **`journalist_snapshot` on targets.** The journalist database changes constantly — new bylines, beat shifts, follower-count drift. A campaign sent in March should show why the AI ranked Sarah Perez highly *as of March*, not as of whenever the page is reloaded. Snapshotting protects historical truthfulness.
- **`regeneration_history` on pitches.** Every regeneration captures what the user steered toward and what changed. This becomes training signal: across thousands of regenerations we'll see which steering notes correlate with sent-vs-skipped outcomes.
- **`is_user_edited` + `edited_fields`** mirror the same pattern from coverage extraction. The discipline: re-runs respect user edits.
- **`ranking_model_tier` and `drafting_model_tier`** capture which cost-engineering tier produced each output, for auditing and for future eval comparisons.

### What's not in the data model

- **No `confidence_threshold_for_auto_send` column.** Auto-send doesn't exist; the column would be misleading.
- **No campaign templates table.** Stock templates are markdown files in `templates/` and selected at campaign-creation time. User-saved templates ("our standard product launch") are a Phase 4 enhancement.
- **No multi-stage approval on campaigns.** Within a workspace, anyone with `member` role can send. The Phase 2 portal client-approval pattern doesn't apply here — pitches go to journalists, not to clients.

## API surface

### Campaign CRUD

- `POST /v1/campaigns` — body: `{ client_id, name, brief_text?, brief_structured?, template_id? }`. Creates draft.
- `GET /v1/campaigns` — list, filterable by client, status.
- `GET /v1/campaigns/:id` — full detail with strategy + targets + pitches.
- `PATCH /v1/campaigns/:id` — update name, brief, send window.
- `DELETE /v1/campaigns/:id` — soft delete (cascades to targets and unsent pitches; sent pitches remain in `pitches` table without campaign linkage).

### Stage transitions

Each stage has an explicit transition endpoint that runs the AI work:

- `POST /v1/campaigns/:id/generate-strategy` — runs `campaign-strategy-v1`. Async. Updates `campaigns.strategy_text` + `strategy_structured`.
- `POST /v1/campaigns/:id/generate-targets` — runs `journalist-ranking-v1` against the SQL-pre-filtered candidate pool. Async via Batches API. Returns targets in batches.
- `POST /v1/campaigns/:id/generate-pitches` — runs `pitch-draft-v1` for each accepted target, routed by confidence tier. Async via Batches API.
- `POST /v1/campaigns/:id/close` — moves campaign to `closed`. Triggers `campaign-insights-v1`.
- `POST /v1/campaigns/:id/pause` and `POST /v1/campaigns/:id/resume` — manual pause/resume of sending.

### Strategy editing

- `PATCH /v1/campaigns/:id/strategy` — body: `{ strategy_text?, strategy_structured? }`. If structured fields change, mark stale-flags on downstream targets and pitches; UI surfaces "your strategy changed; re-run ranking?" prompt.

### Target list operations

- `GET /v1/campaigns/:id/targets` — paginated list of ranked targets with profile snippets.
- `POST /v1/campaigns/:id/targets` — body: `{ author_id }`. Manually add a journalist (sets `status='manually_added'`, `confidence='user_added'`).
- `PATCH /v1/campaigns/:id/targets/:target_id` — accept, remove (with optional reason), reorder.
- `POST /v1/campaigns/:id/targets/regenerate` — re-rank from scratch using current strategy. Confirms with user; preserves user-added entries.

### Pitch operations

- `GET /v1/campaigns/:id/pitches` — list with status filter.
- `GET /v1/campaigns/:id/pitches/:pitch_id` — full pitch with journalist context for the review surface.
- `PATCH /v1/campaigns/:id/pitches/:pitch_id` — edit subject, body. Sets `is_user_edited=true` on touched fields.
- `POST /v1/campaigns/:id/pitches/:pitch_id/regenerate` — body: `{ steering_note? }`. Re-runs draft with optional steering. Always uses the original confidence tier's model.
- `POST /v1/campaigns/:id/pitches/:pitch_id/skip` — body: `{ reason? }`. Marks skipped.
- `POST /v1/campaigns/:id/pitches/:pitch_id/send` — body: `{ send_method }`. Validates method, then either:
  - `beat_sent`: enqueues for transactional send via Postmark. Subject to 30/hour rate limit.
  - `mailto`: returns a `mailto:` URL with subject and body URL-encoded; client opens user's email composer. The pitch is marked `sent` optimistically; the BCC capture from §12.1 reconciles with the actual sent email.

### Insights

- `GET /v1/campaigns/:id/insights` — generated retro analysis once `closed`.
- `POST /v1/campaigns/:id/insights/regenerate` — re-run insights with refreshed data.

### Rate limiting

- `POST /v1/campaigns/:id/pitches/:pitch_id/send` with `send_method=beat_sent`: 30/hour per workspace, with smart spacing.
- `POST /v1/campaigns/:id/generate-targets`: 5/hour per workspace.
- `POST /v1/campaigns/:id/pitches/:pitch_id/regenerate`: 60/hour per workspace, 10/hour per pitch.

## The five new prompts

All ship at v1.0 with cost engineering built in (no v1.0-then-v1.1 lineage; the cost-engineered design is the initial release). Each has its own file under `prompts/` with full prompt text, design notes, eval set, and migration plan. Briefly:

- **`prompts/campaign-strategy-v1.md`** (Opus). Brief → strategy document. Compressed context (top 5 coverage, aggregated pitch counts) plus prompt-cached client_context.
- **`prompts/journalist-ranking-v1.md`** (Haiku + Sonnet escalation). Per-candidate scoring. Two-tier with batch-mode submission.
- **`prompts/pitch-tone-analysis-v1.md`** (Sonnet). Recent articles → tone descriptor. Frequency-tiered cache TTL (monthly / quarterly / on-demand by article frequency).
- **`prompts/pitch-draft-v1.md`** (Opus or Sonnet by confidence tier). Strategy + journalist + recent work + tone → personalized pitch. Confidence-routed; campaign strategy is prompt-cached across the campaign.
- **`prompts/campaign-insights-v1.md`** (Sonnet). Closed campaign outcomes → retro. Pre-aggregated input.

See `docs/18-cost-engineering.md` for the system-wide cost discipline these prompts implement.

## Critical risks

The pitch tracker doc (§12) covers risks for the tracker. Additional risks specific to the campaign workflow:

### 1. Hallucinated journalist details

The single worst failure mode. If a pitch confidently says "I noticed your recent piece on quantum cryptography" but the journalist has never written about quantum cryptography, the pitch is worse than no pitch — it's evidence the agency doesn't know what they're talking about.

Mitigations baked into the prompts:

- The drafting prompt requires every claim about the journalist's work to reference an article URL it was given. The prompt is told to refuse to generate claims it can't ground.
- The eval set explicitly tests for "AI generates plausible-but-false claims about a journalist's coverage." Hard gate: zero hallucinations on the fact-grounding eval.
- Journalist snapshots include actual article titles + summaries, not just metadata; the prompt has the source material in front of it.
- The journalist-context column on the pitch review surface displays the same articles that fed the prompt — visual cross-check by the user.

### 2. Ranking model cold start

Without months of data, per-workspace ranking signals are weak. Aggregate cross-customer signals fill the gap, but with selection bias (we only see what *our* customers pitch and track).

Mitigations:

- The ranking output explicitly surfaces "low confidence" labels and explains why.
- The first 10 campaigns in a new workspace surface a banner: "Your personalized signal is just getting started — expect ranking quality to improve over time."
- Manual override is prominently available; the system never forces the user to send to its top picks.

### 3. Pitch personalization that crosses into creepy

"I noticed you went to Sarah Lawrence and your dog's name is Pepper" is the wrong pitch. Personalization must be grounded in *professional* signals — recent bylines, beat coverage, stated topical interests — not personal life.

Mitigations:

- The drafting prompt explicitly prohibits non-professional personalization.
- Eval set includes adversarial cases: "If the journalist's bio mentions a dog's name, the generated pitch must NOT mention it."
- The journalist snapshot fed to the prompt is filtered to professional fields only; personal-tone fields aren't passed through.

### 4. Privacy — opted-out journalists

§12.5's opt-out flow removes journalists from search and pitching. The campaign workflow must hard-filter opt-outs from ranking *before* the LLM ever sees them.

Implementation: the candidate pool query for ranking includes `WHERE deleted_at IS NULL AND opt_out_at IS NULL`. The ranking worker double-checks each candidate for opt-out at request time — defense in depth.

### 5. Pitch volume escalation

The friction reduction is real — agencies could go from 20 pitches per campaign to 200 because it's cheaper now. That tilts the ecosystem toward spam.

Mitigations:

- Confidence labels are prominent; users see when the system is uncertain.
- Per-campaign volume warnings: "You're sending 150 pitches in this campaign. Average reply rate at this volume is N%, vs. M% for campaigns under 50 pitches. Consider tightening your target list."
- Send rate limiting: 30/hour even Beat-sent. Forces pacing across hours/days.
- Skipped-pitch tracking: if a workspace skips >40% of generated pitches, surface it as feedback ("you're frequently skipping low-confidence pitches; consider raising the score threshold to save AI cost").

### 6. Sender reputation (Beat-sent mode)

Beat-sent emails go from `agency-name@send.beat.app` (or workspace-configured custom domain). One workspace's bad sending behavior could damage deliverability for all workspaces.

Mitigations:

- Per-workspace sub-domains where possible (`hayworth-pr.send.beat.app`).
- Aggressive bounce/complaint handling: 5 hard bounces in a week pauses sending and notifies the workspace owner.
- Sender warm-up for new workspaces — first 50 sends throttled to 5/day, ramping up over 14 days.
- Workspace owners can configure custom domain (Phase 4 enterprise).

### 7. Mailto: tracking gaps

When a user sends via mailto:, we mark the pitch optimistically as `sent` but actual delivery is the user's mail client. If the user closes the compose window without sending, the pitch state in Beat is wrong.

Mitigations:

- The mailto: response includes "If you didn't actually send this, click here to revert."
- The BCC capture reconciles with reality — if no captured email arrives within 24 hours, surface "did this actually send?" prompt.
- A future browser extension hook that knows when the email composer was actually used to send. Phase 4+.

## Build sequence (10 weeks)

This sequence assumes Part 1 (pitch tracker, attribution, journalist DB, analytics) is built and stable.

| Week | Focus |
|---|---|
| 1 | Data model migrations (`campaigns`, `campaign_targets`, `campaign_pitches`); base CRUD APIs; stock templates as markdown files |
| 2 | Brief intake UI (free-form + structured form); strategy generation endpoint + `campaign-strategy-v1` prompt + initial eval set (10 examples) |
| 3 | Strategy review UI: editable structured fields, prose section, missing-information surfacing; strategy → downstream invalidation logic |
| 4 | Journalist ranking endpoint + `journalist-ranking-v1` two-tier prompt + `pitch-tone-analysis-v1` cache layer; eval set (15 examples covering well-fit, edge-case, adversarial); batch-mode integration |
| 5 | Target list UI: ranked list with confidence labels, why-they-matter, journalist profile drawer, accept/remove/reorder, manual add; re-rank confirmation modal |
| 6 | Pitch drafting endpoint + `pitch-draft-v1` confidence-routed prompt + eval; initial review surface (one-pitch-at-a-time) |
| 7 | Send mechanics: Beat-sent infrastructure (Postmark integration, sender domain setup, rate limiting), mailto: flow with BCC capture reconciliation |
| 8 | Pitch review polish: regenerate-with-steering, edit tracking, skip-with-reason, send-method selector; campaign overview surface |
| 9 | Campaign close + `campaign-insights-v1` prompt + insights UI; outcomes feedback into ranking model |
| 10 | Polish, eval expansion, dogfood end-to-end on 3 real campaigns. Fix everything that breaks |

If anything slips, weeks 9-10 are cut points. Insights generation is bolt-on; can ship after launch with manual analytics in the meantime.

## Eval set additions

`docs/06-evals.md` gets new tiers for the campaign workflow. Each prompt's eval set is documented in its own file under `prompts/` (see "The five new prompts" above). Summary:

- **Strategy generation** — 15 hand-written briefs across announcement types (funding, product, exec hire, partnership, regulatory, thought-leadership, layoffs, thin briefs).
- **Journalist ranking** — 30 hand-labeled (strategy, journalist) pairs spanning strong/medium/weak fits; both Haiku-tier and Sonnet-escalation paths.
- **Pitch drafting** — 25 (campaign, journalist, expected pitch) examples covering all three confidence tiers plus adversarial cases (personal-life bait, fabricated-quote bait, embargo handling).
- **Tone analysis** — 20 journalists with hand-written acceptable tone descriptors.
- **Insights generation** — 10 closed-campaign outcome datasets including underperforming campaigns and small-sample cases.

## Acceptance criteria

- An agency can take a real client brief, run it through brief → strategy → targets → pitches → review → send, and have the first pitch leave Beat within 30 minutes total.
- A campaign with 50 targets generates 50 pitches with no hallucinated journalist details (verified against eval set).
- The pitch review surface allows reviewing 10 pitches in < 10 minutes when the user is editing lightly.
- mailto: send mode opens the user's email client with subject and body correctly populated; BCC capture reconciles within 24 hours.
- Beat-sent mode delivers pitches via Postmark with > 95% delivery rate (measured by absence of hard bounce).
- Skipping or removing a journalist on one campaign feeds back into ranking on the next campaign for the same workspace.
- Closed campaigns produce insights that match a manual review of the campaign's outcomes.
- Zero pitches in a calendar quarter were sent without an explicit human "Send" click.
- Combined cost across all five campaign-workflow prompts at typical agency volumes ≤ $25/month per workspace per `docs/18-cost-engineering.md`.

## What's NOT in this phase

- **Auto-send.** Always human-in-the-loop.
- **Approve-and-send-all batch operations.** Each pitch needs an individual click.
- **User-saved campaign templates.** Stock templates only in v1; saved-from-prior-campaign comes in Phase 4+.
- **Mass A/B testing of subject lines automatically.** Surface what performed but don't auto-iterate.
- **Multimedia generation in pitches** (video, decks). Text only.
- **Multi-language campaigns.** English only.
- **Influencer / creator outreach.** Different shape, different ethics.
- **Deep CRM integrations** (Salesforce, HubSpot). Phase 4.
- **Predictive coverage probability before send.** Confidence labels (high/medium/low) only — we don't have data to calibrate honest probabilities.
- **Real-time collaborative campaign editing.** One person at a time per campaign.
- **Native publishing of pitches as paid promotion.** No.

## Cross-references

- `docs/12-phase-3-pitch-tracker.md` — Part 1; campaigns build on top of the pitch CRM, reply tracking, attribution, and journalist DB
- `docs/14-multi-tenancy.md` — pre-flight checklist for the three new tenant tables (`campaigns`, `campaign_targets`, `campaign_pitches`)
- `docs/15-additions.md` — `client_context.style_notes` is consumed by the pitch drafting prompt
- `docs/16-client-dashboard.md` — campaign-related alerts surface in the existing dashboard
- `docs/18-cost-engineering.md` — the system-wide cost discipline that the five new prompts implement
