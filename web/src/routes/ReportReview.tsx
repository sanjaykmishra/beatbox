import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { Pill, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, ApiError, type CoverageItemView, type Report } from '../lib/api';

export function ReportReview() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [editingId, setEditingId] = useState<string | null>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);

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

  const generate = useMutation({
    mutationFn: () => api.generateReport(id),
    onSuccess: () => navigate(`/reports/${id}/preview`),
    onError: (e) => setGenerateError(e instanceof ApiError ? e.message : 'Generate failed'),
  });

  const counts = useMemo(() => countByStatus(report.data?.coverage_items ?? []), [report.data]);
  const editing = report.data?.coverage_items.find((i) => i.id === editingId) ?? null;
  const canGenerate =
    !!report.data &&
    counts.total > 0 &&
    counts.queued === 0 &&
    counts.running === 0 &&
    counts.done > 0;

  if (report.isLoading) {
    return (
      <BrowserFrame crumbs={[{ label: `${slug}.beat.app` }, { label: 'reports' }]}>
        <p className="text-gray-500">Loading…</p>
      </BrowserFrame>
    );
  }
  if (report.error || !report.data) return <p className="text-red-600">Failed to load report.</p>;
  const r = report.data;

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app` },
        { label: 'clients' },
        { label: r.title.toLowerCase() },
      ]}
    >
      <div className="space-y-6">
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-sm text-gray-500">{r.title}</p>
            <h1 className="text-2xl font-semibold tracking-tightish text-ink mt-0.5">Coverage</h1>
          </div>
          <div className="flex items-baseline gap-5">
            <CounterStat n={counts.done} label="done" tone="gray" />
            {counts.queued + counts.running > 0 && (
              <CounterStat
                n={counts.queued + counts.running}
                label="extracting"
                tone="blue"
              />
            )}
            {counts.failed > 0 && (
              <CounterStat n={counts.failed} label="failed" tone="red" />
            )}
          </div>
        </div>

        {generateError && <p className="text-sm text-red-600">{generateError}</p>}

        {r.coverage_items.length === 0 ? (
          <div className="bg-white border border-dashed border-gray-300 rounded-xl p-10 text-center">
            <p className="text-sm font-medium text-ink">No coverage URLs yet</p>
            <p className="text-sm text-gray-500 mt-1">
              Add the article links from your monthly tracker — Beat will extract them in parallel.
            </p>
          </div>
        ) : (
          <ul className="space-y-3">
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
                      /* swallow */
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

        <div className="flex items-center justify-between pt-4 border-t border-gray-100">
          <p className="text-sm text-gray-500">Click any item to edit before generating</p>
          <button
            disabled={!canGenerate || generate.isPending}
            onClick={() => generate.mutate()}
            className="ink-btn rounded-lg text-white px-5 py-2.5 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            title={
              canGenerate
                ? 'Generate report'
                : 'Wait for all extractions to finish; need at least one done item.'
            }
          >
            {generate.isPending ? 'Generating…' : 'Generate report →'}
          </button>
        </div>

        {editing && (
          <EditDrawer
            item={editing}
            reportId={r.id}
            onClose={() => setEditingId(null)}
            onSaved={() => qc.invalidateQueries({ queryKey: ['report', r.id] })}
          />
        )}
      </div>
    </BrowserFrame>
  );
}

function CounterStat({
  n,
  label,
  tone,
}: {
  n: number;
  label: string;
  tone: 'gray' | 'blue' | 'red';
}) {
  const cls =
    tone === 'blue' ? 'text-blue-700' : tone === 'red' ? 'text-red-700' : 'text-gray-700';
  return (
    <div className="flex items-baseline gap-1.5">
      <span className={`text-xl font-semibold tabular-nums ${cls}`}>{n}</span>
      <span className="text-sm text-gray-500">{label}</span>
    </div>
  );
}

function countByStatus(items: CoverageItemView[]) {
  const c = { total: items.length, queued: 0, running: 0, done: 0, failed: 0 };
  for (const i of items) (c as Record<string, number>)[i.extraction_status]++;
  return c;
}

function sentimentTone(s: CoverageItemView['sentiment']): { tone: PillTone; label: string } | null {
  switch (s) {
    case 'positive':
      return { tone: 'green', label: 'Positive' };
    case 'negative':
      return { tone: 'red', label: 'Negative' };
    case 'neutral':
      return { tone: 'gray', label: 'Neutral' };
    case 'mixed':
      return { tone: 'amber', label: 'Mixed' };
    default:
      return null;
  }
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
  if (item.extraction_status === 'queued' || item.extraction_status === 'running') {
    return (
      <div className="bg-white border border-gray-200 rounded-xl p-4 flex items-center gap-4">
        <div className="h-16 w-24 bg-gray-100 rounded-lg flex-none animate-pulse" />
        <div className="min-w-0 flex-1">
          <p className="text-sm text-gray-500 truncate" title={item.source_url}>
            {item.source_url.replace(/^https?:\/\//, '')}
          </p>
          <div className="mt-2 h-3 w-2/3 bg-gray-100 rounded animate-pulse" />
          <div className="mt-1.5 h-3 w-1/2 bg-gray-100 rounded animate-pulse" />
        </div>
        <span className="text-sm text-blue-700 flex-none">Extracting…</span>
      </div>
    );
  }
  if (item.extraction_status === 'failed') {
    return (
      <div className="bg-red-100/70 border border-red-200 rounded-xl p-4 flex items-center gap-4">
        <div className="h-16 w-24 bg-red-100/70 rounded-lg flex-none flex items-center justify-center text-red-500 text-xs">
          failed
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-red-700">Extraction failed</p>
          <p className="text-xs text-gray-500 truncate" title={item.source_url}>
            {item.source_url.replace(/^https?:\/\//, '')}
          </p>
          {item.extraction_error && (
            <p className="text-xs text-red-600 mt-1">{item.extraction_error}</p>
          )}
        </div>
        <div className="flex items-center gap-3 text-sm flex-none">
          <button onClick={onRetry} className="text-red-700 hover:underline font-medium">
            Retry →
          </button>
          <button onClick={onEdit} className="text-gray-700 hover:underline">
            Edit
          </button>
          <button onClick={onRemove} className="text-red-600 hover:underline">
            Remove
          </button>
        </div>
      </div>
    );
  }
  // done
  const sent = sentimentTone(item.sentiment);
  return (
    <button
      onClick={onEdit}
      className="text-left w-full bg-white border border-gray-200 rounded-xl p-4 hover:border-gray-300 transition-colors flex items-start gap-4"
    >
      {item.screenshot_url ? (
        <img
          src={item.screenshot_url}
          alt=""
          className="h-20 w-28 object-cover rounded-lg border border-gray-100 flex-none"
        />
      ) : (
        <div className="h-20 w-28 bg-gray-100 rounded-lg flex-none flex items-center justify-center text-[10px] text-gray-400">
          screenshot
        </div>
      )}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          {item.outlet?.name && (
            <span className="text-sm font-medium text-gray-600">{item.outlet.name}</span>
          )}
          {item.outlet?.tier && <Pill tone="blue">Tier {item.outlet.tier}</Pill>}
          {sent && <Pill tone={sent.tone}>{sent.label}</Pill>}
          {item.is_user_edited && (
            <span className="text-[11px] text-amber-700 ml-auto">edited</span>
          )}
        </div>
        <h3 className="mt-1.5 text-base font-semibold text-ink leading-snug">
          {item.headline ?? item.source_url}
        </h3>
        {item.lede && <p className="mt-1 text-sm text-gray-600 line-clamp-2">{item.lede}</p>}
      </div>
      <span className="text-sm text-gray-400 flex-none pt-1.5">Edit ›</span>
    </button>
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
          <h2 className="text-lg font-semibold tracking-tightish">Edit coverage</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-900">
            ×
          </button>
        </div>
        <p className="text-xs text-gray-500 truncate mb-4 font-mono" title={item.source_url}>
          {item.source_url}
        </p>
        <div className="space-y-3 text-sm">
          <Field label="Headline">
            <input
              className="w-full rounded-lg border border-gray-300 px-3 py-2"
              value={headline}
              onChange={(e) => setHeadline(e.target.value)}
            />
          </Field>
          <Field label="Publish date">
            <input
              type="date"
              className="w-full rounded-lg border border-gray-300 px-3 py-2"
              value={publishDate}
              onChange={(e) => setPublishDate(e.target.value)}
            />
          </Field>
          <Field label="Lede">
            <textarea
              rows={3}
              className="w-full rounded-lg border border-gray-300 px-3 py-2"
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
              className="ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium disabled:opacity-50 transition-colors"
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
      <span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>
      {children}
    </label>
  );
}
