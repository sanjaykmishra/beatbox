# 17 — Phase 1.5 (months 3–4): Social

This doc adds social capability to Beat between Phase 1 (months 0–3) and Phase 2 (months 4–7). It's a half-phase — roughly 6–8 weeks — that extends the wedge from traditional PR into the modern hybrid agency workflow.

## What and why

Phase 1 made the firm great at traditional PR reporting: paste URLs, get a polished PDF. The firm's actual work, in 2026, is roughly half social. Without Phase 1.5, every Phase 1 customer eventually says "this is great but my client also wants to know what we said on LinkedIn this month and what people said back."

Phase 1.5 extends the existing model rather than replacing it:

- **Social mentions become first-class coverage**, alongside articles, in every report.
- **An editorial calendar** plans owned content across platforms.
- **An approval workflow** layers agency-client review on owned content — the differentiator vs. consumer social tools.
- **An asset library** removes the per-session "where's that photo" friction.
- **Reports integrate** earned articles, earned social, and owned content in one client narrative.
- **Lightweight social listening** lets users find mentions on demand.

What Phase 1.5 deliberately does NOT do:

- **Native publishing to platforms** (LinkedIn, X, Instagram, etc.). Too much per-platform engineering for this budget. Customers continue to publish through their existing tool. Phase 2 considers a Buffer/Later integration as a stopgap; deeper native publishing is Phase 3+.
- **Influencer/creator outreach.** Extends the Phase 3 pitch tracker.
- **AI visual content generation.** Phase 3+. Polish, not foundation.
- **Crisis spike detection / real-time alerts.** Phase 4. Too easy to ship a noisy version that breaks trust.
- **Best-time-to-post intelligence, cross-platform performance optimization.** Phase 3+.
- **Repurposing intelligence ("this post did well, adapt it").** Phase 3.

The strategic frame: Beat becomes the **strategic layer** that ties earned media and owned content into one client narrative. The publishing tools (Buffer, native platform UIs) become commoditized infrastructure underneath.

## Features

1. Social mentions as first-class coverage
2. Editorial calendar (planning-only)
3. Asset library
4. Client approval workflow
5. Social content in unified reports
6. Manual social listening

The order above is approximately the build order — features earlier in the list are foundational for later ones.

---

## 17.1 — Social mentions as first-class coverage

The single most-leveraged feature in Phase 1.5. Every existing report becomes more valuable; the data flywheel that already accumulates journalists in the `authors` table starts accumulating social authors too.

### Data model

A parallel table to `coverage_items` because the metadata is genuinely different. Forcing into one schema means lots of nullable fields and fuzzy semantics.

```sql
CREATE TABLE social_authors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform        TEXT NOT NULL CHECK (platform IN ('x','linkedin','bluesky','threads','instagram','facebook','tiktok','reddit','substack','youtube','mastodon')),
    handle          TEXT NOT NULL,                 -- e.g. "@perez" or "u/example"
    display_name    TEXT,
    bio             TEXT,
    follower_count  BIGINT,                         -- snapshotted at last sync
    profile_url     TEXT,
    avatar_url      TEXT,
    is_verified     BOOLEAN NOT NULL DEFAULT false,
    -- Cross-platform linking when the same person is on multiple platforms
    linked_author_id UUID REFERENCES authors(id) ON DELETE SET NULL,
    -- Inferred topic tags from posts
    topic_tags      TEXT[] NOT NULL DEFAULT '{}',
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, handle)
);

CREATE INDEX idx_social_authors_handle_trgm ON social_authors USING gin (handle gin_trgm_ops);
CREATE INDEX idx_social_authors_linked ON social_authors(linked_author_id);
```

