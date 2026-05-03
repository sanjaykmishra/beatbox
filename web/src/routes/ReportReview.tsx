import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { useToast } from '../components/Toast';
import { useConfirm } from '../components/ConfirmDialog';
import { Alert, Eyebrow, Pill, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import {
  api,
  ApiError,
  type CoverageItemView,
  type Report,
  type SocialMentionView,
  type SocialPlatformId,
} from '../lib/api';

type FilterKey = 'all' | 'articles' | 'social' | 'tier1' | 'high_engagement' | 'failed';

/** A unified row in the coverage list — wireframe-31 intermixes articles and social mentions. */
type UnifiedItem =
  | { kind: 'article'; id: string; sortKey: number; data: CoverageItemView }
  | { kind: 'social'; id: string; sortKey: number; data: SocialMentionView };

export function ReportReview() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [editingArticle, setEditingArticle] = useState<CoverageItemView | null>(null);
  const [editingSocial, setEditingSocial] = useState<SocialMentionView | null>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [filter, setFilter] = useState<FilterKey>('all');
  const confirm = useConfirm();

  const report = useQuery({
    queryKey: ['report', id],
    queryFn: () => api.getReport(id),
    // Poll while either extraction or render is in flight — render-worker completion is what
    // flips the banner from "Generating…" to "Report generated", and the page wouldn't notice
    // without polling on status='processing' too.
    refetchInterval: (q) => {
      const data = q.state.data as Report | undefined;
      const extracting = isAnyExtracting(data);
      const rendering = data?.status === 'processing';
      return extracting || rendering ? 2000 : false;
    },
  });

  const generate = useMutation({
    mutationFn: () => api.generateReport(id),
    onSuccess: () => {
      // Optimistically flip the cached report to 'processing' so ReportPreview's draft-bounce
      // effect doesn't read stale 'draft' and ping-pong us back here. Invalidate too so the next
      // fetch reflects authoritative server state (failure_reason, generated_at, etc.).
      qc.setQueryData(['report', id], (prev: Report | undefined) =>
        prev ? { ...prev, status: 'processing' as const } : prev,
      );
      void qc.invalidateQueries({ queryKey: ['report', id] });
      navigate(`/reports/${id}/preview`);
    },
    onError: (e) => setGenerateError(e instanceof ApiError ? e.message : 'Generate failed'),
  });

  const r = report.data;
  const counts = r?.status_counts ?? null;
  const unified = useMemo(() => buildUnified(r), [r]);
  const filtered = useMemo(() => unified.filter((it) => matchesFilter(it, filter)), [unified, filter]);

  const canGenerate =
    !!r && (counts?.total ?? 0) > 0 && (counts?.extracting ?? 0) === 0 && (counts?.done ?? 0) > 0;

  if (report.isLoading) {
    return (
      <BrowserFrame
        crumbs={[{ label: `${slug}.beat.app`, to: '/clients' }, { label: 'reports' }]}
      >
        <p className="text-gray-500">Loading…</p>
      </BrowserFrame>
    );
  }
  if (report.error || !r) {
    return (
      <BrowserFrame
        crumbs={[{ label: `${slug}.beat.app`, to: '/clients' }, { label: 'reports' }]}
      >
        <Alert
          tone="danger"
          title="Couldn't load report"
          action={{ label: 'Retry', onClick: () => report.refetch() }}
        >
          The report data didn't come back. Check your connection or try again in a moment.
        </Alert>
      </BrowserFrame>
    );
  }

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app`, to: '/clients' },
        { label: 'clients', to: '/clients' },
        {
          label: (r.client_name ?? 'client').toLowerCase(),
          to: `/clients/${r.client_id}`,
        },
        { label: r.title.toLowerCase() },
        { label: 'coverage' },
      ]}
    >
      <div className="space-y-5">
        {/* Header — eyebrow + h1, status counts + Generate on the right (per wireframe-31). */}
        <div className="flex items-end justify-between gap-4 flex-wrap">
          <div>
            <Eyebrow className="mb-1">
              {r.title}
              <Link
                to={`/clients/${r.client_id}/reports`}
                className="ml-3 text-xs text-gray-500 hover:text-gray-800 underline decoration-dotted"
              >
                ← All reports
              </Link>
            </Eyebrow>
            <h1 className="text-2xl font-semibold tracking-tightish text-ink">Coverage</h1>
          </div>
          <div className="flex items-center gap-5">
            {counts && (
              <div className="text-sm flex items-baseline gap-3">
                <span>
                  <strong className="font-semibold text-ink tabular-nums">{counts.done}</strong>{' '}
                  <span className="text-gray-500">done</span>
                </span>
                {counts.extracting > 0 && (
                  <span className="text-blue-700 font-medium tabular-nums inline-flex items-center gap-1.5">
                    <Spinner />
                    {counts.extracting} extracting
                  </span>
                )}
                {counts.failed > 0 && (
                  <span className="text-red-700 font-medium tabular-nums">
                    {counts.failed} failed
                  </span>
                )}
              </div>
            )}
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
        </div>

        {r.status === 'failed' && (
          <Alert
            tone="warning"
            title="Last generation attempt failed"
            action={{ label: 'Try again', onClick: () => generate.mutate() }}
          >
            Click <strong>Generate report</strong> above to try again, or fix any failed
            extractions first.
          </Alert>
        )}
        {r.status === 'processing' && (
          <Alert tone="info" title="Generating…">
            We're rendering the PDF in the background. You'll be sent to the preview once it's
            ready.
          </Alert>
        )}
        {r.status === 'ready' && (
          <Alert
            tone="success"
            title="Report generated"
            action={{
              label: 'Open preview →',
              onClick: () => navigate(`/reports/${r.id}/preview`),
            }}
          >
            The PDF is ready. Edit the executive summary inline on the preview, or download to
            send to your client.
          </Alert>
        )}

        {/* Filter pills + sort dropdown. Sort is currently fixed (date desc); wireframe shows a
            select but Phase 1 doesn't need configurable sort. */}
        <div className="flex items-center gap-2 flex-wrap">
          <FilterPill
            label="All"
            count={counts?.total}
            active={filter === 'all'}
            onClick={() => setFilter('all')}
          />
          <FilterPill
            label="Articles"
            count={counts?.articles}
            active={filter === 'articles'}
            onClick={() => setFilter('articles')}
            disabled={(counts?.articles ?? 0) === 0}
          />
          <FilterPill
            label="Social"
            count={counts?.social}
            active={filter === 'social'}
            onClick={() => setFilter('social')}
            disabled={(counts?.social ?? 0) === 0}
          />
          <FilterPill
            label="Tier 1"
            active={filter === 'tier1'}
            onClick={() => setFilter('tier1')}
          />
          <FilterPill
            label="High engagement"
            active={filter === 'high_engagement'}
            onClick={() => setFilter('high_engagement')}
            disabled={(counts?.social ?? 0) === 0}
          />
          <FilterPill
            label="Failed"
            count={counts?.failed}
            active={filter === 'failed'}
            onClick={() => setFilter('failed')}
            disabled={(counts?.failed ?? 0) === 0}
          />
          <span className="ml-auto text-xs text-gray-500">Sort by</span>
          <span className="text-xs text-gray-700 border border-gray-200 rounded-md px-2 py-1">
            Date · newest first
          </span>
        </div>

        {generateError && (
          <Alert
            tone="danger"
            title="Can't generate this report yet"
            action={
              (counts?.failed ?? 0) > 0
                ? { label: 'Show failed', onClick: () => setFilter('failed') }
                : undefined
            }
            onDismiss={() => setGenerateError(null)}
          >
            {generateError}
          </Alert>
        )}

        {/* Unified items list. */}
        {unified.length === 0 ? (
          <div className="bg-white border border-dashed border-gray-300 rounded-xl p-10 text-center">
            <p className="text-sm font-medium text-ink">No coverage URLs yet</p>
            <p className="text-sm text-gray-500 mt-1">
              Paste article links or social posts (Bluesky, X, LinkedIn…) — Beat dispatches them
              automatically.
            </p>
          </div>
        ) : filtered.length === 0 ? (
          <div className="bg-white border border-dashed border-gray-300 rounded-xl p-8 text-center">
            <p className="text-sm text-gray-500">No items match this filter.</p>
          </div>
        ) : (
          <ul className="space-y-2.5">
            {filtered.map((it) =>
              it.kind === 'article' ? (
                <li key={`a-${it.id}`}>
                  <CoverageCard
                    item={it.data}
                    onEdit={() => setEditingArticle(it.data)}
                    onRetry={async () => {
                      try {
                        await api.retryCoverage(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                    onCancel={async () => {
                      try {
                        await api.cancelCoverage(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                    onRemove={async () => {
                      const ok = await confirm({
                        title: 'Remove this coverage item?',
                        tone: 'danger',
                        confirmLabel: 'Remove',
                      });
                      if (!ok) return;
                      try {
                        await api.deleteCoverage(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                  />
                </li>
              ) : (
                <li key={`s-${it.id}`}>
                  <SocialCard
                    item={it.data}
                    onEdit={() => setEditingSocial(it.data)}
                    onRetry={async () => {
                      try {
                        await api.retrySocialMention(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                    onCancel={async () => {
                      try {
                        await api.cancelSocialMention(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                    onRemove={async () => {
                      const ok = await confirm({
                        title: 'Remove this social mention?',
                        tone: 'danger',
                        confirmLabel: 'Remove',
                      });
                      if (!ok) return;
                      try {
                        await api.deleteSocialMention(r.id, it.data.id);
                        qc.invalidateQueries({ queryKey: ['report', r.id] });
                      } catch {
                        /* swallow */
                      }
                    }}
                  />
                </li>
              ),
            )}
          </ul>
        )}

        {(counts?.social ?? 0) > 0 && (
          <p className="text-xs text-gray-500 leading-relaxed bg-gray-50/60 border border-gray-100 rounded-lg p-3">
            <strong className="text-ink font-semibold">Engagement at extraction time.</strong>{' '}
            Likes, reposts, and replies are snapshotted when a mention is added. Numbers in the
            final report reflect that snapshot, not live values — reports are historical artifacts.
          </p>
        )}

        {editingArticle && (
          <ArticleEditDrawer
            item={editingArticle}
            reportId={r.id}
            onClose={() => setEditingArticle(null)}
            onSaved={() => qc.invalidateQueries({ queryKey: ['report', r.id] })}
          />
        )}
        {editingSocial && (
          <SocialEditDrawer
            item={editingSocial}
            reportId={r.id}
            onClose={() => setEditingSocial(null)}
            onSaved={() => qc.invalidateQueries({ queryKey: ['report', r.id] })}
          />
        )}
      </div>
    </BrowserFrame>
  );
}

// --------------------- helpers ---------------------

function isAnyExtracting(r: Report | undefined): boolean {
  if (!r) return false;
  if (r.coverage_items.some((i) => i.extraction_status === 'queued' || i.extraction_status === 'running'))
    return true;
  if (r.social_mentions.some((i) => i.extraction_status === 'queued' || i.extraction_status === 'running'))
    return true;
  return false;
}

function buildUnified(r: Report | undefined): UnifiedItem[] {
  if (!r) return [];
  const out: UnifiedItem[] = [];
  for (const c of r.coverage_items) {
    const t = c.publish_date ? Date.parse(c.publish_date) : 0;
    out.push({ kind: 'article', id: c.id, sortKey: t, data: c });
  }
  for (const s of r.social_mentions) {
    const t = s.posted_at ? Date.parse(s.posted_at) : 0;
    out.push({ kind: 'social', id: s.id, sortKey: t, data: s });
  }
  // Newest first; items with no date sort last (sortKey = 0).
  out.sort((a, b) => b.sortKey - a.sortKey);
  return out;
}

function matchesFilter(it: UnifiedItem, f: FilterKey): boolean {
  switch (f) {
    case 'all':
      return true;
    case 'articles':
      return it.kind === 'article';
    case 'social':
      return it.kind === 'social';
    case 'tier1':
      return it.kind === 'article' && (it.data.tier_at_extraction ?? 99) === 1;
    case 'high_engagement': {
      // Social mentions only; "high engagement" is a heuristic on likes + reposts.
      if (it.kind !== 'social') return false;
      const likes = it.data.likes_count ?? 0;
      const reposts = it.data.reposts_count ?? 0;
      return likes + reposts >= 100;
    }
    case 'failed':
      return it.data.extraction_status === 'failed';
  }
}

function FilterPill({
  label,
  count,
  active,
  onClick,
  disabled,
}: {
  label: string;
  count?: number;
  active: boolean;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`rounded-full px-3 py-1 text-xs font-medium border transition-colors ${
        active
          ? 'bg-ink text-white border-ink'
          : 'bg-white text-gray-600 border-gray-200 hover:border-gray-400'
      } disabled:opacity-40 disabled:cursor-not-allowed`}
    >
      {label}
      {typeof count === 'number' && count > 0 && (
        <span className="ml-1.5 opacity-80 tabular-nums">· {count}</span>
      )}
    </button>
  );
}

function sentimentTone(s: 'positive' | 'neutral' | 'negative' | 'mixed' | null | undefined): {
  tone: PillTone;
  label: string;
} | null {
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

function prominenceLabel(
  p: 'feature' | 'mention' | 'passing' | 'missing' | null | undefined,
): string | null {
  if (!p) return null;
  return p.charAt(0).toUpperCase() + p.slice(1);
}

// --------------------- Article card ---------------------

function CoverageCard({
  item,
  onEdit,
  onRetry,
  onCancel,
  onRemove,
}: {
  item: CoverageItemView;
  onEdit: () => void;
  onRetry: () => void;
  onCancel: () => void;
  onRemove: () => void;
}) {
  if (item.extraction_status === 'queued' || item.extraction_status === 'running') {
    return <ExtractingPanel sourceUrl={item.source_url} onCancel={onCancel} />;
  }
  if (item.extraction_status === 'failed') {
    return (
      <FailedPanel
        sourceUrl={item.source_url}
        error={item.extraction_error}
        onRetry={onRetry}
        onEdit={onEdit}
        onRemove={onRemove}
      />
    );
  }
  const sent = sentimentTone(item.sentiment);
  const date = item.publish_date
    ? new Date(item.publish_date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : null;
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onEdit}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onEdit();
        }
      }}
      className="cursor-pointer text-left w-full bg-white border border-gray-200 rounded-lg px-5 py-4 hover:border-gray-300 transition-colors flex items-start gap-4"
    >
      <Thumbnail src={item.screenshot_url} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <Pill tone="gray">Article</Pill>
          {item.outlet?.name && (
            <span className="text-xs font-medium text-gray-600">{item.outlet.name}</span>
          )}
          {item.outlet?.tier && <Pill tone="blue">Tier {item.outlet.tier}</Pill>}
          {sent && <Pill tone={sent.tone}>{sent.label}</Pill>}
          {date && <span className="text-[11px] text-gray-400">· {date}</span>}
          {item.is_user_edited && (
            <span className="text-[11px] text-amber-700 ml-auto">edited</span>
          )}
        </div>
        <h3 className="mt-1 text-sm font-medium text-ink leading-snug">
          {item.headline ?? item.source_url}
        </h3>
        {item.lede && <p className="mt-1 text-xs text-gray-500 line-clamp-2">{item.lede}</p>}
      </div>
      <div className="flex items-center gap-3 flex-none pt-0.5 text-sm text-gray-500">
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onRetry();
          }}
          className="hover:text-gray-800 hover:underline"
          title="Re-extract this article (preserves your edits)"
        >
          Re-extract
        </button>
        <span className="hover:text-gray-800">Edit ›</span>
      </div>
    </div>
  );
}

// --------------------- Social card ---------------------

const PLATFORM_BADGES: Record<
  SocialPlatformId,
  { label: string; glyph: string; bg: string; fg: string }
> = {
  x: { label: 'X', glyph: 'X', bg: '#0f172a', fg: '#ffffff' },
  linkedin: { label: 'LinkedIn', glyph: 'in', bg: '#0a66c2', fg: '#ffffff' },
  bluesky: { label: 'Bluesky', glyph: 'B', bg: '#1185fe', fg: '#ffffff' },
  threads: { label: 'Threads', glyph: '@', bg: '#0f172a', fg: '#ffffff' },
  instagram: { label: 'Instagram', glyph: 'IG', bg: '#e1306c', fg: '#ffffff' },
  facebook: { label: 'Facebook', glyph: 'f', bg: '#1877f2', fg: '#ffffff' },
  tiktok: { label: 'TikTok', glyph: 'TT', bg: '#0f172a', fg: '#ffffff' },
  reddit: { label: 'Reddit', glyph: 'r/', bg: '#ff4500', fg: '#ffffff' },
  substack: { label: 'Substack', glyph: 'S', bg: '#ff6719', fg: '#ffffff' },
  youtube: { label: 'YouTube', glyph: 'YT', bg: '#ff0000', fg: '#ffffff' },
  mastodon: { label: 'Mastodon', glyph: 'M', bg: '#6364ff', fg: '#ffffff' },
};

function SocialCard({
  item,
  onEdit,
  onRetry,
  onCancel,
  onRemove,
}: {
  item: SocialMentionView;
  onEdit: () => void;
  onRetry: () => void;
  onCancel: () => void;
  onRemove: () => void;
}) {
  if (item.extraction_status === 'queued' || item.extraction_status === 'running') {
    return <ExtractingPanel sourceUrl={item.source_url} onCancel={onCancel} />;
  }
  if (item.extraction_status === 'failed') {
    return (
      <FailedPanel
        sourceUrl={item.source_url}
        error={item.extraction_error}
        onRetry={onRetry}
        onEdit={onEdit}
        onRemove={onRemove}
      />
    );
  }
  const badge = PLATFORM_BADGES[item.platform];
  const sent = sentimentTone(item.sentiment);
  const prom = prominenceLabel(item.subject_prominence);
  const date = item.posted_at
    ? new Date(item.posted_at).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : null;
  // Wireframe shows the post body itself for social mentions; fall back to the LLM summary if
  // content_text isn't available (some platforms / fetcher failures).
  const body = item.content_text ?? item.summary;
  const followers = formatCount(item.author_follower_count);

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onEdit}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onEdit();
        }
      }}
      className="cursor-pointer text-left w-full bg-white border border-gray-200 rounded-lg px-5 py-4 hover:border-gray-300 transition-colors flex items-start gap-4"
    >
      {/* Platform thumbnail tile */}
      <div className="h-16 w-[86px] bg-gray-50 border border-gray-100 rounded flex-none flex flex-col items-center justify-center gap-1 px-1.5">
        <span
          className="h-7 w-7 rounded flex items-center justify-center font-bold text-sm"
          style={{ background: badge.bg, color: badge.fg }}
        >
          {badge.glyph}
        </span>
        <span className="text-[9px] text-gray-500 leading-none">{badge.label}</span>
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span
            className="rounded text-[11px] font-medium px-1.5 py-0.5"
            style={{ background: `${badge.bg}1a`, color: badge.bg }}
          >
            {badge.label}
          </span>
          {(item.author_display_name || item.author_handle) && (
            <span className="text-xs font-medium text-gray-600 truncate max-w-xs">
              {item.author_display_name ?? item.author_handle}
            </span>
          )}
          {sent && <Pill tone={sent.tone}>{sent.label}</Pill>}
          {prom && <Pill tone="blue">{prom}</Pill>}
          {date && (
            <span className="text-[11px] text-gray-400">
              · {date}
              {followers && ` · ${followers} followers`}
            </span>
          )}
          {item.is_user_edited && (
            <span className="text-[11px] text-amber-700 ml-auto">edited</span>
          )}
        </div>

        {body && (
          <p className="mt-1.5 text-[13px] text-ink leading-relaxed line-clamp-3">{body}</p>
        )}

        <SocialEngagementRow item={item} />
      </div>

      <div className="flex items-center gap-3 flex-none pt-0.5 text-sm text-gray-500">
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onRetry();
          }}
          className="hover:text-gray-800 hover:underline"
          title="Re-extract this mention (preserves your edits)"
        >
          Re-extract
        </button>
        <span className="hover:text-gray-800">Edit ›</span>
      </div>
    </div>
  );
}

function SocialEngagementRow({ item }: { item: SocialMentionView }) {
  const stats: { value: number; label: string }[] = [];
  if (item.likes_count != null) stats.push({ value: item.likes_count, label: 'likes' });
  if (item.reposts_count != null) stats.push({ value: item.reposts_count, label: 'reposts' });
  if (item.replies_count != null) stats.push({ value: item.replies_count, label: 'replies' });
  if (item.estimated_reach != null) stats.push({ value: item.estimated_reach, label: 'reach' });
  if (stats.length === 0) return null;
  return (
    <div className="mt-2 flex items-center gap-4 text-[11px] text-gray-500 flex-wrap">
      {stats.map((s) => (
        <span key={s.label}>
          <strong className="text-ink font-semibold tabular-nums">{formatCount(s.value)}</strong>{' '}
          {s.label}
        </span>
      ))}
    </div>
  );
}

function formatCount(n: number | null | undefined): string | null {
  if (n == null) return null;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  if (n >= 10_000) return `${Math.round(n / 1_000)}K`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, '')}K`;
  return n.toLocaleString();
}

