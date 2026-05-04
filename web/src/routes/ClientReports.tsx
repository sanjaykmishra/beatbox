import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { useConfirm } from '../components/ConfirmDialog';
import { useToast } from '../components/Toast';
import { Alert, Eyebrow, Pill, PrimaryLink, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, ApiError, type ReportStatus, type ReportSummary } from '../lib/api';
import { reportPolicy } from '../lib/reportPolicy';
import { injectBase } from '../lib/srcdocBase';

/**
 * Past-reports browser. Left rail (25%) lists all of the client's reports newest-first; right
 * pane (75%) loads the rendered HTML preview of whichever report is selected. Auto-selects the
 * most recent report regardless of status; non-ready reports show a placeholder with a CTA back
 * to the builder rather than embedding the in-progress UI inline.
 */
export function ClientReports() {
  const { id = '' } = useParams();
  const { workspace, user } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedFromQuery = searchParams.get('selected');
  const qc = useQueryClient();
  const confirm = useConfirm();
  const toast = useToast();

  const reports = useQuery({
    queryKey: ['client-reports', id],
    queryFn: () => api.listClientReports(id),
  });

  const deleteReport = useMutation({
    mutationFn: (reportId: string) => api.deleteReport(reportId),
    onSuccess: () => {
      toast.success('Report deleted.');
      void qc.invalidateQueries({ queryKey: ['client-reports', id] });
    },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Could not delete.'),
  });

  async function onDelete(r: ReportSummary) {
    const ok = await confirm({
      title: 'Delete this report?',
      body: 'This cannot be undone. The PDF and any share links will stop working.',
      tone: 'danger',
      confirmLabel: 'Delete report',
    });
    if (!ok) return;
    deleteReport.mutate(r.id);
  }
  const client = useQuery({
    queryKey: ['client', id],
    queryFn: () => api.getClient(id),
  });

  // Auto-select most recent report when none is in the URL yet — matches the breadcrumb mental
  // model where "the latest report" is what the user is working on. Pushes a replace so the
  // back button doesn't bounce between auto-selected and user-selected states.
  useEffect(() => {
    if (selectedFromQuery) return;
    const list = reports.data;
    if (!list || list.length === 0) return;
    setSearchParams({ selected: list[0].id }, { replace: true });
  }, [reports.data, selectedFromQuery, setSearchParams]);

  const selected = useMemo(() => {
    const list = reports.data ?? [];
    if (selectedFromQuery) {
      return list.find((r) => r.id === selectedFromQuery) ?? list[0] ?? null;
    }
    return list[0] ?? null;
  }, [reports.data, selectedFromQuery]);

  const clientName = client.data?.name ?? 'client';

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app`, to: '/clients' },
        { label: 'clients', to: '/clients' },
        { label: clientName.toLowerCase(), to: `/clients/${id}` },
        { label: 'reports' },
      ]}
    >
      <div>
        <Eyebrow className="mb-1">Coverage reports</Eyebrow>
        <h1 className="text-2xl font-semibold tracking-tightish text-ink">{clientName}</h1>
        <p className="mt-1 text-xs text-gray-500">
          Browse past reports or start a new one. Click any to load it on the right.
        </p>
      </div>

      {reports.error && (
        <Alert
          tone="danger"
          title="Couldn't load reports"
          action={{ label: 'Retry', onClick: () => reports.refetch() }}
          className="mt-4"
        >
          The reports list didn't come back. Check your connection or try again in a moment.
        </Alert>
      )}

      <div className="mt-5 grid grid-cols-1 lg:grid-cols-[minmax(260px,25%)_1fr] gap-4 min-h-[70vh]">
        <ReportList
          clientId={id}
          reports={reports.data ?? []}
          isLoading={reports.isLoading}
          selectedId={selected?.id ?? null}
          onSelect={(rid) => setSearchParams({ selected: rid })}
          onDelete={onDelete}
          currentUserId={user?.id ?? null}
          activeMemberCount={workspace?.active_member_count ?? 1}
        />
        <ReportPane report={selected ?? null} loading={reports.isLoading} />
      </div>
    </BrowserFrame>
  );
}

function ReportList({
  clientId,
  reports,
  isLoading,
  selectedId,
  onSelect,
  onDelete,
  currentUserId,
  activeMemberCount,
}: {
  clientId: string;
  reports: ReportSummary[];
  isLoading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onDelete: (r: ReportSummary) => void;
  currentUserId: string | null;
  activeMemberCount: number;
}) {
  return (
    <aside className="bg-white border border-gray-200 rounded-xl overflow-hidden flex flex-col">
      <div className="p-3 border-b border-gray-100">
        <PrimaryLink to={`/clients/${clientId}/reports/new`} className="w-full justify-center">
          + New report
        </PrimaryLink>
      </div>
      <div className="flex-1 overflow-y-auto">
        {isLoading ? (
          <p className="p-4 text-sm text-gray-500">Loading…</p>
        ) : reports.length === 0 ? (
          <p className="p-4 text-sm text-gray-500">
            No reports yet — click <strong>+ New report</strong> to get started.
          </p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {reports.map((r) => {
              const policy = reportPolicy(r, currentUserId, activeMemberCount);
              return (
                <li key={r.id}>
                  <ReportRow
                    report={r}
                    selected={selectedId === r.id}
                    onSelect={() => onSelect(r.id)}
                    onDelete={() => onDelete(r)}
                    canDelete={policy.canDelete}
                  />
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </aside>
  );
}

const STATUS_TONE: Record<ReportStatus, PillTone> = {
  draft: 'gray',
  processing: 'amber',
  ready: 'amber',
  failed: 'red',
  published: 'green',
};

function ReportRow({
  report,
  selected,
  onSelect,
  onDelete,
  canDelete,
}: {
  report: ReportSummary;
  selected: boolean;
  onSelect: () => void;
  onDelete: () => void;
  canDelete: boolean;
}) {
  const when = report.published_at
    ? `published ${relativeTime(report.published_at)}`
    : report.generated_at
      ? `generated ${relativeTime(report.generated_at)}`
      : `created ${relativeTime(report.created_at)}`;

  // Per-status row CTA. published opens the locked preview; processing routes to the builder
  // too so the user can hit Re-generate (recovery for a stuck worker) or Delete; everything
  // else (draft / ready / failed) opens the builder for editing.
  const cta: { label: string; to: string } | null =
    report.status === 'published'
      ? { label: 'Open →', to: `/reports/${report.id}/preview` }
      : report.status === 'processing'
        ? { label: 'Open →', to: `/reports/${report.id}` }
        : { label: 'Edit report →', to: `/reports/${report.id}` };

  return (
    <div
      className={`group relative w-full transition-colors ${
        selected ? 'bg-gray-50 border-l-2 border-l-ink pl-[14px]' : 'hover:bg-gray-50'
      }`}
    >
      <button
        type="button"
        onClick={onSelect}
        aria-current={selected}
        className="text-left w-full px-4 py-3 pr-16"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-ink truncate">{report.title}</span>
        </div>
        <div className="text-xs text-gray-500 mt-0.5 truncate">
          {formatPeriod(report.period_start, report.period_end)}
        </div>
        <div className="mt-1.5 flex items-center gap-2">
          <Pill tone={STATUS_TONE[report.status]}>{report.status}</Pill>
          <span className="text-[11px] text-gray-400 truncate">{when}</span>
        </div>
      </button>
      <div className="absolute top-2 right-2 flex items-center gap-1">
        {cta && (
          <Link
            to={cta.to}
            aria-label={cta.label === 'Open →' ? 'Open report' : 'Edit report'}
            title={cta.label === 'Open →' ? 'Open report' : 'Edit report'}
            className="p-1.5 text-gray-400 hover:text-ink hover:bg-gray-100 rounded transition-colors"
          >
            {cta.label === 'Open →' ? <OpenIcon /> : <EditIcon />}
          </Link>
        )}
        {canDelete && (
          <button
            type="button"
            aria-label="Delete report"
            title="Delete report"
            onClick={(e) => {
              e.stopPropagation();
              onDelete();
            }}
            className="p-1.5 text-gray-400 hover:text-red-600 hover:bg-gray-100 rounded transition-colors"
          >
            <TrashIcon />
          </button>
        )}
      </div>
    </div>
  );
}

// Flat (filled) icons. fill="currentColor" + no stroke so the parent's text color drives them.
// Heroicons-solid-style paths.

function EditIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M14.69 2.66a2.25 2.25 0 1 1 3.18 3.18l-1.06 1.06-3.18-3.18 1.06-1.06zM12.57 4.78L3 14.36V17.5h3.14l9.57-9.57-3.14-3.15z" />
    </svg>
  );
}

function OpenIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M11 3a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0V5.41l-6.29 6.3a1 1 0 1 1-1.42-1.42L13.59 4H12a1 1 0 0 1-1-1z" />
      <path d="M5 5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-3a1 1 0 1 0-2 0v3H5V7h3a1 1 0 1 0 0-2H5z" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M8 1a1 1 0 0 0-.8.4L6.5 2.5H3.5a1 1 0 1 0 0 2h13a1 1 0 1 0 0-2h-3l-.7-1.1A1 1 0 0 0 12 1H8z" />
      <path d="M5 6.5h10l-.7 10.4A2 2 0 0 1 12.3 19H7.7a2 2 0 0 1-2-1.9L5 6.5zm3.5 2a.75.75 0 0 0-.75.75v6.5a.75.75 0 0 0 1.5 0v-6.5a.75.75 0 0 0-.75-.75zm3 0a.75.75 0 0 0-.75.75v6.5a.75.75 0 0 0 1.5 0v-6.5a.75.75 0 0 0-.75-.75z" />
    </svg>
  );
}

function ReportPane({ report, loading }: { report: ReportSummary | null; loading: boolean }) {
  if (loading) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl flex items-center justify-center text-sm text-gray-500">
        Loading…
      </div>
    );
  }
  if (!report) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl flex items-center justify-center text-sm text-gray-500 p-12 text-center">
        No reports to show yet.
      </div>
    );
  }
  // Published reports also have a rendered preview to embed; everything else (draft / processing
  // / failed) renders a placeholder pointing at the builder.
  if (report.status === 'ready' || report.status === 'published') {
    return <RenderedReportPreview report={report} />;
  }
  return <NonReadyPlaceholder report={report} />;
}

/**
 * Fetches the rendered HTML via the authenticated /v1/reports/:id/preview endpoint (Bearer-token
 * auth, same as the rest of the app) and renders it in a sandboxed iframe via srcDoc. Iframes
 * loaded by src= do NOT carry our Authorization header, so a plain {@code src=...} would 401 —
 * we fetch with the header, then inject the body.
 *
 * <p>To make the rendered HTML's relative URLs resolve correctly inside an {@code about:srcdoc}
 * document (notably {@code /v1/screenshots/...}), we inject a {@code <base href>} pointing at
 * the SPA origin. Browsers honor base inside srcDoc; resources then load from the same origin
 * that proxies {@code /v1/*} to the API.
 */
function RenderedReportPreview({ report }: { report: ReportSummary }) {
  const previewHtml = useQuery({
    queryKey: ['report-preview', report.id],
    queryFn: () => api.fetchReportPreviewHtml(report.id),
    staleTime: 60_000,
  });

  const srcDoc = useMemo(() => {
    if (!previewHtml.data) return null;
    return injectBase(previewHtml.data, window.location.origin);
  }, [previewHtml.data]);

  if (previewHtml.isLoading) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl flex items-center justify-center text-sm text-gray-500" style={{ height: '70vh' }}>
        Loading preview…
      </div>
    );
  }
  if (previewHtml.error || !srcDoc) {
    const msg =
      previewHtml.error instanceof ApiError
        ? previewHtml.error.message
        : 'Could not load preview.';
    return (
      <Alert
        tone="danger"
        title="Couldn't load preview"
        action={{ label: 'Retry', onClick: () => previewHtml.refetch() }}
      >
        {msg}
      </Alert>
    );
  }
  return (
    <iframe
      title={report.title}
      srcDoc={srcDoc}
      sandbox="allow-same-origin"
      className="w-full bg-white border border-gray-200 rounded-xl"
      style={{ height: '70vh' }}
    />
  );
}


function NonReadyPlaceholder({ report }: { report: ReportSummary }) {
  // Drafts / processing / failed reports don't have a rendered preview to embed. The status
  // banner is anchored at the top of the right pane so the user immediately sees what state
  // the report is in (rather than scanning a blank center). The CTA back to the builder sits
  // inline on the right edge. (ready and published are routed to RenderedReportPreview by
  // ReportPane, so they never land here.)
  const copy: Record<'draft' | 'processing' | 'failed', { title: string; body: string }> = {
    draft: {
      title: 'This report is still a draft.',
      body: 'Open it in the builder to add coverage and generate the PDF.',
    },
    processing: {
      title: 'This report is generating.',
      body: 'It usually takes under a minute. Open it in the builder to watch progress.',
    },
    failed: {
      title: 'This report failed to generate.',
      body: 'Open it in the builder to see the failure reason and retry.',
    },
  };
  const c = copy[report.status as 'draft' | 'processing' | 'failed'];
  return (
    <div className="bg-white border border-gray-200 rounded-xl">
      <div className="flex items-start justify-between gap-4 px-5 py-4 border-b border-gray-100">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-ink">{c.title}</h2>
          <p className="mt-0.5 text-sm text-gray-600">{c.body}</p>
        </div>
        <Link
          to={`/reports/${report.id}`}
          className="shrink-0 ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium"
        >
          Edit report →
        </Link>
      </div>
    </div>
  );
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.floor(ms / 1000);
  if (s < 60) return 'just now';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d ago`;
  const w = Math.floor(d / 7);
  if (w < 5) return `${w}w ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function formatPeriod(start: string, end: string): string {
  const s = new Date(start);
  const e = new Date(end);
  const fmt = (d: Date) =>
    d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  if (s.getFullYear() === e.getFullYear() && s.getMonth() === e.getMonth()) {
    return s.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  }
  return `${fmt(s)} – ${fmt(e)}`;
}
