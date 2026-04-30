# 04 — API surface

REST + JSON. Spring Boot, controller-per-resource. Bearer token auth via `Authorization: Bearer <session-token>` header (token also accepted as `Cookie: session=<token>` for the web client). Errors follow RFC 7807. Every response includes `X-Request-ID` for support correlation.

OpenAPI spec generated at `/v1/openapi.json` from Spring annotations. Frontend client generated from this spec.

## Conventions

- Base path: `/v1`.
- Pagination: cursor-based. `?limit=N&cursor=<opaque>`. Response includes `next_cursor` (or null).
- All timestamps in responses: ISO 8601 with offset, e.g. `2025-12-04T14:23:00Z`.
- Soft-deleted records are 404, not visible.
- Workspace context: implicit from the authenticated session. We do NOT pass `workspace_id` in URLs except where switching is required.

## Error format

```json
{
  "type": "/errors/coverage-extraction-failed",
  "title": "Extraction failed after 3 attempts",
  "status": 422,
  "detail": "Unable to fetch article HTML — paywall detected",
  "instance": "/v1/reports/abc.../coverage/xyz...",
  "request_id": "req_01HX..."
}
```

---

## Auth

### `POST /v1/auth/signup`

Create a user and a workspace in one call.

```json
// Request
{
  "email": "alex@hayworth.pr",
  "password": "...",
  "name": "Alex Hayworth",
  "workspace_name": "Hayworth PR"
}

// Response 201
{
  "user": { "id": "...", "email": "...", "name": "..." },
  "workspace": { "id": "...", "name": "...", "slug": "hayworth-pr", "plan": "trial", "trial_ends_at": "..." },
  "session_token": "..."
}
```

### `POST /v1/auth/login`

```json
// Request
{ "email": "...", "password": "..." }

// Response 200
{ "session_token": "...", "user": {...}, "workspace": {...} }
```

### `POST /v1/auth/logout`
Invalidates the current session. Response 204.

### `POST /v1/auth/forgot-password`
Body: `{ "email": "..." }`. Always 204 regardless of whether email exists (no enumeration).

### `POST /v1/auth/reset-password`
Body: `{ "token": "...", "new_password": "..." }`. Response 204 or 400.

---

## Workspace

### `GET /v1/workspace`
Current workspace, including branding and plan.

### `PATCH /v1/workspace`
Update name, logo URL, primary color, default template ID.

### `GET /v1/workspace/usage`
Reports generated this billing period, plan limits, days remaining in trial.

### `GET /v1/workspace/members`
List members with roles.

### `POST /v1/workspace/members/invite`
Body: `{ "email": "...", "role": "member" | "viewer" }`. Sends invite email.

### `DELETE /v1/workspace/members/:user_id`
Remove a member. Cannot remove the last owner.

---

## Clients

### `GET /v1/clients`
```json
// Response
{
  "items": [
    {
      "id": "...",
      "name": "Acme Corp",
      "logo_url": "...",
      "report_count": 4,
      "last_report_at": "2025-12-31"
    }
  ],
  "next_cursor": null
}
```

### `POST /v1/clients`
Body: `{ "name": "...", "logo_url"?, "primary_color"?, "default_cadence"?, "notes"? }`. Returns the created client.

### `GET /v1/clients/:id`
Full client details plus recent reports.

### `PATCH /v1/clients/:id`
Update any client field.

### `DELETE /v1/clients/:id`
Soft delete. Reports remain accessible.

---

## Reports — the core flow

### `POST /v1/clients/:client_id/reports`
Create a draft report.

```json
// Request
{
  "title": "December 2025",
  "period_start": "2025-12-01",
  "period_end": "2025-12-31",
  "template_id": "..."        // optional; falls back to workspace default
}

// Response 201
{
  "id": "rpt_...",
  "status": "draft",
  "title": "...",
  "period_start": "...",
  "period_end": "...",
  "coverage_items": [],
  "created_at": "..."
}
```

### `POST /v1/reports/:id/coverage`
Add coverage URLs. Creates `coverage_items` and dispatches extraction jobs. Returns immediately with placeholder items in `queued` status.

```json
// Request
{
  "urls": [
    "https://techcrunch.com/2025/12/04/acme-raises-30m-series-b",
    "https://wsj.com/articles/acme-acquires-foo-corp",
    "..."
  ]
}

// Response 202
{
  "items": [
    { "id": "cov_...", "source_url": "...", "extraction_status": "queued" },
    ...
  ]
}
```

Duplicates within the same report are silently ignored (URL uniqueness enforced by index).

### `GET /v1/reports/:id`
Full report with all coverage items. Frontend polls every 2 seconds while any item is `queued` or `running`. (Phase 2: replace polling with SSE on `GET /v1/reports/:id/events`.)

