import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { Alert, Eyebrow, Pill, PrimaryLink, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, type ReportSummary } from '../lib/api';

/**
 * Past-reports browser. Left rail (25%) lists all of the client's reports newest-first; right
 * pane (75%) loads the rendered HTML preview of whichever report is selected. Auto-selects the
 * most recent report regardless of status; non-ready reports show a placeholder with a CTA back
 * to the builder rather than embedding the in-progress UI inline.
 */
export function ClientReports() {
  const { id = '' } = useParams();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedFromQuery = searchParams.get('selected');

  const reports = useQuery({
    queryKey: ['client-reports', id],
    queryFn: () => api.listClientReports(id),
  });
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
}: {
  clientId: string;
  reports: ReportSummary[];
  isLoading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
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
            {reports.map((r) => (
              <li key={r.id}>
                <ReportRow
                  report={r}
                  selected={selectedId === r.id}
                  onSelect={() => onSelect(r.id)}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </aside>
  );
}

function ReportRow({
  report,
  selected,
  onSelect,
}: {
  report: ReportSummary;
  selected: boolean;
  onSelect: () => void;
}) {
  const tone: Record<ReportSummary['status'], PillTone> = {
    draft: 'gray',
    processing: 'amber',
    ready: 'green',
    failed: 'red',
  };
  const when = report.generated_at
    ? `generated ${relativeTime(report.generated_at)}`
    : `created ${relativeTime(report.created_at)}`;
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-current={selected}
      className={`w-full text-left px-4 py-3 transition-colors flex items-start gap-3 ${
        selected ? 'bg-gray-50 border-l-2 border-l-ink pl-[14px]' : 'hover:bg-gray-50'
      }`}
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-ink truncate">{report.title}</span>
        </div>
        <div className="text-xs text-gray-500 mt-0.5 truncate">
          {formatPeriod(report.period_start, report.period_end)}
        </div>
        <div className="mt-1.5 flex items-center gap-2">
          <Pill tone={tone[report.status]}>{report.status}</Pill>
          <span className="text-[11px] text-gray-400 truncate">{when}</span>
        </div>
      </div>
    </button>
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
  if (report.status === 'ready') {
    return (
      <iframe
        title={report.title}
        // /v1/reports/:id/preview returns the same authenticated rendered HTML used elsewhere.
        // Same-origin iframe so relative /v1/screenshots/... URLs in the body resolve correctly.
        src={`/v1/reports/${report.id}/preview`}
        className="w-full bg-white border border-gray-200 rounded-xl"
        style={{ height: '70vh' }}
      />
    );
  }
  return <NonReadyPlaceholder report={report} />;
}

function NonReadyPlaceholder({ report }: { report: ReportSummary }) {
  // Drafts / failed / in-flight reports don't have a rendered preview to embed. Send the user
  // to the builder where draft editing and retry actually happen.
  const copy: Record<Exclude<ReportSummary['status'], 'ready'>, { title: string; body: string }> = {
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
  const c = copy[report.status as Exclude<ReportSummary['status'], 'ready'>];
  return (
    <div className="bg-white border border-gray-200 rounded-xl flex items-center justify-center p-12">
      <div className="text-center max-w-sm">
        <h2 className="text-base font-semibold text-ink">{c.title}</h2>
        <p className="mt-1 text-sm text-gray-600">{c.body}</p>
        <Link
          to={`/reports/${report.id}`}
          className="mt-5 ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium inline-block"
        >
          Open in builder →
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