```sql
CREATE TABLE social_mentions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       UUID REFERENCES reports(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- Source
    platform        TEXT NOT NULL CHECK (platform IN ('x','linkedin','bluesky','threads','instagram','facebook','tiktok','reddit','substack','youtube','mastodon')),
    source_url      TEXT NOT NULL,
    external_post_id TEXT,                          -- platform-specific ID if available
    author_id       UUID REFERENCES social_authors(id) ON DELETE SET NULL,
    -- Content
    posted_at       TIMESTAMPTZ,
    content_text    TEXT,
    content_lang    TEXT,
    has_media       BOOLEAN NOT NULL DEFAULT false,
    media_summary   TEXT,                           -- "image: CEO at podium"; LLM-generated
    media_urls      TEXT[] NOT NULL DEFAULT '{}',
    -- Thread context
    is_reply        BOOLEAN NOT NULL DEFAULT false,
    is_quote        BOOLEAN NOT NULL DEFAULT false,
    parent_post_url TEXT,                           -- link to the thing being replied to / quoted
    thread_root_url TEXT,                           -- top of the conversation
    -- Engagement (snapshotted at extraction)
    likes_count     BIGINT,
    reposts_count   BIGINT,
    replies_count   BIGINT,
    views_count     BIGINT,
    estimated_reach BIGINT,                         -- platform-specific computation
    -- Analysis
    summary         TEXT,                           -- 2-sentence LLM summary
    sentiment       TEXT CHECK (sentiment IN ('positive','neutral','negative','mixed')),
    sentiment_rationale TEXT,
    subject_prominence TEXT CHECK (subject_prominence IN ('feature','mention','passing')),
    topics          TEXT[] NOT NULL DEFAULT '{}',
    follower_count_at_post BIGINT,                  -- author's followers at time of post (snapshot)
    -- Workflow
    extraction_status TEXT NOT NULL DEFAULT 'queued' CHECK (extraction_status IN ('queued','running','done','failed')),
    extraction_error TEXT,
    extraction_prompt_version TEXT,
    raw_extracted   JSONB,
    is_user_edited  BOOLEAN NOT NULL DEFAULT false,
    edited_fields   TEXT[] NOT NULL DEFAULT '{}',
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_social_mentions_report ON social_mentions(report_id, posted_at DESC);
CREATE INDEX idx_social_mentions_client_posted ON social_mentions(client_id, posted_at DESC) WHERE report_id IS NOT NULL;
CREATE INDEX idx_social_mentions_workspace ON social_mentions(workspace_id);
CREATE UNIQUE INDEX idx_social_mentions_report_url ON social_mentions(report_id, source_url) WHERE report_id IS NOT NULL;
```

### Why some fields exist

- `report_id` is nullable. Mentions can be captured into a per-client pool (via inbox forwarding, listening, manual paste) before being assigned to a specific report. Same pattern as `coverage_inbox` from Phase 2 §11.3.
- `linked_author_id` on `social_authors` lets us recognize when "Sarah Perez" the journalist (in `authors`) is the same person as `@sarahperez` (in `social_authors`). Phase 1.5 doesn't auto-link; users can manually merge from the journalist profile UI. Auto-linking is a Phase 3 enhancement.
- `follower_count_at_post` is a snapshot, separate from `social_authors.follower_count`. Reports are historical artifacts — a journalist's reach today shouldn't change a 6-month-old report's numbers.
- `estimated_reach` is computed per-platform (see Reach formulas below) rather than just "follower_count" because some platforms have meaningful view counts and some don't.

### Reach formulas (per platform)

Where the platform exposes view counts, use them. Where it doesn't, fall back to follower-based estimates with platform-specific multipliers reflecting average organic reach:

```
x:        views_count if available, else followers × 0.04
linkedin: followers × 0.10  (engagement-skewed platform)
bluesky:  followers × 0.15
threads:  followers × 0.06
reddit:   karma + score-based; computed differently — see code
substack: subscriber count when known, else followers × 0.30
default:  followers × 0.05
```

These are deliberately conservative and documented in code (`app.beat.social.ReachEstimator`). Tune from real data once we have months of customer history.

### URL ingestion: the entry points

Three ways a mention enters the system:

1. **Manual paste** — `/reports/:id/coverage` already accepts URLs. Augment to detect social URLs and route to the social pipeline. Frontend shows the same paste field; the backend dispatches.
2. **Coverage inbox forwarding** — Phase 2 §11.3's `coverage_inbox` already extracts URLs from forwarded emails. Augment URL filtering to NOT exclude social platform URLs (currently they're filtered out as "not articles").
3. **Manual social listening** (§17.6) — one-click file from listening results.

The dispatcher logic:

```java
// In CoverageDispatcher
if (UrlClassifier.isSocialPost(url)) {
    socialExtractionQueue.enqueue(url, clientId, reportId);
} else {
    articleExtractionQueue.enqueue(url, clientId, reportId);
}
```

`UrlClassifier.isSocialPost` matches against a hardcoded list of social platform domains plus URL patterns (`twitter.com/*/status/*`, `linkedin.com/posts/*`, etc.).

### Per-platform fetchers

Each platform has its own fetcher because the data shape differs significantly:

| Platform | Approach |
|---|---|
| **X (Twitter)** | API access expensive/unreliable in 2026. Use a paid scraping service (ScrapingBee or similar) for public posts. Embed the rendered tweet for screenshot. |
| **LinkedIn** | Public post URLs render full content if scraped through a service that handles auth requirements. ScrapingBee with JS rendering. |
| **Bluesky** | Public AT Protocol API. Free, well-documented. Native fetcher. |
| **Threads** | Limited public API; scrape public URLs. |
| **Mastodon** | Federated; fetch via the post's home instance public API. |
| **Reddit** | Free public JSON API (`url + ".json"`). Native fetcher. |
| **Substack** | Article-shaped; reuse the article extractor. |
| **YouTube** | Video metadata via oEmbed or YouTube Data API (free tier). For comments analysis, defer to Phase 3+. |
| **Instagram, TikTok, Facebook** | Hard to fetch reliably. Phase 1.5: accept URLs, capture minimal metadata via oEmbed where available, prompt user to paste content manually if extraction fails. |

