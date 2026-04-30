import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type Report } from '../lib/api';

/**
 * Step 3 wireframe. Shows the report preview iframe (server-rendered HTML using the same
 * Handlebars template Puppeteer uses for the PDF), plus Download and Share controls.
 *
 * The browser polls GET /v1/reports/:id every 2s until status leaves 'processing'. Once the
 * report is 'ready', the PDF link enables and the iframe loads the rendered preview.
 */
export function ReportPreview() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const report = useQuery({
    queryKey: ['report', id],
    queryFn: () => api.getReport(id),
    refetchInterval: (q) => {
      const data = q.state.data as Report | undefined;
      return data?.status === 'processing' ? 2000 : false;
    },
  });
  const previewHtml = useQuery({
    queryKey: ['report-preview', id],
    queryFn: () => api.fetchReportPreviewHtml(id),
    enabled: report.data?.status === 'ready',
    staleTime: 60_000,
  });

  const share = useMutation({
    mutationFn: () => api.shareReport(id, 30),
    onSuccess: (r) => {
      setShareUrl(r.share_url);
      setCopied(false);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Share failed'),
  });

  const revoke = useMutation({
    mutationFn: () => api.revokeShare(id),
    onSuccess: () => {
      setShareUrl(null);
      qc.invalidateQueries({ queryKey: ['report', id] });
    },
  });

  function copy() {
    if (!shareUrl) return;
    navigator.clipboard.writeText(shareUrl).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

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
        <Link to={`/reports/${r.id}`} className="hover:text-gray-900">
          {r.title}
        </Link>{' '}
        › <span className="text-gray-900">Preview</span>
      </nav>

      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{r.title}</h1>
          <p className="text-sm text-gray-500 mt-1">
            <StatusBadge status={r.status} />{' '}
            {r.status === 'ready' && r.generated_at
              ? `· generated ${new Date(r.generated_at).toLocaleString()}`
              : null}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            disabled={r.status !== 'ready'}
            onClick={() => share.mutate()}
            className="rounded border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:border-gray-400 disabled:opacity-50"
          >
            {share.isPending ? 'Sharing…' : 'Share link'}
          </button>
          <button
            disabled={r.status !== 'ready'}
            onClick={async () => {
              try {
                const blob = await api.fetchReportPdfBlob(r.id);
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `${r.title}.pdf`;
                a.click();
                URL.revokeObjectURL(url);
              } catch (e) {
                setError(e instanceof ApiError ? e.message : 'Download failed');
              }
            }}
            className="rounded bg-gray-900 text-white px-3 py-2 text-sm font-medium hover:bg-gray-800 disabled:opacity-50"
          >
            Download PDF
          </button>
        </div>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {shareUrl && (
        <div className="bg-white border border-gray-200 rounded p-3 flex items-center gap-3">
          <input
            readOnly
            value={shareUrl}
            className="flex-1 font-mono text-xs px-2 py-1 border border-gray-200 rounded bg-gray-50"
          />
          <button
            onClick={copy}
            className="text-sm text-gray-700 hover:text-gray-900 underline"
          >
            {copied ? 'Copied' : 'Copy'}
          </button>
          <button
            onClick={() => revoke.mutate()}
            className="text-sm text-red-600 hover:text-red-700 underline"
          >
            Revoke
          </button>
        </div>
      )}

      {r.status === 'processing' && <ProcessingSkeleton />}
      {r.status === 'failed' && <FailedNotice />}
      {r.status === 'ready' && (
        <div className="space-y-4">
          <SummaryEditor
            reportId={r.id}
            initial={r.executive_summary ?? ''}
            edited={!!r.executive_summary_edited}
            onSaved={() => {
              qc.invalidateQueries({ queryKey: ['report', r.id] });
              qc.invalidateQueries({ queryKey: ['report-preview', r.id] });
            }}
          />
          {previewHtml.isLoading ? (
            <ProcessingSkeleton />
          ) : previewHtml.error ? (
            <p className="text-red-600 text-sm">Failed to load preview.</p>
          ) : (
            <iframe
              title="Report preview"
              srcDoc={previewHtml.data ?? ''}
              sandbox="allow-same-origin"
              className="w-full h-[80vh] border border-gray-200 rounded bg-white"
            />
          )}
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: Report['status'] }) {
  const cls =
    {
      draft: 'bg-gray-100 text-gray-700',
      processing: 'bg-amber-100 text-amber-800',
      ready: 'bg-emerald-100 text-emerald-800',
      failed: 'bg-red-100 text-red-800',
    }[status] ?? 'bg-gray-100 text-gray-700';
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${cls}`}>{status}</span>
  );
}

function ProcessingSkeleton() {
  return (
    <div className="bg-white border border-gray-200 rounded p-12 text-center space-y-3">
      <div className="mx-auto h-3 w-1/3 bg-gray-100 rounded animate-pulse" />
      <div className="mx-auto h-3 w-1/4 bg-gray-100 rounded animate-pulse" />
      <p className="text-sm text-gray-500 mt-4">Rendering — usually under 30 seconds.</p>
    </div>
  );
}

function FailedNotice() {
  return (
    <div className="bg-white border border-red-200 rounded p-6 text-sm text-red-700">
      Generation failed. Check the report's failure_reason or try again.
    </div>
  );
}

function SummaryEditor({
  reportId,
  initial,
  edited,
  onSaved,
}: {
  reportId: string;
  initial: string;
  edited: boolean;
  onSaved: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(initial);
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () => api.editSummary(reportId, text),
    onSuccess: () => {
      setOpen(false);
      onSaved();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <section className="bg-white border border-gray-200 rounded p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-medium text-gray-700">Executive summary</h2>
          {edited && (
            <p className="text-xs text-amber-700">
              Pinned by you — won't be regenerated on re-runs.
            </p>
          )}
        </div>
        {!open ? (
          <button
            onClick={() => {
              setOpen(true);
              setText(initial);
            }}
            className="text-sm text-gray-700 hover:text-gray-900 underline"
          >
            Edit
          </button>
        ) : (
          <div className="flex gap-2">
            <button
              onClick={() => setOpen(false)}
              className="text-sm text-gray-500 hover:text-gray-900"
            >
              Cancel
            </button>
            <button
              onClick={() => save.mutate()}
              disabled={save.isPending || !text.trim() || text === initial}
              className="rounded bg-gray-900 text-white text-sm px-3 py-1.5 font-medium hover:bg-gray-800 disabled:opacity-60"
            >
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        )}
      </div>
      {open ? (
        <textarea
          rows={8}
          value={text}
          onChange={(e) => setText(e.target.value)}
          className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
          placeholder="Write or paste the executive summary…"
        />
      ) : initial ? (
        <p className="text-sm text-gray-700 whitespace-pre-wrap">{initial}</p>
      ) : (
        <p className="text-sm text-gray-500 italic">No summary yet.</p>
      )}
      {error && <p className="text-sm text-red-600">{error}</p>}
    </section>
  );
}
