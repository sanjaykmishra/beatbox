import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { Avatar } from '../components/Avatar';
import { BrowserFrame } from '../components/BrowserFrame';
import {
  Alert,
  Eyebrow,
  Pill,
  PrimaryLink,
  SecondaryLink,
  type PillTone,
} from '../components/ui';
import { useAuth } from '../lib/useAuth';
import {
  api,
  type AlertCard,
  type ActivityItem,
  type OwnedPost,
  type PostStatus,
  type ReportSummary,
  type Severity,
} from '../lib/api';

export function ClientDashboard() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';

  const dashboard = useQuery({
    queryKey: ['dashboard', id],
    queryFn: () => api.getClientDashboard(id),
  });

  const reportsList = useQuery({
    queryKey: ['client-reports', id],
    queryFn: () => api.listClientReports(id),
  });

  const dismiss = useMutation({
    mutationFn: () => api.dismissSetup(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dashboard', id] }),
  });

  if (dashboard.isLoading) {
    return (
      <BrowserFrame
        crumbs={[
          { label: `${slug}.beat.app`, to: '/clients' },
          { label: 'clients', to: '/clients' },
          { label: '…' },
        ]}
      >
        <DashboardSkeleton />
      </BrowserFrame>
    );
  }
  if (dashboard.error || !dashboard.data) {
    return (
      <BrowserFrame
        crumbs={[
          { label: `${slug}.beat.app`, to: '/clients' },
          { label: 'clients', to: '/clients' },
          { label: '…' },
        ]}
      >
        <Alert
          tone="danger"
          title="Couldn't load this client"
          action={{ label: 'Retry', onClick: () => dashboard.refetch() }}
        >
          The dashboard data didn't come back. Check your connection or try again in a moment.
        </Alert>
      </BrowserFrame>
    );
  }
  const d = dashboard.data;
  const setupAlert = d.alerts.find((a) => a.alert_type === 'client.setup_incomplete');
  const isNewClient = !!setupAlert && d.client.setup_dismissed_at === null;
  const visibleAlerts = d.alerts.filter(
    (a) => a.alert_type !== 'client.setup_incomplete' && a.alert_type !== 'client.healthy',
  );
  const hasHealthyOnly = d.alerts.length === 1 && d.alerts[0].alert_type === 'client.healthy';
  const attentionCount = visibleAlerts.length;

  let headerPill: { tone: PillTone; label: string } | null = null;
  if (isNewClient) headerPill = { tone: 'blue', label: 'New' };
  else if (attentionCount > 0)
    headerPill = {
      tone: 'amber',
      label: `${attentionCount} item${attentionCount === 1 ? '' : 's'} pending`,
    };
  else if (hasHealthyOnly) headerPill = { tone: 'green', label: 'Active' };

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app`, to: '/clients' },
        { label: 'clients', to: '/clients' },
        { label: d.client.name.toLowerCase() },
      ]}
    >
      <div className="space-y-7">
        {/* Header strip */}
        <div className="flex items-start gap-4">
          <Avatar
            name={d.client.name}
            logoUrl={d.client.logo_url}
            primaryColor={d.client.primary_color}
            size="lg"
          />
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2.5 flex-wrap">
              <h1 className="text-2xl font-semibold tracking-tightish text-ink">
                {d.client.name}
              </h1>
              {headerPill && <Pill tone={headerPill.tone}>{headerPill.label}</Pill>}
            </div>
            {d.client.default_cadence && (
              <p className="text-sm text-gray-500 mt-1 capitalize">
                {d.client.default_cadence} cadence
              </p>
            )}
          </div>
          <div className="flex items-center gap-2 flex-none">
            <SecondaryLink to={`/clients/${id}/edit`}>Settings</SecondaryLink>
            <SecondaryLink to={`/clients/${id}/context`}>Context</SecondaryLink>
          </div>
        </div>

        {/* Stats row */}
        <section className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          <StatTile
            label="Coverage · 30d"
            value={isNewClient ? null : d.stats_30d.coverage_count.value}
            deltaLabel={isNewClient ? null : d.stats_30d.coverage_count.delta_label}
          />
          <StatTile
            label="Tier 1"
            value={isNewClient ? null : d.stats_30d.tier_1_count.value}
            deltaLabel={isNewClient ? null : d.stats_30d.tier_1_count.delta_label}
          />
          <StatTile
            label="Sentiment"
            value={isNewClient ? null : formatSentiment(d.stats_30d.sentiment.value)}
            deltaLabel={isNewClient ? null : d.stats_30d.sentiment.delta_label}
          />
          <StatTile
            label="Reach"
            value={isNewClient ? null : formatReach(d.stats_30d.reach.value)}
            deltaLabel={isNewClient ? null : d.stats_30d.reach.delta_label}
          />
        </section>

        {/* Action row */}
        <div className="flex items-center gap-2 flex-wrap">
          <PrimaryLink to={`/clients/${id}/reports/new`}>+ New report</PrimaryLink>
          <SecondaryLink to={`/clients/${id}/context`}>View context</SecondaryLink>
        </div>

        {/* Get started (new client) */}
        {isNewClient && (
          <SetupBlock clientId={id} onDismiss={() => dismiss.mutate()} />
        )}

        {/* Two-column: Needs attention / Coming up */}
        {!isNewClient && (
          <section className="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <Column title="Needs attention">
              {visibleAlerts.length === 0 ? (
                <HealthyEmptyState clientName={d.client.name} />
              ) : (
                visibleAlerts.map((a) => <AlertCardView key={a.alert_type} alert={a} />)
              )}
            </Column>
            <Column title="Coming up">
              {d.coming_up.length === 0 ? (
                <ComingUpEmpty copy="Nothing scheduled in the next few weeks." />
              ) : (
                d.coming_up.map((c, i) => <ComingUpItemView key={`${c.kind}-${i}`} item={c} />)
              )}
            </Column>
          </section>
        )}

        {/* Upcoming posts (always shown — empty state for new clients is informative) */}
        <UpcomingPosts clientId={id} />

        {/* Past reports — newest first. Only shown when at least one exists. */}
        {!isNewClient && (reportsList.data?.length ?? 0) > 0 && (
          <PastReports reports={reportsList.data!} />
        )}

        {/* Recent activity */}
        {!isNewClient && (
          <section>
            <Eyebrow className="mb-3">Recent activity</Eyebrow>
            {d.recent_activity.length === 0 ? (
              <div className="bg-white border border-gray-200 rounded-xl p-6 text-sm text-gray-500 text-center">
                No activity in the last 14 days.
              </div>
            ) : (
              <ul className="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100 overflow-hidden">
                {d.recent_activity.map((e, i) => (
                  <ActivityRowView key={`${e.occurred_at}-${i}`} item={e} />
                ))}
              </ul>
            )}
          </section>
        )}
      </div>
    </BrowserFrame>
  );
}

function Column({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <Eyebrow className="mb-3">{title}</Eyebrow>
      <div className="space-y-3">{children}</div>
    </div>
  );
}

function StatTile({
  label,
  value,
  deltaLabel,
}: {
  label: string;
  value: number | string | null;
  deltaLabel: string | null;
}) {
  const empty = value === null;
  const up = !empty && deltaLabel?.startsWith('↑');
  const down = !empty && deltaLabel?.startsWith('↓');
  const cls = up ? 'text-emerald-700' : down ? 'text-red-700' : 'text-gray-400';
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4">
      <Eyebrow>{label}</Eyebrow>
      <div
        className={`mt-1.5 font-semibold tabular-nums tracking-tightish ${
          empty ? 'text-3xl text-gray-300' : 'text-3xl text-ink'
        }`}
      >
        {empty ? '—' : value}
      </div>
      {!empty && deltaLabel && deltaLabel !== 'stable' && (
        <div className={`text-xs mt-1.5 font-medium tabular-nums ${cls}`}>{deltaLabel}</div>
      )}
      {!empty && deltaLabel === 'stable' && (
        <div className="text-xs mt-1.5 text-gray-400">stable</div>
      )}
    </div>
  );
}

function AlertCardView({ alert }: { alert: AlertCard }) {
  const tone = alertTone(alert.severity);
  return (
    <div className={`rounded-xl border p-4 ${tone.bg} ${tone.border}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="font-semibold text-ink">{alert.card_title}</div>
          {alert.card_subtitle && (
            <div className="text-sm text-gray-600 mt-1">{alert.card_subtitle}</div>
          )}
        </div>
        {alert.card_action_path && (
          <Link
            to={alert.card_action_path}
            className={`text-sm font-medium flex-none whitespace-nowrap ${tone.action} hover:underline`}
          >
            {alert.card_action_label ?? 'Open'} →
          </Link>
        )}
      </div>
    </div>
  );
}

