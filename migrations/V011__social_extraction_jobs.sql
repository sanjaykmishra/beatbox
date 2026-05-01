-- V011 — social mention extraction queue.
--
-- Per docs/17-phase-1-5-social.md §17.1: social URLs pasted into a report enqueue here. The
-- SocialExtractionWorker drains the queue, fetches the post via the per-platform fetcher,
-- runs the social-extraction-v1 LLM prompt, and persists the result onto the social_mentions
-- row whose id is referenced by social_mention_id below.
--
-- Mirrors extraction_jobs in shape: status state machine, attempt counter, started/completed
-- timestamps, per-job last_error string. We do NOT cache by content hash here because the
-- caching boundary for social posts is per-author, per-platform, and is handled at the
-- social_mentions row level rather than as a cross-customer dedup table.

CREATE TABLE social_extraction_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    social_mention_id UUID NOT NULL REFERENCES social_mentions(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued','running','done','failed')),
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    UNIQUE (social_mention_id)
);

CREATE INDEX idx_social_extraction_jobs_queued
  ON social_extraction_jobs(queued_at)
  WHERE status = 'queued';
