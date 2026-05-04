import type { Report, ReportStatus, ReportSummary } from './api';

/**
 * Single source of truth for "what can the current user do with this report right now?"
 * Used by the list view, the coverage view, and the preview view so the rules don't drift
 * across surfaces (CLAUDE.md "same idiom in two places" rule).
 *
 * The lifecycle (per docs/03-data-model and the V013 migration):
 *
 *   draft → processing → ready → published
 *                          │
 *                          └─ failed (recoverable: → processing → ready)
 *
 * - draft / ready / failed: editable
 * - processing: locked (worker in flight)
 * - published: terminal (immutable, no delete)
 *
 * Approval gate: in a multi-person workspace the report's creator cannot self-publish; only
 * another team member can. Single-person workspaces have no choice but to self-publish, so
 * that's allowed.
 */
export type ReportPolicy = {
  canEdit: boolean;
  canDelete: boolean;
  canGenerate: boolean;
  canPublish: boolean;
  canShare: boolean;
  /** Reason the publish action is disabled (for tooltip), or null when canPublish is true. */
  publishDisabledReason: string | null;
};

type StatusOnly = Pick<Report, 'status'> & Partial<Pick<Report, 'created_by_user_id'>>;

export function reportPolicy(
  report: StatusOnly | ReportSummary,
  currentUserId: string | null | undefined,
  activeMemberCount: number,
): ReportPolicy {
  const status: ReportStatus = report.status;
  const isCreator =
    !!currentUserId &&
    'created_by_user_id' in report &&
    !!report.created_by_user_id &&
    report.created_by_user_id === currentUserId;
  const isMultiPerson = activeMemberCount > 1;

  const editableStatus = status === 'draft' || status === 'ready' || status === 'failed';
  const canPublish = status === 'ready' && !(isMultiPerson && isCreator);
  let publishDisabledReason: string | null = null;
  if (status !== 'ready') {
    publishDisabledReason =
      status === 'published'
        ? 'Already published.'
        : 'Generate the report before publishing.';
  } else if (isMultiPerson && isCreator) {
    publishDisabledReason = "Only another team member can publish your report.";
  }

  return {
    canEdit: editableStatus,
    // Delete + generate include 'processing' as the escape hatch when a render worker dies
    // mid-job and leaves the report stuck. Other mutations (Add URLs, Patch coverage, etc.)
    // still reject 'processing' to avoid racing a worker that might actually be alive.
    canDelete:
      status === 'ready' || status === 'failed' || status === 'processing',
    canGenerate: editableStatus || status === 'processing',
    canPublish,
    canShare: status === 'published',
    publishDisabledReason,
  };
}