Implementation: `app.beat.social.fetchers.{Platform}Fetcher` — one class per platform, common interface. Failures fall back to a "manual entry" UI where the user types in what they see.

### LLM prompt: social mention extraction

A new prompt: `prompts/social-extraction-v1.md`. The prompt is structurally similar to article extraction but with platform-aware framing. See `prompts/social-extraction-v1.md` in this package.

Differences from article extraction:

- Headline is replaced by **first 100 chars of content** as the de-facto "headline" (some platforms have no titles).
- Author byline is replaced by the social handle.
- Subject prominence rules are adjusted: a 12-word post about your client is `feature`, not `mention`.
- Sentiment classification is the same four categories.
- Topic tagging is more important since social posts have no editorial categorization.

### API additions

- `POST /v1/reports/:id/social-mentions` — add social URLs (mirrors the existing coverage URL endpoint).
- `GET /v1/reports/:id` — augmented response includes a `social_mentions` array alongside `coverage_items`.
- `PATCH /v1/reports/:id/social-mentions/:item_id` — edit fields. Same `is_user_edited` mechanics as coverage items.
- `DELETE /v1/reports/:id/social-mentions/:item_id` — remove.
- `POST /v1/reports/:id/social-mentions/:item_id/retry` — re-run extraction.

### Eval set additions

The golden set in `docs/06-evals.md` adds a social tier:

- 20 social mentions across platforms (5 X, 5 LinkedIn, 4 Bluesky, 3 Reddit, 3 mixed)
- Each with: ground truth content, expected sentiment, must-include facts, must-not-include hallucinations
- Hard gates parallel to article extraction: schema compliance 100%, hallucination 0, sentiment ≥ 90%

### Risks

- **Platform terms-of-service.** Scraping public posts from X and LinkedIn lives in a gray area. Use legitimate scraping services (which handle compliance), document the architecture, and be ready to swap providers if one becomes problematic. Beat itself never directly scrapes those platforms; we only consume metadata returned by services we pay for that operate within their own legal frameworks.
- **Engagement number drift.** Likes/reposts change after extraction. The snapshot is correct for the report; users should understand the report reflects engagement at extraction time. Surface this as a tooltip on engagement numbers.
- **Cross-platform identity.** The same person on three platforms creates three `social_authors` rows. Phase 1.5 manual-merge flow is acceptable; auto-linking is a Phase 3 problem.

### Acceptance criteria

- A user pastes a tweet URL into a report and the mention appears as a card alongside articles, with sentiment, summary, engagement, and embedded media preview.
- Social mentions render in monthly client reports as a distinct section per the template (see §17.5).
- Failed extractions surface a "paste content manually" fallback that produces a usable mention without requiring the platform fetcher.
- Reach estimates are reasonable (within 2x of platform-native analytics, where measurable).

---

## 17.2 — Editorial calendar (planning-only)

The thing customers will ask for first. A unified calendar surface to plan owned content across all clients and platforms.

**Critical scope decision: planning only.** The calendar tells the agency what to publish and when; the act of publishing happens in their existing tool (native platform UIs, Buffer, Later). This is the single biggest scope-saving decision in Phase 1.5. Building real platform integrations (LinkedIn API, X API with current pricing chaos, Instagram Graph API, TikTok API) is each its own multi-week project.

### Data model

```sql
CREATE TABLE owned_posts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- Content (one row, multiple platform variants)
    title           TEXT,                           -- internal label, never published
    primary_content_text TEXT,                       -- the master copy
    -- Per-platform variants (JSONB to avoid 8 columns of nullable text)
    platform_variants JSONB NOT NULL DEFAULT '{}'::jsonb,
        -- Shape: { "linkedin": { "content": "...", "char_count": 1234, "edited_at": "..." }, ... }
    -- Scheduling
    target_platforms TEXT[] NOT NULL DEFAULT '{}', -- e.g. ['linkedin','x']
    scheduled_for   TIMESTAMPTZ,                    -- when the user plans to publish
    timezone        TEXT NOT NULL DEFAULT 'America/Los_Angeles',
    -- Workflow state
    status          TEXT NOT NULL DEFAULT 'draft' CHECK (status IN (
        'draft','internal_review','client_review','approved','scheduled','posted','archived','rejected'
    )),
    -- Series / theme tags (free-form; surfaces in calendar as colored bands)
    series_tag      TEXT,
    -- People
    drafted_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Lifecycle timestamps
    submitted_for_review_at TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    posted_at       TIMESTAMPTZ,                    -- user marks this manually after publishing
    -- Asset references
    asset_ids       UUID[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_owned_posts_client_scheduled ON owned_posts(client_id, scheduled_for) WHERE deleted_at IS NULL;
CREATE INDEX idx_owned_posts_workspace_status ON owned_posts(workspace_id, status);
CREATE INDEX idx_owned_posts_series ON owned_posts(workspace_id, series_tag) WHERE series_tag IS NOT NULL;
```