function alertTone(s: Severity): { bg: string; border: string; action: string } {
  switch (s) {
    case 'red':
      return { bg: 'bg-red-100/70', border: 'border-red-200', action: 'text-red-700' };
    case 'amber':
      return { bg: 'bg-amber-100/70', border: 'border-amber-200', action: 'text-amber-800' };
    case 'blue':
      return { bg: 'bg-blue-100/70', border: 'border-blue-200', action: 'text-blue-700' };
    default:
      return { bg: 'bg-white', border: 'border-gray-200', action: 'text-gray-700' };
  }
}

function HealthyEmptyState({ clientName }: { clientName: string }) {
  return (
    <div className="rounded-xl border border-dashed border-gray-300 bg-white p-8 text-center">
      <div
        className="mx-auto inline-flex items-center justify-center h-9 w-9 rounded-full bg-emerald-100 text-emerald-700 text-lg"
        aria-hidden
      >
        ✓
      </div>
      <div className="mt-3 text-sm font-semibold text-ink">All caught up</div>
      <div className="text-sm text-gray-500 mt-1">Nothing pending for {clientName}</div>
    </div>
  );
}

function SetupBlock({ clientId, onDismiss }: { clientId: string; onDismiss: () => void }) {
  return (
    <section>
      <Eyebrow className="mb-3">Get started</Eyebrow>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <SetupCard
          n={1}
          title="Set up context"
          body="Add key messages, competitive set, important dates, and style notes. Used by AI to write better summaries."
          actionLabel="Open context →"
          to={`/clients/${clientId}/context`}
        />
        <SetupCard
          n={2}
          title="Capture existing coverage"
          body="Paste URLs of past mentions to backfill history. Or forward press emails to your inbox address."
          actionLabel="Add URLs →"
          to={`/clients/${clientId}/reports/new`}
        />
        <SetupCard
          n={3}
          title="Schedule first report"
          body="Pick a cadence (monthly typical) and the next reporting period. We'll remind you when it's due."
          actionLabel="Set cadence →"
          to={`/clients/${clientId}/edit`}
        />
      </div>
      <div className="mt-4 bg-gray-50 border border-gray-200 rounded-xl p-3 flex items-center justify-between gap-3">
        <p className="text-xs text-gray-600">
          Working on something else? You can come back to setup anytime — none of these steps are
          required to start using Beat for this client.
        </p>
        <button
          onClick={onDismiss}
          className="text-xs text-gray-500 hover:text-gray-900 transition-colors flex-none"
        >
          Dismiss
        </button>
      </div>
    </section>
  );
}