```json
// Response
{
  "id": "...",
  "client": { "id": "...", "name": "Acme Corp", "logo_url": "..." },
  "status": "draft",
  "title": "December 2025",
  "period_start": "2025-12-01",
  "period_end": "2025-12-31",
  "executive_summary": null,
  "pdf_url": null,
  "share_token": null,
  "coverage_items": [
    {
      "id": "cov_...",
      "source_url": "...",
      "extraction_status": "done",
      "outlet": { "id": "...", "name": "TechCrunch", "tier": 1 },
      "author": { "id": "...", "name": "Sarah Perez" },
      "headline": "Acme Corp raises $30M Series B led by Sequoia",
      "publish_date": "2025-12-04",
      "summary": "...",
      "key_quote": "...",
      "sentiment": "positive",
      "subject_prominence": "feature",
      "topics": ["funding"],
      "estimated_reach": 12500000,
      "screenshot_url": "...",
      "is_user_edited": false,
      "edited_fields": []
    }
  ],
  "created_at": "..."
}
```

### `GET /v1/reports/:id/coverage`
Paginated coverage items only. Useful when the full report response is too large.

### `PATCH /v1/reports/:id/coverage/:item_id`
Edit any extracted field. Setting a field marks it as user-edited; that field is then sticky across re-runs.

```json
// Request
{ "headline": "Corrected headline", "sentiment": "mixed" }

// Response 200
{ /* updated coverage_item with is_user_edited=true and edited_fields including "headline","sentiment" */ }
```

### `DELETE /v1/reports/:id/coverage/:item_id`
Remove an item from the report.

### `POST /v1/reports/:id/coverage/:item_id/retry`
Re-queue extraction for a failed item.

### `POST /v1/reports/:id/generate`
Finalize: lock items, generate executive summary, render PDF.

```json
// Response 202
{ "id": "...", "status": "processing" }

// Subsequent GET /reports/:id will show status transition to "ready" with pdf_url populated.
```

Constraints:
- Report must be in `draft` status.
- All coverage items must be `done` or `failed` (no `queued`/`running`).
- Must have at least 1 successful coverage item.

### `GET /v1/reports/:id/pdf`
Returns a 302 redirect to a signed R2 URL. Available once `status = 'ready'`.

### `POST /v1/reports/:id/share`
Create a public share link.

```json
// Request
{ "expires_in_days": 30 }

// Response 200
{
  "share_url": "https://beat.app/r/abc123...",
  "expires_at": "..."
}
```

### `DELETE /v1/reports/:id/share`
Revoke the share token. Response 204.

### `POST /v1/reports/:id/duplicate`
Clone the report (without coverage items) for the next period. Useful for templating recurring reports.

```json
// Request
{ "title": "January 2026", "period_start": "2026-01-01", "period_end": "2026-01-31" }

// Response 201
{ /* new report */ }
```

---

## Public share endpoint (unauthenticated)

### `GET /v1/public/reports/:token`
Read-only HTML view of a shared report. Rendered server-side. Rate-limited to 60 requests/minute per IP. No PII beyond what the agency chose to include in the report.

If the token is expired or revoked, returns a generic "this link is no longer available" page (404 status).

---

## Templates

### `GET /v1/templates`
List system + workspace templates.

### `POST /v1/templates` (Phase 2)
Create a custom template. Body: `{ "name": "...", "structure": {...} }`.

### `GET /v1/templates/:id/preview`
Returns a PDF rendered with sample data. Useful for the template picker UI.

---

## Billing

### `GET /v1/billing`
Current subscription, plan, next invoice date, recent invoices (last 12).

### `POST /v1/billing/checkout`
```json
// Request
{ "plan": "agency", "interval": "monthly" }

// Response 200
{ "checkout_url": "https://checkout.stripe.com/..." }
```

### `POST /v1/billing/portal`
```json
// Response 200
{ "portal_url": "https://billing.stripe.com/..." }
```

### `POST /v1/webhooks/stripe`
Stripe webhook receiver. Signature verified via `STRIPE_WEBHOOK_SECRET`. Handles:
- `checkout.session.completed` → activate subscription, set workspace plan
- `customer.subscription.updated` → update plan limits
- `customer.subscription.deleted` → downgrade to trial-expired state
- `invoice.payment_failed` → email workspace owner, flag account

---

## Rate limits

Enforced at the gateway level (Spring filter), not in business logic.

| Scope | Limit |
|---|---|
| Per workspace, URL extractions | 100/minute |
| Per workspace, report generations | 10/hour |
| Per IP, unauthenticated | 60/minute |
| Per user, login attempts | 10/minute (then exponential lockout) |
| Per user, password reset | 3/hour |

Rate limit responses: 429 with `Retry-After` header.

---

## Things deliberately not in v1

- No GraphQL endpoint.
- No bulk operations on coverage items (do them client-side).
- No webhook subscriptions for customers (Phase 4).
- No public API for customers to integrate (Phase 4 enterprise).
- No SSE on report status (Phase 2 — for v1, frontend polls).
