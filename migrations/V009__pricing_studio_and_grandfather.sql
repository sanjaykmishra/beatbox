-- V009 — pricing migration: add 'studio' plan and grandfather window for legacy customers.
--
-- Per docs/10-roadmap-overview.md: Solo $59, Agency $179, Studio $349 supersede the original
-- $39/$99 Phase 1 pricing. Customers already on a paid plan when this migration runs are
-- grandfathered for 12 months from the price-change date — their existing Stripe subscription
-- (linked to the legacy price ID) keeps billing at the old rate until then.

-- Drop and recreate the plan check to add 'studio'.
ALTER TABLE workspaces DROP CONSTRAINT IF EXISTS workspaces_plan_check;
ALTER TABLE workspaces
  ADD CONSTRAINT workspaces_plan_check
  CHECK (plan IN ('trial','solo','agency','studio','enterprise'));

-- Grandfather window. NULL = current pricing applies. Non-null = legacy pricing applies until
-- the timestamp; after that a follow-up migration job will prompt the customer to re-checkout.
ALTER TABLE workspaces
  ADD COLUMN IF NOT EXISTS grandfathered_until TIMESTAMPTZ;

-- Backfill: any workspace currently on a paid plan was on legacy pricing. Lock them in for
-- 12 months from now. Trial workspaces and unactivated workspaces are unaffected.
UPDATE workspaces
   SET grandfathered_until = now() + interval '12 months'
 WHERE plan IN ('solo','agency')
   AND grandfathered_until IS NULL
   AND deleted_at IS NULL;