function SetupCard({
  n,
  title,
  body,
  actionLabel,
  to,
}: {
  n: number;
  title: string;
  body: string;
  actionLabel: string;
  to: string;
}) {
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5 flex flex-col">
      <div className="flex items-center gap-2.5">
        <span className="flex-none flex items-center justify-center h-6 w-6 rounded-full bg-gray-100 text-gray-700 text-xs font-semibold tabular-nums">
          {n}
        </span>
        <div className="font-semibold text-ink">{title}</div>
      </div>
      <p className="text-sm text-gray-600 mt-2 flex-1">{body}</p>
      <Link
        to={to}
        className="mt-4 self-start rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50 transition-colors"
      >
        {actionLabel}
      </Link>
    </div>
  );
}

function ComingUpEmpty({ copy }: { copy: string }) {
  return (
    <div className="bg-white border border-dashed border-gray-300 rounded-xl p-5 text-center">
      <p className="text-sm text-gray-500">{copy}</p>
    </div>
  );
}

function ComingUpItemView({
  item,
}: {
  item: { kind: string; title: string; subtitle: string | null; path: string | null };
}) {
  const inner = (
    <div className="bg-white rounded-xl border border-gray-200 p-4 hover:border-gray-300 transition-colors">
      <div className="font-semibold text-sm text-ink">{item.title}</div>
      {item.subtitle && <div className="text-xs text-gray-500 mt-1">{item.subtitle}</div>}
    </div>
  );
  return item.path ? (
    <Link to={item.path} className="block">
      {inner}
    </Link>
  ) : (
    inner
  );
}

