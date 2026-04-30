-- Beat — V005: render_jobs queue.
-- Same shape as extraction_jobs: PG LISTEN/NOTIFY substrate, polled by the render worker.
-- Forward-only.

CREATE TABLE render_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       UUID NOT NULL UNIQUE REFERENCES reports(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed')),
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_render_jobs_queued ON render_jobs(created_at) WHERE status = 'queued';
CREATE INDEX idx_render_jobs_running ON render_jobs(started_at) WHERE status = 'running';

CREATE TRIGGER trg_render_jobs_updated_at BEFORE UPDATE ON render_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION notify_render_job() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('render_jobs', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_render_jobs_notify
AFTER INSERT ON render_jobs
FOR EACH ROW EXECUTE FUNCTION notify_render_job();
