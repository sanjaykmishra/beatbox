import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { useToast } from '../components/Toast';
import { Alert, Pill, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, ApiError, type Report } from '../lib/api';

export function ReportPreview() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

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
      toast.success('Share link revoked.');
    },
  });

  function copy() {
    if (!shareUrl) return;
    navigator.clipboard.writeText(shareUrl).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  // The preview surface only handles processing / ready / failed reports — a draft means the
  // user navigated here directly (or via a stale link) before generating. Bounce them back to
  // the report builder where draft editing actually happens. The Alert in the body is a
  // fallback for the ~one-frame window between effect-fire and navigation.
  const status = report.data?.status;
  const reportId = report.data?.id;
  useEffect(() => {
    if (status === 'draft' && reportId) {
      navigate(`/reports/${reportId}`, { replace: true });
    }
  }, [status, reportId, navigate]);

  // Surface the processing → failed transition as a toast so the user catches it even if their
  // attention drifted. The body Alert (FailedNotice) handles the persistent banner.
  const lastStatus = useRef<Report['status'] | null>(null);
  useEffect(() => {
    if (!status) return;
    if (lastStatus.current === 'processing' && status === 'failed') {
      toast.error('Report generation failed.');
    }
    lastStatus.current = status;
  }, [status, toast]);

  if (report.isLoading) {
    return (
      <BrowserFrame
        crumbs={[
          { label: `${slug}.beat.app`, to: '/clients' },
          { label: 'reports' },
        ]}
      >
        <p className="text-gray-500">Loading…</p>
      </BrowserFrame>
    );
  }
  if (report.error || !report.data) {
    return (
      <BrowserFrame
        crumbs={[{ label: `${slug}.beat.app`, to: '/clients' }, { label: 'reports' }]}
      >
        <Alert
          tone="danger"
          title="Couldn't load report"
          action={{ label: 'Retry', onClick: () => report.refetch() }}
        >
          The report data didn't come back. Check your connection or try again.
        </Alert>
      </BrowserFrame>
    );
  }
  const r = report.data;
  const ready = r.status === 'ready';
  const generatedSecs =
    r.generated_at && r.created_at
      ? Math.max(
          1,
          Math.round(
            (new Date(r.generated_at).getTime() - new Date(r.created_at).getTime()) / 1000,
          ),
        )
      : null;

  const rightSlot = (
    <>
      <button
        disabled={!ready}
        onClick={() => share.mutate()}
        className="rounded-md border border-gray-300 bg-white px-3 py-1 text-[12px] font-medium text-gray-700 hover:border-gray-400 hover:bg-white disabled:opacity-50 transition-colors"
      >
        {share.isPending ? 'Sharing…' : 'Share link'}
      </button>
      <button
        disabled={!ready}
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
        className="ink-btn rounded-md text-white px-3 py-1 text-[12px] font-medium disabled:opacity-50 transition-colors"
      >
        Download PDF
      </button>
    </>
  );

  const chromeLabel: Crumb = ready
    ? {
        label:
          generatedSecs !== null
            ? `Report ready · generated in ${generatedSecs}s`
            : 'Report ready',
      }
    : r.status === 'processing'
      ? { label: 'Generating…' }
      : r.status === 'failed'
        ? { label: 'Generation failed' }
        : { label: r.title };

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app`, to: '/clients' },
        {
          label: (r.client_name ?? 'client').toLowerCase(),
          to: `/clients/${r.client_id}`,
        },
        { label: r.title.toLowerCase(), to: `/reports/${r.id}` },
        chromeLabel,
      ]}
      rightSlot={rightSlot}
    >
      <div className="space-y-6">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-lg font-semibold tracking-tightish text-ink">{r.title}</h1>
            <StatusPill status={r.status} />
            <Link
              to={`/clients/${r.client_id}/reports`}
              className="ml-auto text-xs text-gray-500 hover:text-gray-800 underline decoration-dotted"
            >
              ← All reports
            </Link>
          </div>
          <p className="mt-1 text-xs text-gray-500">
            Server-rendered preview of the PDF. Edit the executive summary inline; use Share or
            Download in the chrome to send.
          </p>
        </div>

        {error && (
          <Alert tone="danger" onDismiss={() => setError(null)}>
            {error}
          </Alert>
        )}

        {shareUrl && (
          <div className="bg-white border border-gray-200 rounded-xl p-3 flex items-center gap-3">
            <input
              readOnly
              value={shareUrl}
              className="flex-1 font-mono text-xs px-2 py-1.5 border border-gray-200 rounded bg-gray-50"
            />
            <button onClick={copy} className="text-sm text-gray-700 hover:text-gray-900 underline">
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

        {r.status === 'draft' && (
          <Alert
            tone="info"
            title="This report is still a draft"
            action={{
              label: 'Open builder',
              onClick: () => navigate(`/reports/${r.id}`, { replace: true }),
            }}
          >
            Finish adding coverage and click <strong>Generate report</strong> to produce a PDF.
          </Alert>
        )}
        {r.status === 'processing' && <ProcessingSkeleton />}
        {r.status === 'failed' && (
          <FailedNotice
            reason={r.failure_reason}
            onTryAgain={() => navigate(`/reports/${r.id}`)}
          />
        )}
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
              <Alert
                tone="danger"
                title="Couldn't render preview"
                action={{ label: 'Retry', onClick: () => previewHtml.refetch() }}
              >
                The render service didn't return preview HTML. Try again, or check the server
                logs.
              </Alert>
            ) : (
              <iframe
                title="Report preview"
                srcDoc={previewHtml.data ?? ''}
                sandbox="allow-same-origin"
                className="w-full h-[80vh] border border-gray-200 rounded-xl bg-white"
              />
            )}
          </div>
        )}
      </div>
    </BrowserFrame>
  );
}

type Crumb = { label: string };

function StatusPill({ status }: { status: Report['status'] }) {
  const map: Record<Report['status'], { tone: PillTone; label: string }> = {
    draft: { tone: 'gray', label: 'draft' },
    processing: { tone: 'amber', label: 'processing' },
    ready: { tone: 'green', label: 'ready' },
    failed: { tone: 'red', label: 'failed' },
  };
  const { tone, label } = map[status];
  return <Pill tone={tone}>{label}</Pill>;
}

function ProcessingSkeleton() {
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-12 text-center space-y-3">
      <div className="mx-auto h-3 w-1/3 bg-gray-100 rounded animate-pulse" />
      <div className="mx-auto h-3 w-1/4 bg-gray-100 rounded animate-pulse" />
      <p className="text-sm text-gray-500 mt-4">Rendering — usually under 30 seconds.</p>
    </div>
  );
}

function FailedNotice({
  reason,
  onTryAgain,
}: {
  reason: string | null;
  onTryAgain: () => void;
}) {
  // Map machine-shaped reasons we ship from the worker into something a human can act on.
  const friendly = friendlyFailureReason(reason);
  return (
    <Alert
      tone="danger"
      title="Generation failed"
      action={{ label: 'Back to builder', onClick: onTryAgain }}
    >
      {friendly}
      {reason && reason !== friendly && (
        <span className="block mt-1 text-xs text-red-600/80 font-mono">{reason}</span>
      )}
    </Alert>
  );
}

function friendlyFailureReason(reason: string | null): string {
  if (!reason) return 'Something went wrong rendering the PDF. Try again from the report builder.';
  if (reason === 'object_storage_not_configured') {
    return "The server can't store the PDF — neither R2 nor a local-disk fallback is configured. Set R2_* env vars or LOCAL_PDFS_DIR.";
  }
  if (reason.toLowerCase().includes('render')) {
    return 'The render service failed to produce the PDF. Check that the render container is running and reachable.';
  }
  return 'Something went wrong rendering the PDF. Try again from the report builder.';
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
  const toast = useToast();

  const save = useMutation({
    mutationFn: () => api.editSummary(reportId, text),
    onSuccess: () => {
      setOpen(false);
      onSaved();
      toast.success('Executive summary saved.');
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <div className="eyebrow">Executive summary</div>
          {edited && (
            <p className="text-xs text-amber-700 mt-1">
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
              className="ink-btn rounded-md text-white text-sm px-3 py-1.5 font-medium disabled:opacity-60 transition-colors"
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
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
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