function UpcomingPosts({ clientId }: { clientId: string }) {
  // Window: start of today (local) → +30d. Using start-of-today rather than `now` so that a
  // post scheduled earlier today still appears in 'upcoming' until the day rolls over.
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const fromIso = start.toISOString();
  const toIso = new Date(start.getTime() + 30 * 86_400_000).toISOString();
  const postsQ = useQuery({
    queryKey: ['client-upcoming-posts', clientId],
    queryFn: () =>
      api.listPosts({ client_id: clientId, from: fromIso, to: toIso, limit: 100 }),
  });
  const visible = (postsQ.data?.items ?? [])
    .filter((p) => UPCOMING_STATUSES.includes(p.status))
    .sort((a, b) => (a.scheduled_for ?? '').localeCompare(b.scheduled_for ?? ''))
    .slice(0, 5);
  const total = (postsQ.data?.items ?? []).filter((p) =>
    UPCOMING_STATUSES.includes(p.status),
  ).length;
  return (
    <section>
      <div className="flex items-baseline justify-between mb-3">
        <Eyebrow>Upcoming posts</Eyebrow>
        <Link
          to={`/calendar?client_id=${clientId}`}
          className="text-xs font-medium text-gray-500 hover:text-ink hover:underline"
        >
          Open calendar →
        </Link>
      </div>
      <Link
        to={`/calendar?client_id=${clientId}`}
        className="block bg-white border border-gray-200 rounded-xl overflow-hidden hover:border-gray-300 transition-colors"
      >
        {postsQ.isLoading ? (
          <div className="p-5">
            <div className="h-3 w-1/3 bg-gray-100 rounded animate-pulse" />
          </div>
        ) : visible.length === 0 ? (
          <div className="px-5 py-6 text-sm text-gray-500 text-center">
            No posts scheduled in the next 30 days.
          </div>
        ) : (
          <>
            <ul className="divide-y divide-gray-100">
              {visible.map((p) => (
                <UpcomingPostRow key={p.id} post={p} />
              ))}
            </ul>
            {total > visible.length && (
              <div className="px-5 py-2 text-xs text-gray-500 bg-gray-50 border-t border-gray-100">
                + {total - visible.length} more in the next 30 days
              </div>
            )}
          </>
        )}
      </Link>
    </section>
  );
}

const UPCOMING_STATUSES: PostStatus[] = [
  'draft',
  'internal_review',
  'client_review',
  'approved',
  'scheduled',
];

function UpcomingPostRow({ post }: { post: OwnedPost }) {
  const summary =
    post.title?.trim() ||
    (post.primary_content_text ?? '').slice(0, 80) ||
    '(empty draft)';
  const when = post.scheduled_for
    ? new Date(post.scheduled_for).toLocaleDateString(undefined, {
        weekday: 'short',
        month: 'short',
        day: 'numeric',
      })
    : 'no date';
  return (
    <li className="px-5 py-3 flex items-center gap-4">
      <div className="text-xs text-gray-500 tabular-nums w-24 flex-none">{when}</div>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-ink truncate">{summary}</div>
        <div className="text-xs text-gray-500 mt-0.5">
          {post.target_platforms.length} platform
          {post.target_platforms.length === 1 ? '' : 's'}
        </div>
      </div>
      <Pill tone={postStatusTone(post.status)} className="!text-[10px] !px-1.5 !py-0 flex-none">
        {post.status.replace('_', ' ')}
      </Pill>
    </li>
  );
}

