-- V008 — Phase 1.5+ general calendar.  Spec: see proposal in development chat (Option C).
--
-- Standalone calendar events for things that don't have another home: embargoes, launches,
-- earnings calls, meetings, blackouts, milestones, generic 'other' rows. Posts / reports /
-- coverage items keep their own source-of-truth tables; the calendar feed aggregator
-- (GET /v1/calendar/feed) unions over all sources at read time.

CREATE TABLE calendar_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- NULL = workspace-wide (e.g. agency PTO blackout, internal milestone).
    client_id           UUID REFERENCES clients(id) ON DELETE CASCADE,
    event_type          TEXT NOT NULL CHECK (event_type IN (
                            'embargo','launch','earnings','meeting','blackout','milestone','other')),
    title               TEXT NOT NULL,
    description         TEXT,
    occurs_at           TIMESTAMPTZ NOT NULL,
    -- For ranged events (blackouts, multi-day campaigns). NULL = point-in-time.
    ends_at             TIMESTAMPTZ,
    all_day             BOOLEAN NOT NULL DEFAULT false,
    url                 TEXT,
    color               TEXT CHECK (color IS NULL OR color ~ '^[0-9A-Fa-f]{6}$'),
    created_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CHECK (ends_at IS NULL OR ends_at >= occurs_at)
);

CREATE INDEX idx_calendar_events_workspace_when
    ON calendar_events(workspace_id, occurs_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_calendar_events_client_when
    ON calendar_events(client_id, occurs_at)
    WHERE deleted_at IS NULL AND client_id IS NOT NULL;