### Why one row per post (not one per platform variant)

A "post" is a unit of intent — "we want to communicate X this week." The platforms are channels for that intent. Treating cross-platform variants as separate rows fragments the editorial concept and complicates approvals (does the client approve one platform but not another? It's confusing).

The trade-off: per-platform analytics (when integrated in Phase 2+) need to attach to the variant, not the post. We'll add a child `owned_post_publications` table when native publishing lands. The current model is correct for Phase 1.5; it doesn't preclude the future model.

### State machine

```
draft ──→ internal_review ──→ client_review ──→ approved ──→ scheduled ──→ posted
   │           │                    │              │             │             │
   │           ↓                    ↓              ↓             ↓             ↓
   └────────→ rejected (back to draft, or archived)
```

`scheduled` is just `approved` plus a confirmed `scheduled_for` time. Some workflows skip explicit scheduling — agency posts immediately on approval. The state is then `posted` once the user marks it.

`posted` requires a manual click — "I posted this" — because the system doesn't know without native publishing integrations. We surface this in the UI at the scheduled time: "Did you post this?" with one click to mark posted, or to reschedule.

### Composer with platform variants

A single composer surface. Master content area on the left; right side shows variants per platform with character counters and platform-specific tips ("LinkedIn posts under 1500 chars perform best," "X allows up to 280 chars; over 280 will need to be a thread").

Variants are independently editable. Default behavior: when the master content changes, regenerate variants via LLM unless the user has manually edited a variant (then leave it alone — same `is_user_edited` discipline as extraction).

### LLM prompt: variant generation

`prompts/post-variant-v1.md` — generates platform-specific adaptations from master content. Inputs: master content, target platform, client style notes from `client_context.style_notes`. Output: platform-tuned content respecting platform conventions and char limits.

This is the lowest-stakes LLM call in the system (user reviews every variant before scheduling). Use Sonnet. Single call per platform per save.

### Calendar UI

Three views, switchable from a header tab:

1. **Month view** — full month grid. Each cell shows posts scheduled for that day, color-coded by client. Hovering shows quick preview; clicking opens detail. Click an empty cell to draft a new post on that day.
2. **Week view** — 7 days × 24 hour grid. Posts are positioned at their scheduled hour. Drag to reschedule; drag between days; drag to change time. The primary working surface.
3. **List view** — flat list, filterable by client, platform, status, series. Useful for "show me all unapproved posts" or "show me everything in the Q1 launch series."

Filtering is workspace-wide by default. Per-client filter for "Acme only" view.

The default landing view is **week**, showing the current week with previous and next 1-week navigation arrows.

### API surface

- `GET /v1/posts` — list posts with filtering (`client_id`, `status`, `from`, `to`, `series_tag`, `platform`).
- `GET /v1/posts/:id` — full detail.
- `POST /v1/posts` — create (draft).
- `PATCH /v1/posts/:id` — update fields.
- `POST /v1/posts/:id/regenerate-variants` — rerun the variant LLM call. Body: `{ platforms: [...] }`.
- `POST /v1/posts/:id/transitions/:transition` — state machine transitions (e.g. `submit_for_internal_review`, `request_client_approval`, `approve`, `reject`, `mark_posted`, `archive`). Validates allowed transitions.
- `DELETE /v1/posts/:id` — soft delete.

The transitions endpoint pattern (rather than `PATCH status`) is deliberate: state transitions have side effects (notifying client of pending review, marking timestamps) that shouldn't fire on every status update.

### Acceptance criteria

- A user can draft a post for a client, generate platform variants, schedule it, and have it appear correctly in the week view at the scheduled time.
- Drag-rescheduling in the week view feels instant (no full reload, optimistic UI).
- Filtering to "everything in client review" produces a focused list across all clients.
- The character counters per platform are accurate to current platform limits.
- A post drafted for 4 platforms shows 4 distinct variants, each respecting platform conventions.

---

## 17.3 — Asset library

Per-client asset storage. Logos, brand colors, photos, video clips, b-roll, brand guidelines docs.

### Why this matters

Without it, every post composer session begins with "where's the right photo of the CEO" or "what's the brand color again." With it, the composer becomes a flow surface — drag image in, write caption, schedule. The 5-minute search is gone.

Boring feature, real ROI.

### Data model

```sql
CREATE TABLE client_assets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    -- Storage
    storage_key     TEXT NOT NULL,                 -- R2 path: assets/{workspace_id}/{client_id}/{uuid}
    original_filename TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    -- Image-specific (nullable for non-image)
    width           INT,
    height          INT,
    -- Metadata
    title           TEXT,
    description     TEXT,
    tags            TEXT[] NOT NULL DEFAULT '{}',
    folder_path     TEXT,                           -- "/logos", "/photos/2025", etc. user-defined
    -- Lifecycle
    uploaded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    last_used_at    TIMESTAMPTZ,
    use_count       INT NOT NULL DEFAULT 0,
    -- Asset type (helps UX without forcing strict typing)
    asset_type      TEXT CHECK (asset_type IN ('logo','photo','video','document','brand_guide','color_swatch','other')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_assets_client ON client_assets(client_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_folder ON client_assets(client_id, folder_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_type ON client_assets(client_id, asset_type) WHERE deleted_at IS NULL;
```

### Storage

R2, namespaced by workspace and client:

```
assets/{workspace_id}/{client_id}/{uuid}.{ext}
```

Phase 1.5 plan limits:

| Plan | Asset storage |
|---|---|
| Solo | 1 GB total |
| Agency | 10 GB total |

Enforced at upload time. Approaching the limit surfaces a warning at 80% utilization.

### Brand colors

Phase 1.5 supports brand colors as a special case. Either:

- Stored as a special `asset_type='color_swatch'` row with hex codes in `description` (simple, but data is unstructured), OR
- A separate `client_brand_colors` table.

Recommendation: separate table, because brand colors are referenced from templates and other places where querying a special-cased asset row is awkward.

```sql
CREATE TABLE client_brand_colors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,                 -- "Primary blue", "Accent coral"
    hex             TEXT NOT NULL,                 -- "1F2937" (without #)
    display_order   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_brand_colors_client ON client_brand_colors(client_id, display_order);
```

### API surface

- `GET /v1/clients/:id/assets` — list with filtering (folder, type, tags, search).
- `POST /v1/clients/:id/assets/upload-url` — returns presigned R2 PUT URL.
- `POST /v1/clients/:id/assets` — create record after upload completes.
- `PATCH /v1/clients/:id/assets/:asset_id` — update metadata (title, tags, folder).
- `DELETE /v1/clients/:id/assets/:asset_id` — soft delete.
- `GET /v1/clients/:id/brand-colors`, `POST /v1/clients/:id/brand-colors`, `DELETE /v1/clients/:id/brand-colors/:id`.

### UI surface

`/clients/:id/assets`:

- Grid view with thumbnails. Click for detail / edit.
- Folder navigation (left rail).
- Filter chips (type, tags).
- Search by filename, title, tags.
- Drag-and-drop upload anywhere.
- Bulk-select for tagging or deletion.

In the post composer:

- Sidebar with client's recent assets visible.
- Drag-into-content or click-to-attach.
- Search bar at the top of the asset sidebar.

### What's deliberately NOT here

- AI image generation — Phase 3+.
- Background removal, automated cropping — Phase 3+.
- Asset versioning (V1, V2 of same logo) — out of scope; users replace the file or upload a new one.
- Smart de-duplication on upload — out of scope.

### Acceptance criteria

- A user can upload 50 photos to a client, organize them into folders, and find a specific one via search in under 10 seconds.
- The composer's asset sidebar shows the client's recent assets within 200ms of opening.
- Plan limits are enforced and the user gets a clear upgrade prompt when approaching the cap.

---

## 17.4 — Client approval workflow

The single biggest agency-vs-consumer-tool differentiator in Phase 1.5. Existing social tools were built for in-house teams; agencies need a structured way to send drafts to clients for review before publishing.

### How it works

1. Agency drafts a post in the calendar.
2. Agency clicks "Request client approval." Selects which client contacts to send to (one or more).
3. System generates a unique view-only link, sends an email to those contacts with the link.
4. Client opens the link in their browser. No login required (magic link in URL).
5. Client sees the post with platform previews. They can:
   - Approve → post moves to `approved` state.
   - Request changes → post moves to `rejected`. Client must include a comment.
   - Leave inline comments without approving/rejecting.
6. Comments thread on the post. Agency sees them on the post detail surface.
7. Once any single approver approves, post is approved. (Phase 2 adds multi-stage chains.)

### Reuse Phase 2 portal infrastructure

The Phase 2 client portal (`docs/11-phase-2-features.md` §11.7) already has:

- `client_portal_users` and `client_portal_sessions` tables
- Magic link auth flow
- Distinct trust domain via `/v1/portal/*` namespace

Phase 1.5 forces a small piece of the Phase 2 portal forward — specifically the auth and trust isolation. This is fine; the portal's value is high enough that earlier delivery is a feature.

The auth scope expands: in Phase 2 portal, a client_portal_user can view published reports for their client. In Phase 1.5, they can additionally view and act on posts pending their approval.

### Data model

Posts already have a status field. Add tables for the approval workflow:

```sql
CREATE TABLE post_approval_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id         UUID NOT NULL REFERENCES owned_posts(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,            -- default 14 days
    -- Outcome
    resolved_at     TIMESTAMPTZ,
    resolution      TEXT CHECK (resolution IN ('approved','rejected','expired')),
    resolved_by_portal_user_id UUID REFERENCES client_portal_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_approval_requests_post ON post_approval_requests(post_id);
CREATE INDEX idx_approval_requests_unresolved ON post_approval_requests(post_id) WHERE resolved_at IS NULL;

CREATE TABLE post_approval_recipients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_request_id UUID NOT NULL REFERENCES post_approval_requests(id) ON DELETE CASCADE,
    portal_user_id  UUID NOT NULL REFERENCES client_portal_users(id) ON DELETE CASCADE,
    notification_sent_at TIMESTAMPTZ,
    last_viewed_at  TIMESTAMPTZ,
    UNIQUE (approval_request_id, portal_user_id)
);

CREATE TABLE post_comments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id         UUID NOT NULL REFERENCES owned_posts(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    -- Author can be either an internal user or a portal user
    author_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    author_portal_user_id UUID REFERENCES client_portal_users(id) ON DELETE SET NULL,
    -- Optional anchor to a specific platform variant
    anchored_to_platform TEXT,
    body            TEXT NOT NULL,
    is_resolved     BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (author_user_id IS NOT NULL OR author_portal_user_id IS NOT NULL)
);

CREATE INDEX idx_post_comments_post ON post_comments(post_id, created_at);
```

### API additions

Internal:

- `POST /v1/posts/:id/transitions/request_client_approval` — body: `{ portal_user_ids: [...] }`. Creates `post_approval_requests` row, recipients, sends emails.
- `GET /v1/posts/:id/comments` — list comments (paginated).
- `POST /v1/posts/:id/comments` — internal user adds a comment.

Portal namespace (already separate trust domain):

- `GET /v1/portal/posts/:approval_token` — read-only post view by approval token (different from auth session). Token grants temporary post-view scope.
- `POST /v1/portal/posts/:approval_token/auth` — exchanges approval token for a portal session.
- `POST /v1/portal/posts/:id/comments` — portal user adds a comment.
- `POST /v1/portal/posts/:id/transitions/approve` — approve.
- `POST /v1/portal/posts/:id/transitions/reject` — reject. Body: `{ reason: string }` (required).

### Email templates

Three transactional emails:

1. **Approval request** to client portal user: "Hayworth PR has 1 post for Acme Corp ready for your review. Click to review."
2. **Reminder** at day 7 if unresolved.
3. **Notification to agency** when approved or rejected.

All sent via Resend or Postmark with proper threading (`In-Reply-To` headers so they group in the client's inbox).

### UI surface

Internal:

- Post detail page has an "Approval" tab showing pending requests, who's been notified, who's viewed, who's commented.
- Status badge on calendar surfaces ("Awaiting client review").
- Activity feed on dashboard shows approval-related events.

Portal:

- A focused, branded review page: post on the left with platform previews, comments and actions on the right.
- "Approve all" / "Request changes" big buttons at the top.
- Inline comment-on-platform-variant (e.g., "this LinkedIn version reads great; the X version is too punchy").

### Risks

- **Approval link forwarding.** Recipients may forward approval emails to colleagues. Don't treat the approval token as user-binding — treat it as post-binding with audit logging. Multiple people can view; only one needs to approve. Track who approved.
- **Deliverability.** Approval emails landing in spam means a post gets marked "client unresponsive" when really the client never saw it. Use Postmark or Resend with proper SPF/DKIM/DMARC. Send from a dedicated subdomain (e.g. `review.beat.app`).
- **Approval expiry edge cases.** A 14-day approval expires before the client gets back from vacation. Allow agency to extend without sending a new email.

### Acceptance criteria

- An agency can request approval, the client receives an email within 30 seconds, opens the link, and approves — all within 5 minutes from request to approval.
- Approval state changes flow back to the calendar and dashboard within 60 seconds of the client action.
- Comment threads work bidirectionally (agency and client can both see all comments).
- A bug-bounty-style review of the approval portal finds zero ways for a portal user to view posts they weren't sent.

---

## 17.5 — Social content in unified reports

The payoff feature. With #1 (social mentions) and #2 (calendar) in place, monthly client reports now include three streams of work: earned articles, earned social mentions, and owned content posted.

### Template additions

Three new section types added to the template structure (`docs/11-phase-2-features.md` §11.1's `TemplateSection` union):

```typescript
| { type: "social_mentions";
    layout: "compact_list" | "card_grid" | "highlights_only";
    sort_by: "engagement" | "reach" | "date" | "sentiment";
    max_items: number;
    platforms?: ("x"|"linkedin"|"bluesky"|"reddit"|"...")[];  // filter
  }
| { type: "owned_content_summary";
    show_breakdown_by: "platform" | "series" | "none";
    include_top_performers: boolean;  // requires post engagement data; falls back to graceful empty
  }
| { type: "unified_timeline";
    period_grouping: "daily" | "weekly";
    streams: ("articles"|"social_mentions"|"owned_posts")[];
  }
```

The `unified_timeline` is the highest-signal new section: a chronological view of all three streams together, showing the rhythm of earned + owned activity through the month.

### Updated executive summary prompt

`prompts/executive-summary-v1.md` is bumped to **v1.2** to optionally include social. New variables:

- `{{social_mentions_count}}`, `{{social_mentions_top_platform}}`, `{{social_mentions_sentiment_breakdown}}`
- `{{owned_posts_count}}`, `{{owned_posts_platforms}}`

Conditional logic: if any social data exists for the period, the summary includes it. If none, the summary falls back to v1.1 behavior (article-only).

Sample output the prompt should produce when both streams are present:

> "December delivered 14 pieces of earned media coverage across 11 outlets, including 4 tier-1 placements led by TechCrunch's Series B announcement. Earned social conversation was strong, with 47 mentions across LinkedIn and Bluesky — most notably amplification from industry analysts following the Foo Corp acquisition. The team published 12 owned posts across LinkedIn and X this month, with a thought-leadership series from the CEO continuing to perform above the client's three-month average."

The prompt's anti-hyperbole rules carry forward unchanged; eval set is extended to test mixed-stream summaries.

### Eval set additions

Add to `docs/06-evals.md`:

- 10 example "weeks" with mixed earned + owned + social data
- Hand-written acceptable summaries
- Hard gates: no fabricated numbers, no hyperbole, no claims about owned content unless the data supports them

### UI surface

Template editor (Phase 2 §11.1) gains the three new section types. For Phase 1.5, the system templates ship with one updated default that includes a `unified_timeline` and a `social_mentions: highlights_only` section. Customers don't need to touch the editor to benefit.

### Acceptance criteria

- A monthly report with both earned articles and 30+ social mentions renders cleanly without overflowing pages.
- The executive summary correctly references both streams when present and gracefully falls back when only one stream has data.
- Owned content summary works in reports for clients who don't use the calendar (graceful empty state).

---

## 17.6 — Manual social listening

The lightweight, deferrable feature. Build it if there's room; cut it if there isn't.

### What it is

A single page: `/clients/:id/listen`. The user enters a query (typically the client's name + variations + key hashtags). The system runs the query against the platforms we have integration with and returns the last ~50 mentions across all of them. Each mention has a one-click "file as social mention" action.

This is **on-demand**, not scheduled. Phase 4 brings real-time monitoring; Phase 1.5 just gives users a fast way to find recent mentions when they're building a report.

### Platforms integrated in Phase 1.5

The honest assessment of platform APIs in 2026:

| Platform | Status | Phase 1.5? |
|---|---|---|
| **Bluesky** | Free, open AT Protocol API | **Yes** |
| **Reddit** | Free public JSON API | **Yes** |
| **Mastodon** | Federated public APIs | Maybe — opportunistic |
| **X (Twitter)** | API expensive and unreliable | No — defer to Phase 4 with a paid scraping service |
| **LinkedIn** | No public search API | No — search via Google/scraping is fragile |
| **Threads** | Limited public access | No |
| **TikTok / Instagram** | No public search | No |

Bluesky and Reddit cover real PR-relevant conversation. Substantially more useful than nothing; substantially less risky than scraping X.

### Data model

```sql
CREATE TABLE social_listening_queries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    client_id       UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    query           TEXT NOT NULL,
    excluded_terms  TEXT[] NOT NULL DEFAULT '{}',
    platforms       TEXT[] NOT NULL DEFAULT '{bluesky,reddit}',
    last_run_at     TIMESTAMPTZ,
    last_result_count INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_listening_queries_client ON social_listening_queries(client_id);
```

Saved queries let the user re-run a search ("monthly Acme listen") without retyping. They're not scheduled — the user clicks "Run" each time.

### API surface

- `POST /v1/clients/:id/listening/run` — body: `{ query, excluded_terms?, platforms? }`. Returns array of mentions with metadata. Synchronous; takes a few seconds.
- `POST /v1/clients/:id/listening/file` — body: `{ mention_data, report_id? }`. Files a result as a `social_mention`.
- `GET /v1/clients/:id/listening/queries`, `POST /v1/clients/:id/listening/queries`, `DELETE /v1/clients/:id/listening/queries/:id` — saved queries.

### UI surface

A simple two-panel layout. Left: query input + saved queries list. Right: results with mention previews. Each result row has a checkbox; "File selected" button at the top batch-files into either a specific report or the inbox.

### Cost guardrails

Per-workspace rate limit: 50 listening runs per day. Bluesky and Reddit free tiers are generous but not infinite, and a runaway "run every 5 minutes" loop would burn the budget fast.

### Acceptance criteria

- A user can search "Acme Corp" and get results from both Bluesky and Reddit in under 5 seconds.
- One-click filing creates correctly-shaped `social_mentions` rows.
- Saved queries persist across sessions and re-run cleanly.

---

## Migration / backwards compatibility

All Phase 1.5 features are additive at the data model level. Existing reports continue to work unchanged.

One-time work:

- **Coverage inbox URL filtering update.** Phase 2 §11.3's filtering excludes social URLs as "not articles." Update to NOT exclude them — instead route them to the social pipeline. Backfill optional: run the dispatcher over existing inbox items if customers want their pre-Phase-1.5 forwards to retroactively become social mentions.
- **System report templates.** Update the default templates to include a `unified_timeline` and `social_mentions` section. Existing custom templates are unchanged; users opt in by editing.

## Build sequence (7-week plan)

| Week | Focus |
|---|---|
| 1 | Data model migrations (`social_authors`, `social_mentions`, `owned_posts`, `client_assets`, `client_brand_colors`, approval tables, listening queries). Base CRUD APIs. |
| 2 | Social mention extraction pipeline: per-platform fetchers (Bluesky, Reddit, X via ScrapingBee, LinkedIn via ScrapingBee, others as best-effort). LLM extraction prompt + eval. URL classifier and dispatcher. |
| 3 | Editorial calendar: month/week views, drag-drop, post detail surface, multi-platform composer, variant generation prompt + eval. |
| 4 | Calendar polish + asset library: upload, browse, drag-into-composer. Brand colors. Plan limits. |
| 5 | Approval workflow: approval request flow, portal pages, comment threads. Reuses portal auth from Phase 2 §11.7 (forced forward). |
| 6 | Reports integration: new template sections, exec summary prompt v1.2, eval updates. Updated default system template. |
| 7 | Manual social listening (Bluesky + Reddit). Polish. End-to-end dogfood with two real clients. |

If anything slips, weeks 6–7 are the cut points. Reports integration is high-value but bolt-onable; manual listening is the most deferrable feature.

## Phase boundaries

Things that belong to later phases, not Phase 1.5:

- **Native publishing to platforms** — Phase 2+ (or via Buffer/Later integration in Phase 2).
- **Cross-platform performance analytics for owned posts** — Phase 2 with `client_metrics_monthly`.
- **Influencer/creator outreach** — Phase 3, extending pitch tracker.
- **AI image generation** — Phase 3+.
- **Real-time mention monitoring** — Phase 4.
- **Crisis spike detection** — Phase 4.
- **Multi-stage approval chains, custom workflows, integrations with DocuSign-like tools** — Phase 4 enterprise.
- **Auto-linking of `social_authors` to `authors`** — Phase 3.
- **Best-time-to-post intelligence** — Phase 3+, after months of customer data.
- **Repurposing intelligence ("this post did well, adapt it")** — Phase 3.

## CLAUDE.md routing update

After merging, append to "Where to find things" in `CLAUDE.md`:

```markdown
- **Phase 1.5 (months 3–4) — social wedge:** `docs/17-phase-1-5-social.md`
```

Also update "Critical guardrails" to add:

```markdown
8. **Social mentions are first-class.** When new features reason about "what's been written about a client," they include both `coverage_items` and `social_mentions`. Don't accidentally regress to article-only logic.
```

## Cross-references

- `docs/03-data-model.md` — base tables for clients, reports, coverage_items
- `docs/05-llm-prompts.md` — prompt loader and versioning patterns
- `docs/06-evals.md` — eval harness gets new social tier
- `docs/10-roadmap-overview.md` — slot Phase 1.5 between Phases 1 and 2 in the roadmap
- `docs/11-phase-2-features.md` §11.1 — template structure (extended with social sections)
- `docs/11-phase-2-features.md` §11.7 — client portal (forced forward for approval workflow)
- `docs/14-multi-tenancy.md` — pre-flight checklist applies to all new tenant tables
- `docs/15-additions.md` — `client_context` (style notes used by post variant prompts)