function postStatusTone(s: PostStatus): PillTone {
  switch (s) {
    case 'draft':
      return 'gray';
    case 'internal_review':
      return 'blue';
    case 'client_review':
      return 'amber';
    case 'approved':
    case 'scheduled':
    case 'posted':
      return 'green';
    case 'rejected':
      return 'red';
    default:
      return 'gray';
  }
}

function ActivityRowView({ item }: { item: ActivityItem }) {
  const ago = relativeTime(item.occurred_at);
  return (
    <li className="px-4 py-3 flex items-start gap-4 hover:bg-gray-50 transition-colors">
      <div className="text-xs text-gray-500 w-20 flex-none tabular-nums pt-0.5">{ago}</div>
      <div className="min-w-0 flex-1">
        <div className="text-sm text-ink">{item.label}</div>
        {item.detail && <div className="text-xs text-gray-500 truncate mt-0.5">{item.detail}</div>}
      </div>
      {item.tag ? (
        <Pill tone={tagTone(item.tag.tone)} className="flex-none">
          {item.tag.label}
        </Pill>
      ) : item.actor_label ? (
        <span className="text-xs text-gray-400 flex-none">by {item.actor_label}</span>
      ) : null}
    </li>
  );
}

function DashboardSkeleton() {
  return (
    <div className="space-y-8 animate-pulse">
      <div className="flex items-center gap-4">
        <div className="h-14 w-14 rounded-lg bg-gray-200" />
        <div className="flex-1 space-y-2">
          <div className="h-6 w-48 bg-gray-200 rounded" />
          <div className="h-3 w-32 bg-gray-100 rounded" />
        </div>
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-24 rounded-xl bg-white border border-gray-200" />
        ))}
      </div>
    </div>
  );
}

function tagTone(tone: string): PillTone {
  switch (tone) {
    case 'success':
      return 'green';
    case 'danger':
      return 'red';
    case 'info':
      return 'blue';
    default:
      return 'gray';
  }
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const secs = Math.floor(ms / 1000);
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function formatSentiment(pts: number): string {
  if (pts > 0) return `+${pts}`;
  return pts.toString();
}

function formatReach(n: number): string {
  if (n <= 0) return '—';
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toString();
}

function PastReports({ reports }: { reports: ReportSummary[] }) {
  const reportTone: Record<ReportSummary['status'], PillTone> = {
    draft: 'gray',
    processing: 'amber',
    ready: 'green',
    failed: 'red',
  };
  return (
    <section>
      <Eyebrow className="mb-3">Past reports</Eyebrow>
      <ul className="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100 overflow-hidden">
        {reports.map((r) => {
          // Ready reports open the rendered preview; everything else (draft / processing /
          // failed) goes back to the builder where the user can finish or retry.
          const href = r.status === 'ready' ? `/reports/${r.id}/preview` : `/reports/${r.id}`;
          return (
            <li key={r.id}>
              <Link
                to={href}
                className="flex items-center gap-3 px-4 py-3 hover:bg-gray-50 transition-colors"
              >
                <Pill tone={reportTone[r.status]}>{r.status}</Pill>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-ink truncate">{r.title}</div>
                  <div className="text-xs text-gray-500 mt-0.5">
                    {formatPeriod(r.period_start, r.period_end)} ·{' '}
                    {r.generated_at
                      ? `generated ${relativeTime(r.generated_at)}`
                      : `created ${relativeTime(r.created_at)}`}
                  </div>
                </div>
                <span className="text-xs text-gray-400 flex-none">Open ›</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
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
