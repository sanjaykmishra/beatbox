import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { Avatar } from '../components/Avatar';
import { api, type AlertCard, type ActivityItem, type Severity } from '../lib/api';

/**
 * Per docs/16-client-dashboard.md. Renders one of four states based on the alert set.
 * Layout: header strip, stats row, two-column attention/coming-up, recent activity.
 */
export function ClientDashboard() {
  const { id = '' } = useParams();
  const qc = useQueryClient();

  const dashboard = useQuery({
    queryKey: ['dashboard', id],
    queryFn: () => api.getClientDashboard(id),
  });

  const dismiss = useMutation({
    mutationFn: () => api.dismissSetup(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dashboard', id] }),
  });

  if (dashboard.isLoading) return <DashboardSkeleton />;
  if (dashboard.error || !dashboard.data) {
    return <p className="text-red-600">Failed to load client.</p>;
  }
  const d = dashboard.data;
  const setupAlert = d.alerts.find((a) => a.alert_type === 'client.setup_incomplete');
  const isNewClient = !!setupAlert && d.client.setup_dismissed_at === null;
  const visibleAlerts = d.alerts.filter(
    (a) => a.alert_type !== 'client.setup_incomplete' && a.alert_type !== 'client.healthy',
  );
  const hasHealthyOnly = d.alerts.length === 1 && d.alerts[0].alert_type === 'client.healthy';
  const attentionCount = visibleAlerts.length;

  return (
    <div className="space-y-8">
      <nav className="text-sm text-gray-500">
        <Link to="/clients" className="hover:text-gray-900 transition-colors">
          Clients
        </Link>
        <span className="mx-1.5 text-gray-300">›</span>
        <span className="text-gray-900">{d.client.name}</span>
      </nav>

      {/* Header strip */}
      <div className="flex items-center gap-4">
        <Avatar
          name={d.client.name}
          logoUrl={d.client.logo_url}
          primaryColor={d.client.primary_color}
          size="lg"
        />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-2xl font-semibold tracking-tight text-gray-900">{d.client.name}</h1>
            {attentionCount > 0 && !isNewClient && (
              <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-amber-100 text-amber-800">
                {attentionCount} item{attentionCount === 1 ? '' : 's'} need attention
              </span>
            )}
            {hasHealthyOnly && (
              <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800">
                All caught up
              </span>
            )}
          </div>
          {d.client.default_cadence && (
            <p className="text-sm text-gray-500 mt-1 capitalize">
              {d.client.default_cadence} cadence
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 flex-none">
          <Link
            to={`/clients/${id}/edit`}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50 transition-colors"
          >
            Edit
          </Link>
          <Link
            to={`/clients/${id}/context`}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50 transition-colors"
          >
            Context
          </Link>
          <Link
            to={`/clients/${id}/reports/new`}
            className="rounded-md bg-gray-900 text-white px-3 py-1.5 text-sm font-medium hover:bg-gray-800 transition-colors shadow-sm"
          >
            + New report
          </Link>
        </div>
      </div>

      {/* Stats row */}
      <section className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <StatTile
          label="Coverage · 30d"
          value={isNewClient ? null : d.stats_30d.coverage_count.value}
          deltaLabel={isNewClient ? null : d.stats_30d.coverage_count.delta_label}
          accent
        />
        <StatTile
          label="Tier 1"
          value={isNewClient ? null : d.stats_30d.tier_1_count.value}
          deltaLabel={isNewClient ? null : d.stats_30d.tier_1_count.delta_label}
        />
        <StatTile
          label="Sentiment"
          value={isNewClient ? null : d.stats_30d.sentiment.value}
          deltaLabel={isNewClient ? null : d.stats_30d.sentiment.delta_label}
        />
        <StatTile
          label="Reach"
          value={isNewClient ? null : formatReach(d.stats_30d.reach.value)}
          deltaLabel={isNewClient ? null : d.stats_30d.reach.delta_label}
        />
      </section>

      {/* Two-column: Needs attention / Coming up */}
      <section className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <Column title="Needs attention">
          {isNewClient ? (
            <SetupChecklist clientId={id} onDismiss={() => dismiss.mutate()} />
          ) : visibleAlerts.length === 0 ? (
            <HealthyEmptyState clientName={d.client.name} />
          ) : (
            visibleAlerts.map((a) => <AlertCardView key={a.alert_type} alert={a} />)
          )}
        </Column>
        <Column title="Coming up">
          {isNewClient ? (
            <ComingUpEmpty
              copy="Schedule a cadence and add important dates in the client's context to see what's next."
              action={{ label: 'Add context →', to: `/clients/${id}/context` }}
            />
          ) : d.coming_up.length === 0 ? (
            <ComingUpEmpty copy="Nothing scheduled in the next few weeks." />
          ) : (
            d.coming_up.map((c, i) => <ComingUpItemView key={`${c.kind}-${i}`} item={c} />)
          )}
        </Column>
      </section>

      {/* Recent activity */}
      {!isNewClient && (
        <section>
          <div className="flex items-baseline justify-between mb-3">
            <h2 className="text-xs font-semibold text-gray-500 tracking-wider uppercase">
              Recent activity
            </h2>
          </div>
          {d.recent_activity.length === 0 ? (
            <div className="bg-white border border-gray-200 rounded-lg p-6 text-sm text-gray-500 text-center">
              No activity in the last 14 days.
            </div>
          ) : (
            <ul className="bg-white border border-gray-200 rounded-lg divide-y divide-gray-100 overflow-hidden">
              {d.recent_activity.map((e, i) => (
                <ActivityRowView key={`${e.occurred_at}-${i}`} item={e} />
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}

function Column({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h2 className="text-xs font-semibold text-gray-500 tracking-wider uppercase mb-3">{title}</h2>
      <div className="space-y-3">{children}</div>
    </div>
  );
}

function StatTile({
  label,
  value,
  deltaLabel,
  accent,
}: {
  label: string;
  value: number | string | null;
  deltaLabel: string | null;
  accent?: boolean;
}) {
  const empty = value === null;
  const up = !empty && deltaLabel?.startsWith('↑');
  const down = !empty && deltaLabel?.startsWith('↓');
  const cls = up ? 'text-emerald-700' : down ? 'text-red-700' : 'text-gray-500';
  return (
    <div
      className={`bg-white border border-gray-200 rounded-lg p-4 ${
        accent ? 'border-t-2 border-t-gray-900' : ''
      }`}
    >
      <div className="text-[10px] font-semibold uppercase tracking-wider text-gray-500">
        {label}
      </div>
      <div
        className={`mt-2 font-semibold tabular-nums tracking-tight ${
          empty ? 'text-2xl text-gray-300' : 'text-3xl text-gray-900'
        }`}
      >
        {empty ? '—' : value}
      </div>
      {!empty && deltaLabel && deltaLabel !== 'stable' && (
        <div className={`text-xs mt-1.5 font-medium ${cls}`}>{deltaLabel}</div>
      )}
      {!empty && deltaLabel === 'stable' && (
        <div className="text-xs mt-1.5 text-gray-400">— stable</div>
      )}
    </div>
  );
}

function AlertCardView({ alert }: { alert: AlertCard }) {
  const tone = severityClasses(alert.severity);
  return (
    <div className={`bg-white rounded-lg border p-4 ${tone.border}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className={`text-[11px] font-semibold uppercase tracking-wider ${tone.eyebrow}`}>
            {alert.badge_label}
          </div>
          <div className="font-semibold text-gray-900 mt-1">{alert.card_title}</div>
          {alert.card_subtitle && (
            <div className="text-sm text-gray-600 mt-1.5">{alert.card_subtitle}</div>
          )}
        </div>
        {alert.card_action_path && (
          <Link
            to={alert.card_action_path}
            className="rounded-md bg-gray-900 text-white text-sm px-3 py-1.5 font-medium hover:bg-gray-800 flex-none transition-colors"
          >
            {alert.card_action_label ?? 'Open'}
          </Link>
        )}
      </div>
    </div>
  );
}

function HealthyEmptyState({ clientName }: { clientName: string }) {
  return (
    <div className="bg-white rounded-lg border border-emerald-200 p-5">
      <div className="flex items-center gap-2">
        <span
          className="inline-flex items-center justify-center h-6 w-6 rounded-full bg-emerald-100 text-emerald-700 text-sm"
          aria-hidden
        >
          ✓
        </span>
        <div className="text-sm font-semibold text-emerald-800">All caught up</div>
      </div>
      <div className="text-sm text-gray-600 mt-2">
        Nothing about {clientName} needs your attention right now.
      </div>
    </div>
  );
}

function SetupChecklist({ clientId, onDismiss }: { clientId: string; onDismiss: () => void }) {
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-5 space-y-4">
      <div>
        <div className="text-sm font-semibold text-gray-900">Get this client set up</div>
        <div className="text-xs text-gray-500 mt-0.5">
          Three quick steps to get the most out of Beat.
        </div>
      </div>
      <ol className="space-y-3">
        <SetupStep
          n={1}
          title="Add client context"
          subtitle="Key messages, style notes, important dates."
          to={`/clients/${clientId}/context`}
          actionLabel="Add"
        />
        <SetupStep
          n={2}
          title="Set a default reporting cadence"
          subtitle="So we can flag overdue reports."
          to={`/clients/${clientId}/edit`}
          actionLabel="Set"
        />
        <SetupStep
          n={3}
          title="Paste your first coverage URLs"
          subtitle="Watch them extract live."
          to={`/clients/${clientId}/reports/new`}
          actionLabel="Start"
        />
      </ol>
      <div className="text-right pt-3 border-t border-gray-100">
        <button
          onClick={onDismiss}
          className="text-xs text-gray-500 hover:text-gray-900 transition-colors"
        >
          I'll do it later
        </button>
      </div>
    </div>
  );
}

function SetupStep({
  n,
  title,
  subtitle,
  to,
  actionLabel,
}: {
  n: number;
  title: string;
  subtitle: string;
  to: string;
  actionLabel: string;
}) {
  return (
    <li className="flex items-start gap-3">
      <span className="flex-none flex items-center justify-center h-6 w-6 rounded-full bg-gray-100 text-gray-700 text-xs font-semibold tabular-nums">
        {n}
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium text-gray-900">{title}</div>
        <div className="text-xs text-gray-500 mt-0.5">{subtitle}</div>
      </div>
      <Link to={to} className="text-sm font-medium text-gray-900 hover:underline flex-none">
        {actionLabel} →
      </Link>
    </li>
  );
}

function ComingUpEmpty({
  copy,
  action,
}: {
  copy: string;
  action?: { label: string; to: string };
}) {
  return (
    <div className="bg-white border border-dashed border-gray-300 rounded-lg p-5 text-center space-y-3">
      <p className="text-sm text-gray-500">{copy}</p>
      {action && (
        <Link to={action.to} className="text-sm font-medium text-gray-900 hover:underline">
          {action.label}
        </Link>
      )}
    </div>
  );
}

function ComingUpItemView({
  item,
}: {
  item: { kind: string; title: string; subtitle: string | null; path: string | null };
}) {
  const inner = (
    <div className="bg-white rounded-lg border border-gray-200 p-4 hover:border-gray-300 transition-colors">
      <div className="font-medium text-sm text-gray-900">{item.title}</div>
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

function ActivityRowView({ item }: { item: ActivityItem }) {
  const ago = relativeTime(item.occurred_at);
  const tone = item.tag ? toneClass(item.tag.tone) : '';
  return (
    <li className="px-4 py-3 flex items-start gap-4 hover:bg-gray-50 transition-colors">
      <div className="text-xs text-gray-500 w-20 flex-none tabular-nums pt-0.5">{ago}</div>
      <div className="min-w-0 flex-1">
        <div className="text-sm text-gray-900">{item.label}</div>
        {item.detail && <div className="text-xs text-gray-500 truncate mt-0.5">{item.detail}</div>}
      </div>
      {item.tag && (
        <span
          className={`text-[10px] rounded-full px-2 py-0.5 font-medium flex-none uppercase tracking-wider ${tone}`}
        >
          {item.tag.label}
        </span>
      )}
    </li>
  );
}

function DashboardSkeleton() {
  return (
    <div className="space-y-8 animate-pulse">
      <div className="h-4 w-32 bg-gray-200 rounded" />
      <div className="flex items-center gap-4">
        <div className="h-14 w-14 rounded-lg bg-gray-200" />
        <div className="flex-1 space-y-2">
          <div className="h-6 w-48 bg-gray-200 rounded" />
          <div className="h-3 w-32 bg-gray-100 rounded" />
        </div>
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-24 rounded-lg bg-white border border-gray-200" />
        ))}
      </div>
    </div>
  );
}

function toneClass(tone: string): string {
  switch (tone) {
    case 'success':
      return 'bg-emerald-100 text-emerald-800';
    case 'danger':
      return 'bg-red-100 text-red-800';
    case 'info':
      return 'bg-blue-100 text-blue-800';
    default:
      return 'bg-gray-100 text-gray-700';
  }
}

function severityClasses(s: Severity): { border: string; eyebrow: string } {
  switch (s) {
    case 'red':
      return { border: 'border-red-200', eyebrow: 'text-red-700' };
    case 'amber':
      return { border: 'border-amber-200', eyebrow: 'text-amber-700' };
    case 'blue':
      return { border: 'border-blue-200', eyebrow: 'text-blue-700' };
    default:
      return { border: 'border-gray-200', eyebrow: 'text-gray-500' };
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
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function formatReach(n: number): string {
  if (n <= 0) return '—';
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toString();
}