/** Inline spinner used next to "Extracting…" labels. 12px, currentColor stroke so it inherits
 * the surrounding text color. */
function Spinner() {
  return (
    <svg
      className="animate-spin h-3 w-3"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path
        d="M21 12a9 9 0 0 1-9 9"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  );
}

/** Article thumbnail with graceful fallback when the image fails to load (broken URL, R2 misconfig,
 * stale row from a previous data shape). Matches the placeholder size + chrome so layout doesn't
 * shift when the load fails. */
function Thumbnail({ src }: { src: string | null }) {
  const validSrc = isLikelyValidImageUrl(src) ? src : null;
  // Reset failed state when the URL changes (otherwise React keeps state across re-renders and
  // a previously-failed item shows the placeholder even after the URL is fixed).
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setFailed(false);
  }, [validSrc]);
  if (!validSrc || failed) {
    return (
      <div className="h-16 w-[86px] bg-gray-50 border border-gray-100 rounded flex-none flex items-center justify-center text-[11px] text-gray-400">
        screenshot
      </div>
    );
  }
  return (
    <img
      src={validSrc}
      alt=""
      onError={() => setFailed(true)}
      onLoad={(e) => {
        // Some servers return 200 with 0 bytes (e.g. partial write) — onError doesn't fire. Trip
        // the fallback when the loaded image has no size.
        const img = e.currentTarget;
        if (!img.naturalWidth || !img.naturalHeight) setFailed(true);
      }}
      className="h-16 w-[86px] object-cover rounded border border-gray-100 flex-none bg-gray-50"
    />
  );
}

