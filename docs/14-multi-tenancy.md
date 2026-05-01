# 14 — Multi-tenancy

This doc states the rules every tenant-scoped table must follow and the pre-flight checklist for adding new ones. It exists because cross-tenant data leakage is the highest-cost class of bug we can ship — one incident burns trust irrecoverably with the affected workspace and casts doubt on every other workspace's privacy.

## The model

Beat is a multi-tenant SaaS with **workspace-level isolation**. Every customer-facing entity is owned by exactly one workspace; no entity is ever shared across workspaces (with one explicit exception, below).

The single primary key for tenancy is `workspaces.id`. Every tenant-scoped table carries a `workspace_id UUID NOT NULL` foreign key to `workspaces(id)`. Application code enforces isolation via mandatory `workspace_id` filters on every query; we do not yet rely on Postgres Row-Level Security (RLS), though Phase 4 enterprise tier introduces it for additional defense in depth.

The exception: a few tables are **global** by design and carry no `workspace_id`:

- `outlets` — the curated outlet directory (~500 hand-curated outlets, plus LLM-classified rows on cache miss). Outlet identity is universal; an outlet's tier doesn't depend on which workspace is looking at it.
- `authors` — the journalist database. Journalists exist independently of any workspace. Per-workspace data *about* a journalist (notes, pitch history) lives in workspace-scoped tables that reference `authors.id` from the workspace context.
- `domains_pitch_response_rates` — anonymized, aggregated, computed from cross-workspace data. Used to surface "this journalist replies to ~14% of pitches across all PR users" without exposing individual workspace data.

Anything else gets `workspace_id`.

## Rules

### Schema

Every tenant-scoped table:

1. Has a `workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE` column.
2. Has at least one index that includes `workspace_id` as the leading column (so workspace-scoped queries scan only that workspace's rows).
3. Has a soft-delete `deleted_at TIMESTAMPTZ NULL` if the entity is user-facing (so deletes are recoverable and audit trails preserved).

The `ON DELETE CASCADE` is intentional: when a workspace is deleted, every dependent row goes with it. This simplifies offboarding and prevents zombie data.

### Repository / DAO layer

Every repository method that reads or writes tenant data takes a `workspaceId: UUID` parameter and includes it in every WHERE clause. Methods that do not take `workspaceId` are inherently dangerous and require explicit annotation (`@CrossWorkspace` in code) plus a code-review check. There is exactly one valid use case for cross-workspace reads in current code: aggregate analytics that anonymize before returning.

Pseudocode pattern (Java/Spring):

```java
@Repository
public class PitchRepository {

    public Pitch findById(UUID workspaceId, UUID pitchId) {
        // workspaceId is part of the query, not just a parameter.
        return jdbc.query(
            "SELECT * FROM pitches WHERE id = :id AND workspace_id = :ws AND deleted_at IS NULL",
            Map.of("id", pitchId, "ws", workspaceId),
            Pitch.MAPPER
        ).stream().findFirst().orElse(null);
    }

    // BAD — never write this.
    // public Pitch findById(UUID pitchId) { ... }
}
```

A controller that does not pass `workspaceId` from the authenticated session into every repository call is broken. Code review catches this; integration tests verify it.

### Controller / API layer

The `workspaceId` is derived from the authenticated session, never from the request payload or URL. Endpoints accept `client_id` or `pitch_id` in the URL; the workspace context comes from `SecurityContext.getCurrentWorkspaceId()`.

```java
@PostMapping("/v1/pitches")
public ResponseEntity<Pitch> createPitch(@RequestBody CreatePitchRequest req) {
    UUID workspaceId = SecurityContext.getCurrentWorkspaceId();  // from session
    return ResponseEntity.ok(pitchService.create(workspaceId, req));
}
```

The session establishes the workspace; no client-supplied `workspace_id` is ever trusted. This prevents the worst-case scenario: a malicious client passing a `workspace_id` they don't have access to.

### Cross-workspace reads (aggregate analytics)

The few legitimate cases — computing `authors.pitch_response_rate_global`, the cross-customer pattern signals fed into ranking — must:

1. Run as background batch jobs, not in-request handlers.
2. Aggregate before any workspace-attributable data leaves the query (no row-level joins that could leak individual pitches across workspaces).
3. Persist the aggregate to a global table (`domains_pitch_response_rates` or similar) with no `workspace_id` field.
4. Be code-reviewed by at least two engineers before merging.

The aggregate row tells the consumer "Sarah Perez replies to 14% of pitches across all users" without ever revealing whose pitches.

## Pre-flight checklist for a new tenant table

When adding a new table that holds workspace data, walk this checklist before merging the migration. Most failures here are caught by integration tests; the checklist exists to prevent the migration from shipping in the first place.

1. **`workspace_id` column exists, NOT NULL, FK to workspaces, ON DELETE CASCADE.** Never optional, never default-able away.
2. **Index includes `workspace_id` as leading column.** Run `EXPLAIN` on the canonical query; it must show an index scan, not a seq scan.
3. **Soft delete column (`deleted_at`) if entity is user-facing.** Operational/derivative tables (rollups, caches) may use hard delete; user content does not.
4. **Repository methods all take `workspaceId` parameter.** Greppable: every public read/write method.
5. **Controllers derive workspace from session, not payload.** No `@RequestBody` field named `workspace_id`.
6. **Integration test asserts cross-workspace isolation.** Specifically: create row in workspace A, attempt to read with workspace B's session, assert 404 or 403.
7. **Activity event added for the corresponding mutations.** See `docs/15-additions.md` §15.2.
8. **No PII unique to this table that's already stored elsewhere.** If we already have email addresses on `users`, don't duplicate them on the new table; reference by ID.
9. **No JOIN paths that could accidentally leak across workspaces.** If the new table joins `authors` (global), the `authors` join must be scoped by other workspace-tenant filters in the query.
10. **Documentation updated.** Add the table to `docs/03-data-model.md`. Reference any LLM-related fields in `docs/05-llm-prompts.md`.

## Phase 4 enhancement: Postgres RLS

The Phase 4 enterprise tier introduces Postgres Row-Level Security policies as a defense-in-depth layer. Every tenant-scoped table gets a policy:

```sql
ALTER TABLE pitches ENABLE ROW LEVEL SECURITY;
CREATE POLICY pitches_workspace_isolation ON pitches
    USING (workspace_id = current_setting('app.workspace_id')::uuid);
```

The application sets `app.workspace_id` per-connection at the start of every request. RLS catches any application-layer bug that forgets to filter by `workspace_id` — the database refuses to return rows the session shouldn't see.

RLS is enterprise-tier-only because it adds connection-management overhead (every pool checkout sets the workspace, every checkin clears it) and because the smaller-tier customer base hasn't asked for the additional assurance. Migration to enabled-by-default is a Phase 4 question and depends on enterprise demand.

## Onboarding workflow

When a new workspace is created:

1. `workspaces` row created with the founding user as owner.
2. Founding user's `users.workspace_id` set to the new workspace.
3. Default `client_context` template not auto-created (clients are added explicitly).
4. Stripe customer record created and linked.
5. BCC capture address allocated (`{workspace-slug}.bcc@capture.beat.app` if available, else `{uuid-prefix}.bcc@capture.beat.app`).

## Offboarding workflow

When a workspace is deleted (user request or non-payment after grace period):

1. Workspace marked `deleted_at`. UI access immediately revoked.
2. After 30-day grace period (in case of error or reinstatement request), the cascade triggers: `DELETE FROM workspaces WHERE id = $1` deletes every tenant-scoped row.
3. Aggregate signals previously contributed by this workspace remain in the global tables (anonymized, can't be reattributed to the deleted workspace).
4. Stripe subscription canceled; final invoice generated.

The 30-day grace period is critical. Do not move to immediate cascade deletion — accidental deletion has happened in our customer-development conversations and recovery in Postgres point-in-time-recovery is more expensive than holding for 30 days.

## Cross-references

- `docs/03-data-model.md` — schema definitions for all tenant-scoped tables
- `docs/02-architecture-and-stack.md` — auth and session management
- `docs/16-client-dashboard.md` — pre-flight applied to `client_alerts`
- `docs/12-phase-3-pitch-tracker.md` — pre-flight applied to `pitches`, `pitch_recipients`, `pitch_replies`, `pitch_coverage_attributions`, `pitch_analytics_rollups`, `journalist_enrichment_cache`
- `docs/12a-phase-3-campaign-workflow.md` — pre-flight applied to `campaigns`, `campaign_targets`, `campaign_pitches`
- `docs/17-phase-1-5-social.md` — pre-flight applied to social tables
