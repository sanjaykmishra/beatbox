-- V007 — Phase 1.5: social mentions, owned posts, asset library, approval portal,
-- listening queries.  Spec source: docs/17-phase-1-5-social.md.
--
-- Notes
-- - All tables are tenant-scoped via workspace_id (per multi-tenancy convention).
-- - social_mentions.report_id is nullable so future Phase 2 coverage_inbox can pool
--   mentions before assigning to a report; in Phase 1.5 we always set report_id.
-- - client_portal_users / client_portal_sessions are pulled forward from Phase 2
--   §11.7 because §17.4 approval workflow requires the portal trust domain.
--   These are the minimum portal tables needed; Phase 2 may extend.

-- =========================================================================
-- 17.1 — Social mentions as first-class coverage
-- =========================================================================

CREATE TABLE social_authors (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform            TEXT NOT NULL CHECK (platform IN (
        'x','linkedin','bluesky','threads','instagram','facebook',
        'tiktok','reddit','substack','youtube','mastodon')),
    handle              TEXT NOT NULL,
    display_name        TEXT,
    bio                 TEXT,
    follower_count      BIGINT,
    profile_url         TEXT,
    avatar_url          TEXT,
    is_verified         BOOLEAN NOT NULL DEFAULT false,
    -- Cross-platform linking when the same person is on multiple platforms.
    -- Phase 1.5 only allows manual merge from the journalist profile UI;
    -- auto-linking is a Phase 3 enhancement.
    linked_author_id    UUID REFERENCES authors(id) ON DELETE SET NULL,
    topic_tags          TEXT[] NOT NULL DEFAULT '{}',
    last_seen_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, handle)
);

CREATE INDEX idx_social_authors_handle_trgm
    ON social_authors USING gin (handle gin_trgm_ops);
CREATE INDEX idx_social_authors_linked
    ON social_authors(linked_author_id);

CREATE TABLE social_mentions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable: future Phase 2 inbox pools mentions before report assignment.
    report_id                   UUID REFERENCES reports(id) ON DELETE CASCADE,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- Source
    platform                    TEXT NOT NULL CHECK (platform IN (
        'x','linkedin','bluesky','threads','instagram','facebook',
        'tiktok','reddit','substack','youtube','mastodon')),
    source_url                  TEXT NOT NULL,
    external_post_id            TEXT,
    author_id                   UUID REFERENCES social_authors(id) ON DELETE SET NULL,
    -- Content
    posted_at                   TIMESTAMPTZ,
    content_text                TEXT,
    content_lang                TEXT,
    has_media                   BOOLEAN NOT NULL DEFAULT false,
    media_summary               TEXT,
    media_urls                  TEXT[] NOT NULL DEFAULT '{}',
    -- Thread context
    is_reply                    BOOLEAN NOT NULL DEFAULT false,
    is_quote                    BOOLEAN NOT NULL DEFAULT false,
    parent_post_url             TEXT,
    thread_root_url             TEXT,
    -- Engagement (snapshotted at extraction; document drift in UI tooltip)
    likes_count                 BIGINT,
    reposts_count               BIGINT,
    replies_count               BIGINT,
    views_count                 BIGINT,
    estimated_reach             BIGINT,
    -- Analysis
    summary                     TEXT,
    sentiment                   TEXT CHECK (sentiment IN ('positive','neutral','negative','mixed')),
    sentiment_rationale         TEXT,
    subject_prominence          TEXT CHECK (subject_prominence IN ('feature','mention','passing')),
    topics                      TEXT[] NOT NULL DEFAULT '{}',
    is_amplification            BOOLEAN,
    -- Author follower count at time of post (separate from social_authors.follower_count
    -- which evolves; this snapshot keeps historical reports correct).
    follower_count_at_post      BIGINT,
    -- Workflow
    extraction_status           TEXT NOT NULL DEFAULT 'queued'
        CHECK (extraction_status IN ('queued','running','done','failed')),
    extraction_error            TEXT,
    extraction_prompt_version   TEXT,
    raw_extracted               JSONB,
    is_user_edited              BOOLEAN NOT NULL DEFAULT false,
    edited_fields               TEXT[] NOT NULL DEFAULT '{}',
    sort_order                  INT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_social_mentions_report
    ON social_mentions(report_id, posted_at DESC);
CREATE INDEX idx_social_mentions_client_posted
    ON social_mentions(client_id, posted_at DESC) WHERE report_id IS NOT NULL;
CREATE INDEX idx_social_mentions_workspace
    ON social_mentions(workspace_id);
CREATE UNIQUE INDEX idx_social_mentions_report_url
    ON social_mentions(report_id, source_url) WHERE report_id IS NOT NULL;

-- =========================================================================
-- 17.2 — Editorial calendar (planning-only)
-- =========================================================================

CREATE TABLE owned_posts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- One row, multiple platform variants in JSONB to avoid 8 columns of nullable text.
    title                       TEXT,
    primary_content_text        TEXT,
    -- Shape: { "linkedin": { "content": "...", "char_count": 1234, "edited_at": "..." }, ... }
    platform_variants           JSONB NOT NULL DEFAULT '{}'::jsonb,
    target_platforms            TEXT[] NOT NULL DEFAULT '{}',
    scheduled_for               TIMESTAMPTZ,
    timezone                    TEXT NOT NULL DEFAULT 'America/Los_Angeles',
    status                      TEXT NOT NULL DEFAULT 'draft' CHECK (status IN (
        'draft','internal_review','client_review','approved',
        'scheduled','posted','archived','rejected')),
    series_tag                  TEXT,
    drafted_by_user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    submitted_for_review_at     TIMESTAMPTZ,
    approved_at                 TIMESTAMPTZ,
    -- Manual click ("I posted this") — system has no native publishing in 1.5.
    posted_at                   TIMESTAMPTZ,
    asset_ids                   UUID[] NOT NULL DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ
);