function isLikelyValidImageUrl(s: string | null): boolean {
  if (!s) return false;
  const t = s.trim();
  if (!t) return false;
  // Production R2 URLs are absolute https; local-disk URLs are relative /v1/screenshots/...
  // Anything else (a bare /screenshots/... from a buggy older code path, javascript:, etc.) is
  // rejected upfront so we don't even render the broken <img>.
  return t.startsWith('https://') || t.startsWith('http://') || t.startsWith('/v1/');
}

// --------------------- Shared panel states ---------------------

function ExtractingPanel({
  sourceUrl,
  onCancel,
}: {
  sourceUrl: string;
  onCancel?: () => void;
}) {
  return (
    <div className="bg-white border border-gray-200 rounded-lg px-5 py-4 flex items-center gap-4">
      <div className="h-16 w-[86px] bg-gray-50 border border-gray-100 rounded flex-none animate-pulse" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-mono text-gray-500 truncate" title={sourceUrl}>
          {sourceUrl.replace(/^https?:\/\//, '')}
        </p>
        <div className="mt-2 h-2 w-2/3 bg-gray-100 rounded animate-pulse" />
        <div className="mt-1.5 h-2 w-2/5 bg-gray-100 rounded animate-pulse" />
      </div>
      <div className="flex items-center gap-3 flex-none text-sm">
        <span className="text-blue-700 font-medium inline-flex items-center gap-1.5">
          <Spinner />
          Extracting…
        </span>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="text-gray-500 hover:text-gray-800 hover:underline"
            title="Cancel this extraction"
          >
            Cancel
          </button>
        )}
      </div>
    </div>
  );
}

