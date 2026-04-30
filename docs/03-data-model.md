# 03 — Data model

Postgres 16. UUID primary keys via `gen_random_uuid()`. `timestamptz` everywhere — never `timestamp`. Soft-delete on user-facing entities via `deleted_at`. JSONB for evolving shapes.

The DDL below is canonical. Translate to Flyway migration files (`V001__init.sql`, etc.) in order. Don't add columns by editing existing migrations — always write a new one.

## Schema

```sql
-- Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- gen_random_uuid
CREATE EXTENSION IF NOT EXISTS citext;        -- case-insensitive emails
CREATE EXTENSION IF NOT EXISTS pg_trgm;       -- fuzzy outlet/author search

-- ==================== USERS & WORKSPACES ====================

CREATE TABLE workspaces (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    slug            TEXT NOT NULL UNIQUE,
    logo_url        TEXT,
    primary_color   TEXT,                      -- hex without #, e.g. "1F2937"
    plan            TEXT NOT NULL DEFAULT 'trial' CHECK (plan IN ('trial','solo','agency','enterprise')),
    plan_limit_clients         INT NOT NULL DEFAULT 5,
    plan_limit_reports_monthly INT NOT NULL DEFAULT 50,
    stripe_customer_id     TEXT,
    stripe_subscription_id TEXT,
    trial_ends_at   TIMESTAMPTZ,
    default_template_id    UUID,               -- FK added below after report_templates
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           CITEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    name            TEXT NOT NULL,
    email_verified_at TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE workspace_members (
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('owner','member','viewer')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX idx_workspace_members_user ON workspace_members(user_id);

CREATE TABLE sessions (
    token_hash      TEXT PRIMARY KEY,           -- SHA-256 of session token
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_agent      TEXT,
    ip              INET
);

CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);

-- ==================== CLIENTS ====================

CREATE TABLE clients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    logo_url        TEXT,                       -- overrides workspace logo on this client's reports
    primary_color   TEXT,                       -- overrides workspace primary
    notes           TEXT,
    default_cadence TEXT CHECK (default_cadence IN ('weekly','biweekly','monthly','quarterly')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_clients_workspace ON clients(workspace_id) WHERE deleted_at IS NULL;

-- ==================== OUTLETS & AUTHORS (global) ====================

CREATE TABLE outlets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain          TEXT NOT NULL UNIQUE,        -- lowercased apex domain, e.g. "techcrunch.com"
    name            TEXT NOT NULL,
    tier            INT NOT NULL DEFAULT 3 CHECK (tier IN (1,2,3)),
    tier_source     TEXT NOT NULL DEFAULT 'default' CHECK (tier_source IN ('curated','llm','default','manual')),
    domain_authority INT,                         -- 0–100, refreshed monthly
    domain_authority_updated_at TIMESTAMPTZ,
    estimated_monthly_visits BIGINT,
    country         TEXT,                         -- ISO 3166-1 alpha-2
    language        TEXT NOT NULL DEFAULT 'en',   -- ISO 639-1
    last_verified_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outlets_name_trgm ON outlets USING gin (name gin_trgm_ops);

CREATE TABLE authors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    primary_outlet_id UUID REFERENCES outlets(id) ON DELETE SET NULL,
    email           CITEXT,
    social_handles  JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {twitter, linkedin, bluesky, substack}
    beat_tags       TEXT[] NOT NULL DEFAULT '{}',
    last_seen_byline_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (name, primary_outlet_id)              -- soft uniqueness; nulls in PK count as distinct
);

CREATE INDEX idx_authors_name_trgm ON authors USING gin (name gin_trgm_ops);
CREATE INDEX idx_authors_outlet ON authors(primary_outlet_id);

-- ==================== REPORT TEMPLATES ====================

CREATE TABLE report_templates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,  -- NULL = system template
    name            TEXT NOT NULL,
    structure       JSONB NOT NULL,               -- ordered list of sections + config
    preview_image_url TEXT,
    is_system       BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_templates_workspace ON report_templates(workspace_id);

ALTER TABLE workspaces
  ADD CONSTRAINT fk_workspace_default_template
  FOREIGN KEY (default_template_id) REFERENCES report_templates(id) ON DELETE SET NULL;

-- ==================== REPORTS ====================

CREATE TABLE reports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,  -- denormalized for queries
    template_id     UUID NOT NULL REFERENCES report_templates(id) ON DELETE RESTRICT,
    title           TEXT NOT NULL,
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','processing','ready','failed')),
    executive_summary TEXT,                       -- LLM-generated, user-editable
    executive_summary_edited BOOLEAN NOT NULL DEFAULT false,
    pdf_url         TEXT,
    share_token     TEXT UNIQUE,
    share_token_expires_at TIMESTAMPTZ,
    generated_at    TIMESTAMPTZ,
    failure_reason  TEXT,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CHECK (period_end >= period_start)
);

CREATE INDEX idx_reports_client_period ON reports(client_id, period_end DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_reports_workspace_status ON reports(workspace_id, status);
CREATE INDEX idx_reports_share_token ON reports(share_token) WHERE share_token IS NOT NULL;

-- ==================== COVERAGE ITEMS ====================

CREATE TABLE coverage_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    source_url      TEXT NOT NULL,
    outlet_id       UUID REFERENCES outlets(id) ON DELETE SET NULL,
    author_id       UUID REFERENCES authors(id) ON DELETE SET NULL,
    headline        TEXT,
    subheadline     TEXT,
    publish_date    DATE,
    lede            TEXT,
    summary         TEXT,                         -- 2-sentence LLM summary
    key_quote       TEXT,
    sentiment       TEXT CHECK (sentiment IN ('positive','neutral','negative','mixed')),
    sentiment_rationale TEXT,
    subject_prominence TEXT CHECK (subject_prominence IN ('feature','mention','passing')),
    topics          TEXT[] NOT NULL DEFAULT '{}',
    estimated_reach BIGINT,                       -- snapshot from outlet at time of extraction
    tier_at_extraction INT,                       -- snapshot from outlet at time of extraction
    screenshot_url  TEXT,
    extraction_status TEXT NOT NULL DEFAULT 'queued' CHECK (extraction_status IN ('queued','running','done','failed')),
    extraction_error TEXT,
    extraction_prompt_version TEXT,               -- e.g. "extraction_v1.0"
    raw_extracted   JSONB,                         -- full LLM JSON for debugging / re-runs
    is_user_edited  BOOLEAN NOT NULL DEFAULT false, -- if true, re-extraction skips this item
    edited_fields   TEXT[] NOT NULL DEFAULT '{}', -- fields the user has touched; never overwritten
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_coverage_report_date ON coverage_items(report_id, publish_date DESC);
CREATE INDEX idx_coverage_outlet ON coverage_items(outlet_id);
CREATE INDEX idx_coverage_author ON coverage_items(author_id);
CREATE UNIQUE INDEX idx_coverage_report_url ON coverage_items(report_id, source_url);

-- ==================== EXTRACTION JOBS ====================

CREATE TABLE extraction_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coverage_item_id UUID NOT NULL UNIQUE REFERENCES coverage_items(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed')),
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_extraction_jobs_queued ON extraction_jobs(created_at) WHERE status = 'queued';
CREATE INDEX idx_extraction_jobs_running ON extraction_jobs(started_at) WHERE status = 'running';

-- ==================== AUDIT EVENTS ====================

CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    action          TEXT NOT NULL,                 -- e.g. "report.generated", "client.created"
    target_type     TEXT,                          -- e.g. "report"
    target_id       UUID,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip              INET,
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_workspace_time ON audit_events(workspace_id, created_at DESC);

-- ==================== TRIGGERS: updated_at ====================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply to every table with updated_at
DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN SELECT table_name FROM information_schema.columns
             WHERE column_name = 'updated_at' AND table_schema = 'public'
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %I
             FOR EACH ROW EXECUTE FUNCTION set_updated_at()', t, t);
    END LOOP;
END $$;
```