CREATE INDEX idx_owned_posts_client_scheduled
    ON owned_posts(client_id, scheduled_for) WHERE deleted_at IS NULL;
CREATE INDEX idx_owned_posts_workspace_status
    ON owned_posts(workspace_id, status);
CREATE INDEX idx_owned_posts_series
    ON owned_posts(workspace_id, series_tag) WHERE series_tag IS NOT NULL;

-- =========================================================================
-- 17.3 — Asset library
-- =========================================================================

CREATE TABLE client_assets (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    storage_key                 TEXT NOT NULL, -- assets/{workspace_id}/{client_id}/{uuid}
    original_filename           TEXT NOT NULL,
    mime_type                   TEXT NOT NULL,
    size_bytes                  BIGINT NOT NULL,
    width                       INT,
    height                      INT,
    title                       TEXT,
    description                 TEXT,
    tags                        TEXT[] NOT NULL DEFAULT '{}',
    folder_path                 TEXT,
    uploaded_by_user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    last_used_at                TIMESTAMPTZ,
    use_count                   INT NOT NULL DEFAULT 0,
    asset_type                  TEXT CHECK (asset_type IN (
        'logo','photo','video','document','brand_guide','color_swatch','other')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ
);

CREATE INDEX idx_assets_client
    ON client_assets(client_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_folder
    ON client_assets(client_id, folder_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_type
    ON client_assets(client_id, asset_type) WHERE deleted_at IS NULL;

CREATE TABLE client_brand_colors (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    hex                         TEXT NOT NULL CHECK (hex ~ '^[0-9A-Fa-f]{6}$'),
    display_order               INT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_brand_colors_client
    ON client_brand_colors(client_id, display_order);

-- =========================================================================
-- 17.4 — Client portal (pulled forward from Phase 2 §11.7) + approval workflow
-- =========================================================================

CREATE TABLE client_portal_users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    email                       CITEXT NOT NULL,
    name                        TEXT,
    role                        TEXT NOT NULL DEFAULT 'reviewer'
        CHECK (role IN ('reviewer','viewer')),
    invited_by_user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ,
    UNIQUE (client_id, email)
);

CREATE INDEX idx_client_portal_users_workspace
    ON client_portal_users(workspace_id) WHERE deleted_at IS NULL;

CREATE TABLE client_portal_sessions (
    token_hash                  BYTEA PRIMARY KEY,
    portal_user_id              UUID NOT NULL REFERENCES client_portal_users(id) ON DELETE CASCADE,
    issued_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ NOT NULL,
    revoked_at                  TIMESTAMPTZ,
    user_agent                  TEXT,
    ip_address                  INET
);

CREATE INDEX idx_client_portal_sessions_user
    ON client_portal_sessions(portal_user_id);
CREATE INDEX idx_client_portal_sessions_expires
    ON client_portal_sessions(expires_at) WHERE revoked_at IS NULL;

CREATE TABLE post_approval_requests (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id                     UUID NOT NULL REFERENCES owned_posts(id) ON DELETE CASCADE,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    requested_by_user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ NOT NULL,
    resolved_at                 TIMESTAMPTZ,
    resolution                  TEXT CHECK (resolution IN ('approved','rejected','expired')),
    resolved_by_portal_user_id  UUID REFERENCES client_portal_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_approval_requests_post
    ON post_approval_requests(post_id);
CREATE INDEX idx_approval_requests_unresolved
    ON post_approval_requests(post_id) WHERE resolved_at IS NULL;

CREATE TABLE post_approval_recipients (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_request_id         UUID NOT NULL REFERENCES post_approval_requests(id) ON DELETE CASCADE,
    portal_user_id              UUID NOT NULL REFERENCES client_portal_users(id) ON DELETE CASCADE,
    notification_sent_at        TIMESTAMPTZ,
    last_viewed_at              TIMESTAMPTZ,
    UNIQUE (approval_request_id, portal_user_id)
);

CREATE TABLE post_comments (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id                     UUID NOT NULL REFERENCES owned_posts(id) ON DELETE CASCADE,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Author can be either an internal user or a portal user; one must be set.
    author_user_id              UUID REFERENCES users(id) ON DELETE SET NULL,
    author_portal_user_id       UUID REFERENCES client_portal_users(id) ON DELETE SET NULL,
    anchored_to_platform        TEXT,
    body                        TEXT NOT NULL,
    is_resolved                 BOOLEAN NOT NULL DEFAULT false,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (author_user_id IS NOT NULL OR author_portal_user_id IS NOT NULL)
);

CREATE INDEX idx_post_comments_post
    ON post_comments(post_id, created_at);

-- =========================================================================
-- 17.6 — Manual social listening (saved queries)
-- =========================================================================

CREATE TABLE social_listening_queries (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id                   UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    query                       TEXT NOT NULL,
    excluded_terms              TEXT[] NOT NULL DEFAULT '{}',
    platforms                   TEXT[] NOT NULL DEFAULT ARRAY['bluesky','reddit'],
    last_run_at                 TIMESTAMPTZ,
    last_result_count           INT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_listening_queries_client
    ON social_listening_queries(client_id);
