-- Beat — V004: client context ("second brain") + activity_events.
-- Source: docs/15-additions.md §15.1 + §15.2.
-- Forward-only.

-- ==================== CLIENT CONTEXT (§15.1) ====================

CREATE TABLE client_context (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL UNIQUE REFERENCES clients(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Structured fields used in prompts and surfaced in UI
    key_messages    TEXT,
    do_not_pitch    TEXT,
    competitive_set TEXT,
    important_dates TEXT,
    style_notes     TEXT,
    -- Free-form notes
    notes_markdown  TEXT,
    -- Versioning
    version         INT NOT NULL DEFAULT 1,
    last_edited_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_client_context_workspace ON client_context(workspace_id);

CREATE TRIGGER trg_client_context_updated_at BEFORE UPDATE ON client_context
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ==================== ACTIVITY EVENTS (§15.2) ====================
-- Distinct from audit_events: powers product analytics, not compliance.
-- See docs/15-additions.md for the comparison table.

CREATE TABLE activity_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_type      TEXT NOT NULL CHECK (actor_type IN ('user','system','worker','api','portal_user')),
    kind            TEXT NOT NULL,
    target_type     TEXT,
    target_id       UUID,
    duration_ms     INT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_workspace_time ON activity_events(workspace_id, occurred_at DESC);
CREATE INDEX idx_activity_kind_time ON activity_events(kind, occurred_at DESC);
CREATE INDEX idx_activity_target ON activity_events(target_type, target_id);
