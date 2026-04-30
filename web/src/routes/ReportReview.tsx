import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type CoverageItemView, type Report } from '../lib/api';

export function ReportReview() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const [editingId, setEditingId] = useState<string | null>(null);

  const report = useQuery({
    queryKey: ['report', id],
    queryFn: () => api.getReport(id),
    refetchInterval: (q) => {
      const data = q.state.data as Report | undefined;
      const inFlight = data?.coverage_items.some(
        (i) => i.extraction_status === 'queued' || i.extraction_status === 'running',
      );
      return inFlight ? 2000 : false;
    },
  });

  const counts = useMemo(() => countByStatus(report.data?.coverage_items ?? []), [report.data]);
  const editing = report.data?.coverage_items.find((i) => i.id === editingId) ?? null;
  const canGenerate =
    !!report.data &&
    counts.total > 0 &&
    counts.queued === 0 &&
    counts.running === 0 &&
    counts.done > 0;

  if (report.isLoading) return <p className="text-gray-500">Loading…</p>;
  if (report.error || !report.data) return <p className="text-red-600">Failed to load report.</p>;
  const r = report.data;

  return (
    <div className="space-y-6">
      <nav className="text-sm text-gray-500">
        <Link to="/clients" className="hover:text-gray-900">
          Clients
        </Link>{' '}
        ›{' '}
        <Link to={`/clients/${r.client_id}`} className="hover:text-gray-900">
          Client
        </Link>{' '}
        › <span className="text-gray-900">{r.title}</span>
      </nav>

      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{r.title}</h1>
          <p className="text-sm text-gray-500 mt-1">
            {r.period_start} → {r.period_end}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <CountsBadge counts={counts} />
          <button
            disabled={!canGenerate}
            className="rounded bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed"
            title={
              canGenerate
                ? 'Generate report'
                : 'Wait for all extractions to finish; need at least one done item.'
            }
          >
            Generate report →
          </button>
        </div>
      </div>

      {r.coverage_items.length === 0 ? (
        <p className="text-gray-500">No coverage items yet.</p>
      ) : (
        <ul className="grid grid-cols-1 gap-3">
          {r.coverage_items.map((item) => (
            <li key={item.id}>
              <CoverageCard
                item={item}
                onEdit={() => setEditingId(item.id)}
                onRetry={async () => {
                  try {
                    await api.retryCoverage(r.id, item.id);
                    qc.invalidateQueries({ queryKey: ['report', r.id] });
                  } catch {
                    /* swallow — UI shows status from server */
                  }
                }}
                onRemove={async () => {
                  if (!confirm('Remove this coverage item?')) return;
                  try {
                    await api.deleteCoverage(r.id, item.id);
                    qc.invalidateQueries({ queryKey: ['report', r.id] });
                  } catch {
                    /* swallow */
                  }
                }}
              />
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <EditDrawer
          item={editing}
          reportId={r.id}
          onClose={() => setEditingId(null)}
          onSaved={() => qc.invalidateQueries({ queryKey: ['report', r.id] })}
        />
      )}
    </div>
  );
}

function countByStatus(items: CoverageItemView[]) {
  const c = { total: items.length, queued: 0, running: 0, done: 0, failed: 0 };
  for (const i of items) (c as Record<string, number>)[i.extraction_status]++;
  return c;
}

function CountsBadge({
  counts,
}: {
  counts: { total: number; queued: number; running: number; done: number; failed: number };
}) {
  const inFlight = counts.queued + counts.running;
  const label =
    inFlight > 0
      ? `${counts.done} done, ${inFlight} extracting${counts.failed ? `, ${counts.failed} failed` : ''}`
      : `${counts.done} done${counts.failed ? `, ${counts.failed} failed` : ''}`;
  return <span className="text-sm text-gray-600">{label}</span>;
}

function CoverageCard({
  item,
  onEdit,
  onRetry,
  onRemove,
}: {
  item: CoverageItemView;
  onEdit: () => void;
  onRetry: () => void;
  onRemove: () => void;
}) {
  if (item.extraction_status === 'queued') {
    return <SkeletonCard label={item.source_url} status="Queued" />;
  }
  if (item.extraction_status === 'running') {
    return <SkeletonCard label={item.source_url} status="Extracting…" />;
  }
  if (item.extraction_status === 'failed') {
    return (
      <div className="bg-white border border-red-200 rounded p-4 flex items-center justify-between">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-red-700">Extraction failed</p>
          <p className="text-xs text-gray-500 truncate" title={item.source_url}>
            {item.source_url}
          </p>
          {item.extraction_error && (
            <p className="text-xs text-red-600 mt-1">{item.extraction_error}</p>
          )}
        </div>
        <div className="flex gap-2 ml-4">
          <button onClick={onRetry} className="text-sm text-gray-700 hover:text-gray-900 underline">
            Retry
          </button>
          <button onClick={onEdit} className="text-sm text-gray-700 hover:text-gray-900 underline">
            Edit manually
          </button>
          <button onClick={onRemove} className="text-sm text-red-600 hover:text-red-700 underline">
            Remove
          </button>
        </div>
      </div>
    );
  }
  // done
  return (
    <button
      onClick={onEdit}
      className="text-left w-full bg-white border border-gray-200 rounded p-4 hover:border-gray-400 flex items-start gap-4"
    >
      {item.screenshot_url ? (
        <img
          src={item.screenshot_url}
          alt=""
          className="h-16 w-24 object-cover rounded border border-gray-100 flex-none"
        />
      ) : (
        <div className="h-16 w-24 bg-gray-100 rounded flex-none" />
      )}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 text-xs text-gray-500">
          {item.outlet?.name ? (
            <span className="font-medium">{item.outlet.name}</span>
          ) : null}
          {item.outlet?.tier ? <span>· tier {item.outlet.tier}</span> : null}
          {item.publish_date ? <span>· {item.publish_date}</span> : null}
          {item.is_user_edited && (
            <span className="ml-auto text-amber-700">edited</span>
          )}
        </div>
        <h3 className="mt-1 font-medium text-gray-900 truncate">
          {item.headline ?? item.source_url}
        </h3>
        {item.lede && <p className="mt-1 text-sm text-gray-600 line-clamp-2">{item.lede}</p>}
      </div>
    </button>
  );
}

function SkeletonCard({ label, status }: { label: string; status: string }) {
  return (
    <div className="bg-white border border-gray-200 rounded p-4 flex items-start gap-4">
      <div className="h-16 w-24 bg-gray-100 rounded animate-pulse flex-none" />
      <div className="min-w-0 flex-1">
        <p className="text-xs text-gray-500">{status}</p>
        <p className="mt-1 text-sm text-gray-700 truncate" title={label}>
          {label}
        </p>
        <div className="mt-2 h-3 w-2/3 bg-gray-100 rounded animate-pulse" />
        <div className="mt-1 h-3 w-1/2 bg-gray-100 rounded animate-pulse" />
      </div>
    </div>
  );
}

function EditDrawer({
  item,
  reportId,
  onClose,
  onSaved,
}: {
  item: CoverageItemView;
  reportId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [headline, setHeadline] = useState(item.headline ?? '');
  const [lede, setLede] = useState(item.lede ?? '');
  const [publishDate, setPublishDate] = useState(item.publish_date ?? '');
  const [error, setError] = useState<string | null>(null);

  // Close on Escape.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const save = useMutation({
    mutationFn: async () => {
      const edits: Record<string, unknown> = {};
      if (headline !== (item.headline ?? '')) edits.headline = headline;
      if (lede !== (item.lede ?? '')) edits.lede = lede;
      if (publishDate !== (item.publish_date ?? '')) edits.publish_date = publishDate || undefined;
      if (Object.keys(edits).length === 0) return null;
      return api.patchCoverage(reportId, item.id, edits);
    },
    onSuccess: () => {
      onSaved();
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <div className="fixed inset-0 z-50 flex">
      <button
        type="button"
        aria-label="Close"
        className="flex-1 bg-black/40"
        onClick={onClose}
      />
      <div className="w-full max-w-md bg-white shadow-xl border-l border-gray-200 p-6 overflow-y-auto">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold tracking-tight">Edit coverage</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-900">
            ×
          </button>
        </div>
        <p className="text-xs text-gray-500 truncate mb-4" title={item.source_url}>
          {item.source_url}
        </p>
        <div className="space-y-3 text-sm">
          <Field label="Headline">
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={headline}
              onChange={(e) => setHeadline(e.target.value)}
            />
          </Field>
          <Field label="Publish date">
            <input
              type="date"
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={publishDate}
              onChange={(e) => setPublishDate(e.target.value)}
            />
          </Field>
          <Field label="Lede">
            <textarea
              rows={3}
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={lede}
              onChange={(e) => setLede(e.target.value)}
            />
          </Field>
          {item.edited_fields.length > 0 && (
            <p className="text-xs text-amber-700">
              Edited: {item.edited_fields.join(', ')} (will not be overwritten on re-runs)
            </p>
          )}
          {error && <p className="text-sm text-red-600">{error}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <button onClick={onClose} className="px-3 py-2 text-gray-700 hover:text-gray-900">
              Cancel
            </button>
            <button
              onClick={() => save.mutate()}
              disabled={save.isPending}
              className="rounded bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-800 disabled:opacity-60"
            >
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="block text-sm font-medium text-gray-700 mb-1">{label}</span>
      {children}
    </label>
  );
}
