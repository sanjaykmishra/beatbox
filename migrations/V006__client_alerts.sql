-- Beat — V006: client dashboard alert engine.
-- Source: docs/16-client-dashboard.md.
-- Forward-only.

-- Used by client.setup_incomplete dismissal so the new-client checklist doesn't
-- come back after the user clicks "I'll do it later".
ALTER TABLE clients ADD COLUMN setup_dismissed_at TIMESTAMPTZ;

CREATE TABLE client_alerts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    alert_type      TEXT NOT NULL,
    severity        TEXT NOT NULL CHECK (severity IN ('red','amber','blue','green')),
    count           INT NOT NULL DEFAULT 1,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Pre-rendered for the UI so the frontend doesn't re-derive copy.
    badge_label     TEXT NOT NULL,
    card_title      TEXT NOT NULL,
    card_subtitle   TEXT,
    card_action_label TEXT,
    card_action_path  TEXT,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (client_id, alert_type)
);

CREATE INDEX idx_client_alerts_workspace ON client_alerts(workspace_id);
CREATE INDEX idx_client_alerts_client ON client_alerts(client_id);