## Notes

### Why `workspace_id` is denormalized on `reports`

It saves a join on every report query and lets us add a workspace-scoped index. Maintained by app code on insert. Trade-off accepted.

### Why `extraction_jobs` is its own table

Could live as fields on `coverage_items`, but separating gives us:
- A clean queue surface for `LISTEN/NOTIFY`.
- Job retries don't bloat the coverage row.
- We can extend to other job types later (re-extraction, screenshot generation).

### Why `is_user_edited` and `edited_fields`

Critical guardrail: when a user fixes the LLM's output, we must never overwrite that fix on a re-run. Worker code reads `edited_fields` and merges only into untouched fields.

### Why no separate `coverage_outlet_snapshot` table

We snapshot tier and reach onto `coverage_items` at extraction time (`tier_at_extraction`, `estimated_reach`). Outlet metadata may drift, but a generated report should reflect the snapshot at the time of generation, not a moving average. Reports are historical documents.

### Tenancy

Every workspace-scoped query MUST include `WHERE workspace_id = :current_workspace`. This is enforced in app code via a base repository class — never hand-write SQL that touches tenant tables without it. Phase 4 (enterprise) will add Postgres Row-Level Security for defense in depth.

### What's missing from v1

These tables are coming in later phases — listed so we don't accidentally collide on naming:

- `pitches`, `pitch_recipients`, `pitch_outcomes` — Phase 3 pitch tracker.
- `monitoring_searches`, `monitored_mentions` — Phase 4 coverage monitoring.
- `journalist_contacts` — Phase 4 enriched media database.

## Seed data

System-owned report templates are seeded in `V002__seed_templates.sql`. Tier-1/2/3 outlet curation is seeded in `V003__seed_outlets.sql` with ~500 entries (source list to be assembled — see CLAUDE.md open decisions).
