import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
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

  if (dashboard.isLoading) return <p className="text-gray-500">Loading…</p>;
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

  return (
    <div className="space-y-6">
      <nav className="text-sm text-gray-500">
        <Link to="/clients" className="hover:text-gray-900">
          Clients
        </Link>{' '}
        › <span className="text-gray-900">{d.client.name}</span>
      </nav>

      {/* Header strip */}
      <div className="flex items-center gap-4">
        {d.client.logo_url ? (
          <img src={d.client.logo_url} alt="" className="h-12 w-12 rounded object-cover" />
        ) : (
          <div
            className="h-12 w-12 rounded"
            style={{ background: d.client.primary_color ? `#${d.client.primary_color}` : '#E5E7EB' }}
          />
        )}
        <div className="flex-1">
          <h1 className="text-2xl font-semibold tracking-tight">{d.client.name}</h1>
          {d.client.default_cadence && (
            <p className="text-xs text-gray-500 mt-1">{d.client.default_cadence}</p>
          )}
        </div>
        <Link
          to={`/clients/${id}/edit`}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:border-gray-400"
        >
          Edit
        </Link>
        <Link
          to={`/clients/${id}/context`}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:border-gray-400"
        >
          Context
        </Link>
        <Link
          to={`/clients/${id}/reports/new`}
          className="rounded bg-gray-900 text-white px-3 py-1.5 text-sm font-medium hover:bg-gray-800"
        >
          + New report
        </Link>
      </div>

      {/* Stats row */}
      {isNewClient ? (
        <StatsPlaceholder />
      ) : (
        <section className="grid grid-cols-4 gap-3">
          <StatTile
            label="Coverage · 30d"
            value={d.stats_30d.coverage_count.value}
            deltaLabel={d.stats_30d.coverage_count.delta_label}
          />
          <StatTile
            label="Tier 1"
            value={d.stats_30d.tier_1_count.value}
            deltaLabel={d.stats_30d.tier_1_count.delta_label}
          />
          <StatTile
            label="Sentiment"
            value={d.stats_30d.sentiment.value}
            deltaLabel={d.stats_30d.sentiment.delta_label}
          />
          <StatTile
            label="Reach"
            value={formatReach(d.stats_30d.reach.value)}
            deltaLabel={d.stats_30d.reach.delta_label}
          />
        </section>
      )}

      {/* Two-column: Needs attention / Coming up */}
      <section className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Column title="Needs attention">
          {isNewClient ? (
            <SetupChecklist clientId={id} onDismiss={() => dismiss.mutate()} />
          ) : hasHealthyOnly ? (
            <HealthyEmptyState />
          ) : visibleAlerts.length === 0 ? (
            <HealthyEmptyState />
          ) : (
            visibleAlerts.map((a) => <AlertCardView key={a.alert_type} alert={a} />)
          )}
        </Column>
        <Column title="Coming up">
          {isNewClient ? null : d.coming_up.length === 0 ? (
            <p className="text-sm text-gray-500">Nothing scheduled.</p>
          ) : (
            d.coming_up.map((c, i) => (
              <ComingUpItemView key={`${c.kind}-${i}`} item={c} />
            ))
          )}
        </Column>
      </section>

      {/* Recent activity */}
      {!isNewClient && (
        <section>
          <div className="flex items-baseline justify-between mb-2">
            <h2 className="text-sm font-medium text-gray-700">Recent activity</h2>
          </div>
          {d.recent_activity.length === 0 ? (
            <p className="text-sm text-gray-500">No activity in the last 14 days.</p>
          ) : (
            <ul className="bg-white border border-gray-200 rounded divide-y divide-gray-200">
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
      <h2 className="text-sm font-medium text-gray-700 mb-2">{title}</h2>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function StatTile({
  label,
  value,
  deltaLabel,
}: {
  label: string;
  value: number | string;
  deltaLabel: string;
}) {
  const up = deltaLabel.startsWith('↑');
  const down = deltaLabel.startsWith('↓');
  const cls = up ? 'text-emerald-700' : down ? 'text-red-700' : 'text-gray-500';
  return (
    <div className="bg-white border border-gray-200 rounded p-4">
      <div className="text-xs uppercase tracking-wide text-gray-500">{label}</div>
      <div className="text-2xl font-semibold mt-1">{value}</div>
      <div className={`text-xs mt-1 ${cls}`}>{deltaLabel}</div>
    </div>
  );
}

function StatsPlaceholder() {
  return (
    <section className="grid grid-cols-4 gap-3">
      {['Coverage · 30d', 'Tier 1', 'Sentiment', 'Reach'].map((label) => (
        <div
          key={label}
          className="bg-white border border-gray-200 rounded p-4 text-gray-300"
        >
          <div className="text-xs uppercase tracking-wide text-gray-400">{label}</div>
          <div className="text-2xl font-semibold mt-1">—</div>
        </div>
      ))}
    </section>
  );
}

function AlertCardView({ alert }: { alert: AlertCard }) {
  const tone = severityClasses(alert.severity);
  return (
    <div className={`bg-white rounded border p-4 ${tone.border}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className={`text-xs font-medium ${tone.eyebrow}`}>{alert.badge_label}</div>
          <div className="font-medium text-gray-900 mt-1">{alert.card_title}</div>
          {alert.card_subtitle && (
            <div className="text-sm text-gray-600 mt-1">{alert.card_subtitle}</div>
          )}
        </div>
        {alert.card_action_path && (
          <Link
            to={alert.card_action_path}
            className="rounded bg-gray-900 text-white text-sm px-3 py-1.5 font-medium hover:bg-gray-800 flex-none"
          >
            {alert.card_action_label ?? 'Open'}
          </Link>
        )}
      </div>
    </div>
  );
}

function HealthyEmptyState() {
  return (
    <div className="bg-white rounded border border-emerald-200 p-4">
      <div className="text-xs font-medium text-emerald-700">All caught up</div>
      <div className="text-sm text-gray-700 mt-1">Nothing here needs your attention.</div>
    </div>
  );
}

function SetupChecklist({ clientId, onDismiss }: { clientId: string; onDismiss: () => void }) {
  return (
    <div className="bg-white rounded border border-gray-200 p-4 space-y-3">
      <div className="text-sm font-medium text-gray-700">Get this client set up</div>
      <ol className="space-y-2 text-sm">
        <li className="flex items-center justify-between gap-3">
          <span>1. Add client context (key messages, style notes)</span>
          <Link
            to={`/clients/${clientId}/context`}
            className="text-gray-900 underline text-sm"
          >
            Add
          </Link>
        </li>
        <li className="flex items-center justify-between gap-3">
          <span>2. Set a default reporting cadence</span>
          <Link to={`/clients/${clientId}/edit`} className="text-gray-900 underline text-sm">
            Set
          </Link>
        </li>
        <li className="flex items-center justify-between gap-3">
          <span>3. Paste your first coverage URLs</span>
          <Link
            to={`/clients/${clientId}/reports/new`}
            className="text-gray-900 underline text-sm"
          >
            Start
          </Link>
        </li>
      </ol>
      <div className="text-right">
        <button
          onClick={onDismiss}
          className="text-xs text-gray-500 hover:text-gray-900 underline"
        >
          I'll do it later
        </button>
      </div>
    </div>
  );
}

function ComingUpItemView({ item }: { item: { kind: string; title: string; subtitle: string | null; path: string | null } }) {
  const inner = (
    <div className="bg-white rounded border border-gray-200 p-3">
      <div className="font-medium text-sm text-gray-900">{item.title}</div>
      {item.subtitle && <div className="text-xs text-gray-500 mt-0.5">{item.subtitle}</div>}
    </div>
  );
  return item.path ? (
    <Link to={item.path} className="block hover:bg-gray-50 rounded">
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
    <li className="px-4 py-3 flex items-start gap-4">
      <div className="text-xs text-gray-500 w-24 flex-none">{ago}</div>
      <div className="min-w-0 flex-1">
        <div className="text-sm text-gray-900">{item.label}</div>
        {item.detail && <div className="text-xs text-gray-500 truncate mt-0.5">{item.detail}</div>}
      </div>
      {item.tag && (
        <span className={`text-xs rounded px-2 py-0.5 font-medium flex-none ${tone}`}>
          {item.tag.label}
        </span>
      )}
    </li>
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
