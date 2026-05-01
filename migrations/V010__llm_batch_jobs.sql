-- V010 — Anthropic Message Batches tracking table.
--
-- Per docs/18-cost-engineering.md: ranking and pitch-draft are user-async ("press generate, watch
-- the spinner") and qualify for the 50% Batches API discount. Once those features ship in Phase 3
-- Part 2 they enqueue batches via AnthropicBatchClient; BatchPoller drains completed batches and
-- writes results back to the originating row.
--
-- The table is workspace-scoped: every batch belongs to the workspace whose user kicked off the
-- async operation, satisfying docs/14-multi-tenancy.md §Schema.

CREATE TABLE llm_batch_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Consumer feature label so the poller knows where to write results back. Free-form string,
    -- e.g. 'journalist_ranking', 'pitch_draft'. NOT a foreign key — the poller dispatches by name.
    feature         TEXT NOT NULL,
    -- Logical "what does this batch process" identifier (e.g. campaign UUID for ranking).
    -- Opaque to BatchPoller; meaningful only to the feature handler.
    target_id       UUID,
    -- Anthropic-side batch ID, set after submission completes.
    anthropic_batch_id TEXT,
    status          TEXT NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued','in_progress','ended','failed','cancelled')),
    -- Lifecycle counters mirrored from Anthropic for the dashboard.
    request_count   INT NOT NULL DEFAULT 0,
    succeeded_count INT NOT NULL DEFAULT 0,
    errored_count   INT NOT NULL DEFAULT 0,
    -- Non-PII metadata the feature handler stuffs in (e.g. campaign params); the request payload
    -- itself is uploaded directly to Anthropic and not persisted here.
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error      TEXT,
    submitted_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_llm_batch_jobs_workspace ON llm_batch_jobs(workspace_id);
CREATE INDEX idx_llm_batch_jobs_status ON llm_batch_jobs(status) WHERE status IN ('queued','in_progress');
CREATE INDEX idx_llm_batch_jobs_anthropic_id ON llm_batch_jobs(anthropic_batch_id) WHERE anthropic_batch_id IS NOT NULL;