function FailedPanel({
  sourceUrl,
  error,
  onRetry,
  onEdit,
  onRemove,
}: {
  sourceUrl: string;
  error: string | null;
  onRetry: () => void;
  onEdit: () => void;
  onRemove: () => void;
}) {
  return (
    <div className="bg-red-50/70 border border-red-200 rounded-lg px-5 py-4 flex items-center gap-4">
      <div className="h-16 w-[86px] bg-red-50 border border-red-100 rounded flex-none flex items-center justify-center text-red-500 text-[11px]">
        failed
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-red-700">Extraction failed</p>
        <p className="text-xs text-gray-500 truncate" title={sourceUrl}>
          {sourceUrl.replace(/^https?:\/\//, '')}
        </p>
        {error && <p className="text-xs text-red-600 mt-1 line-clamp-2">{error}</p>}
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

// --------------------- Edit drawers ---------------------

function ArticleEditDrawer({
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
  const [sentiment, setSentiment] = useState<'positive' | 'neutral' | 'negative' | 'mixed' | ''>(
    (item.sentiment as 'positive' | 'neutral' | 'negative' | 'mixed' | null) ?? '',
  );
  const [prominence, setProminence] = useState<
    'feature' | 'mention' | 'passing' | 'missing' | ''
  >(item.subject_prominence ?? '');
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

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
      if (sentiment && sentiment !== (item.sentiment ?? '')) edits.sentiment = sentiment;
      if (prominence && prominence !== (item.subject_prominence ?? '')) {
        edits.subject_prominence = prominence;
      }
      if (Object.keys(edits).length === 0) return null;
      return api.patchCoverage(reportId, item.id, edits);
    },
    onSuccess: (result) => {
      onSaved();
      onClose();
      if (result) toast.success('Coverage edits saved.');
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <DrawerShell onClose={onClose} title="Edit coverage" sourceUrl={item.source_url}>
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
          rows={6}
          className="w-full rounded-lg border border-gray-300 px-3 py-2"
          value={lede}
          onChange={(e) => setLede(e.target.value)}
        />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Sentiment">
          <select
            className="w-full rounded-lg border border-gray-300 px-3 py-2 bg-white"
            value={sentiment}
            onChange={(e) =>
              setSentiment(e.target.value as 'positive' | 'neutral' | 'negative' | 'mixed' | '')
            }
          >
            <option value="">—</option>
            <option value="positive">positive</option>
            <option value="neutral">neutral</option>
            <option value="mixed">mixed</option>
            <option value="negative">negative</option>
          </select>
        </Field>
        <Field label="Subject prominence">
          <select
            className="w-full rounded-lg border border-gray-300 px-3 py-2 bg-white"
            value={prominence}
            onChange={(e) =>
              setProminence(
                e.target.value as 'feature' | 'mention' | 'passing' | 'missing' | '',
              )
            }
          >
            <option value="">—</option>
            <option value="feature">feature</option>
            <option value="mention">mention</option>
            <option value="passing">passing</option>
            <option value="missing">missing</option>
          </select>
        </Field>
      </div>
      <EditedFieldsNote fields={item.edited_fields} />
      {error && (
        <Alert tone="danger" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      <DrawerFooter onClose={onClose} onSave={() => save.mutate()} saving={save.isPending} />
    </DrawerShell>
  );
}

function SocialEditDrawer({
  item,
  reportId,
  onClose,
  onSaved,
}: {
  item: SocialMentionView;
  reportId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [summary, setSummary] = useState(item.summary ?? '');
  const [sentiment, setSentiment] = useState<NonNullable<SocialMentionView['sentiment']> | ''>(
    item.sentiment ?? '',
  );
  const [rationale, setRationale] = useState(item.sentiment_rationale ?? '');
  const [prominence, setProminence] = useState<
    NonNullable<SocialMentionView['subject_prominence']> | ''
  >(item.subject_prominence ?? '');
  const [topics, setTopics] = useState((item.topics ?? []).join(', '));
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

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
      if (summary !== (item.summary ?? '')) edits.summary = summary;
      if (sentiment && sentiment !== item.sentiment) edits.sentiment = sentiment;
      if (rationale !== (item.sentiment_rationale ?? '')) edits.sentiment_rationale = rationale;
      if (prominence && prominence !== item.subject_prominence) {
        edits.subject_prominence = prominence;
      }
      const parsedTopics = topics
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
      const before = (item.topics ?? []).join('|');
      if (parsedTopics.join('|') !== before) edits.topics = parsedTopics;
      if (Object.keys(edits).length === 0) return null;
      return api.patchSocialMention(reportId, item.id, edits);
    },
    onSuccess: (result) => {
      onSaved();
      onClose();
      if (result) toast.success('Social mention edits saved.');
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  return (
    <DrawerShell onClose={onClose} title="Edit social mention" sourceUrl={item.source_url}>
      <Field label="Summary">
        <textarea
          rows={6}
          className="w-full rounded-lg border border-gray-300 px-3 py-2"
          value={summary}
          onChange={(e) => setSummary(e.target.value)}
        />
      </Field>
      <Field label="Sentiment">
        <select
          className="w-full rounded-lg border border-gray-300 px-3 py-2 bg-white"
          value={sentiment}
          onChange={(e) => setSentiment(e.target.value as typeof sentiment)}
        >
          <option value="">—</option>
          <option value="positive">Positive</option>
          <option value="neutral">Neutral</option>
          <option value="negative">Negative</option>
          <option value="mixed">Mixed</option>
        </select>
      </Field>
      <Field label="Sentiment rationale">
        <input
          className="w-full rounded-lg border border-gray-300 px-3 py-2"
          value={rationale}
          onChange={(e) => setRationale(e.target.value)}
        />
      </Field>
      <Field label="Subject prominence">
        <select
          className="w-full rounded-lg border border-gray-300 px-3 py-2 bg-white"
          value={prominence}
          onChange={(e) => setProminence(e.target.value as typeof prominence)}
        >
          <option value="">—</option>
          <option value="feature">Feature</option>
          <option value="mention">Mention</option>
          <option value="passing">Passing</option>
          <option value="missing">Missing</option>
        </select>
      </Field>
      <Field label="Topics (comma-separated)">
        <input
          className="w-full rounded-lg border border-gray-300 px-3 py-2"
          value={topics}
          onChange={(e) => setTopics(e.target.value)}
        />
      </Field>
      <EditedFieldsNote fields={item.edited_fields} />
      {error && (
        <Alert tone="danger" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      <DrawerFooter onClose={onClose} onSave={() => save.mutate()} saving={save.isPending} />
    </DrawerShell>
  );
}

function DrawerShell({
  title,
  sourceUrl,
  onClose,
  children,
}: {
  title: string;
  sourceUrl: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
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
          <h2 className="text-lg font-semibold tracking-tightish">{title}</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-900">
            ×
          </button>
        </div>
        <p className="text-xs text-gray-500 truncate mb-4 font-mono" title={sourceUrl}>
          {sourceUrl}
        </p>
        <div className="space-y-3 text-sm">{children}</div>
      </div>
    </div>
  );
}

function DrawerFooter({
  onClose,
  onSave,
  saving,
}: {
  onClose: () => void;
  onSave: () => void;
  saving: boolean;
}) {
  return (
    <div className="flex justify-end gap-2 pt-2">
      <button onClick={onClose} className="px-3 py-2 text-gray-700 hover:text-gray-900">
        Cancel
      </button>
      <button
        onClick={onSave}
        disabled={saving}
        className="ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium disabled:opacity-50 transition-colors"
      >
        {saving ? 'Saving…' : 'Save'}
      </button>
    </div>
  );
}

function EditedFieldsNote({ fields }: { fields: string[] }) {
  if (fields.length === 0) return null;
  return (
    <p className="text-xs text-amber-700">
      Edited: {fields.join(', ')} (will not be overwritten on re-runs)
    </p>
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
