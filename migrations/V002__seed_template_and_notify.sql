-- Beat — V002: seed default system template + NOTIFY trigger on extraction_jobs.
-- Forward-only.

-- Seed one system-owned report template so reports.template_id (NOT NULL,
-- ON DELETE RESTRICT) can be satisfied. The structure here is the v1 default
-- referenced by docs/07-wireframes.md; templating proper lands later.
INSERT INTO report_templates (id, workspace_id, name, structure, is_system)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    NULL,
    'Standard',
    '{
      "sections": [
        {"type": "cover"},
        {"type": "executive_summary"},
        {"type": "at_a_glance"},
        {"type": "highlights"},
        {"type": "coverage_list"}
      ]
    }'::jsonb,
    true
);

-- LISTEN/NOTIFY: the extraction worker subscribes to this channel and is
-- woken up immediately when a new extraction_job is enqueued.
CREATE OR REPLACE FUNCTION notify_extraction_job() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('extraction_jobs', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_extraction_jobs_notify
AFTER INSERT ON extraction_jobs
FOR EACH ROW EXECUTE FUNCTION notify_extraction_job();
