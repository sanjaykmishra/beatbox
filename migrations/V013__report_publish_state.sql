-- V013: Report publish state. Adds 'published' to the lifecycle and tracks who published when.
--
-- Lifecycle (per CLAUDE.md guardrail #5):
--   draft → processing → ready → published
--                          │
--                          └─ failed (recoverable: → processing → ready)
--
-- 'published' is terminal: every mutation endpoint refuses it, the row cannot be soft-deleted,
-- and the public share endpoint requires this state. Approval gating (single- vs multi-person
-- workspace) is enforced in app code, not here.
--
-- Existing 'ready' rows are correctly already in "ready to publish" state — no backfill needed.
-- Existing 'draft' / 'failed' rows are also unchanged.

ALTER TABLE reports DROP CONSTRAINT IF EXISTS reports_status_check;

ALTER TABLE reports
    ADD CONSTRAINT reports_status_check
    CHECK (status IN ('draft','processing','ready','failed','published'));

ALTER TABLE reports
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Quick lookup of "what's been published recently" — used by the upcoming list view that
-- intermixes drafts and published reports.
CREATE INDEX idx_reports_workspace_published
    ON reports(workspace_id, published_at DESC)
    WHERE status = 'published';
