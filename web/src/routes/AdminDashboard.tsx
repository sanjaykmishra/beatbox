import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { Eyebrow } from '../components/ui';
import { ApiError, api } from '../lib/api';

export function AdminDashboard() {
  const dash = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: api.adminDashboard,
    retry: false,
  });

  if (dash.isLoading) {
    return (
      <BrowserFrame
        crumbs={[
          { label: 'beat.app', to: '/clients' },
          { label: 'admin' },
        ]}
      >
        <p className="text-gray-500">Loading…</p>
      </BrowserFrame>
    );
  }
  if (dash.error) {
    const forbidden = dash.error instanceof ApiError && dash.error.status === 403;
    return (
      <BrowserFrame
        crumbs={[
          { label: 'beat.app', to: '/clients' },
          { label: 'admin' },
        ]}
      >
        <div className="text-center py-16">
          <h1 className="text-xl font-semibold text-ink">
            {forbidden ? 'Admin only' : 'Couldn\'t load dashboard'}
          </h1>
          <p className="text-sm text-gray-500 mt-2">
            {forbidden
              ? 'Your account is not on the founder allowlist.'
              : (dash.error as Error).message}
          </p>
          <Link
            to="/clients"
            className="inline-block mt-5 rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50"
          >
            Back to clients
          </Link>
        </div>
      </BrowserFrame>
    );
  }
  const d = dash.data!;
  const totalCost = d.workspace_costs.reduce((acc, w) => acc + (w.cost_usd ?? 0), 0);
  const totalExtractions = d.workspace_costs.reduce((acc, w) => acc + w.extractions, 0);
  const totalReports = d.workspace_costs.reduce((acc, w) => acc + w.reports, 0);

  return (
    <BrowserFrame
      crumbs={[
        { label: 'beat.app', to: '/clients' },
        { label: 'admin' },
        { label: 'dashboard' },
      ]}
    >
      <div className="space-y-7">
        <div>
          <h1 className="text-2xl font-semibold tracking-tightish text-ink">Founder dashboard</h1>
          <p className="text-sm text-gray-500 mt-1">
            Last 30 days · pulled from <code className="font-mono text-xs">activity_events</code>
          </p>
        </div>

        <section className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          <Stat label="Extractions · 30d" value={totalExtractions.toLocaleString()} />
          <Stat label="Reports · 30d" value={totalReports.toLocaleString()} />
          <Stat label="Cost · 30d" value={`$${totalCost.toFixed(2)}`} />
          <Stat
            label="P95 extraction · 7d"
            value={d.p95_extraction_ms != null ? `${(d.p95_extraction_ms / 1000).toFixed(1)}s` : '—'}
          />
        </section>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <Card title="Daily extractions">
            <Sparkline points={d.daily_extractions} color="#4f46e5" />
          </Card>
          <Card title="Daily reports generated">
            <Sparkline points={d.daily_reports} color="#059669" />
          </Card>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <Card title="Workspace cost · 30d">
            {d.workspace_costs.length === 0 ? (
              <Empty>No activity yet.</Empty>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-gray-500 uppercase tracking-wider">
                  <tr>
                    <th className="py-2 font-semibold">Workspace</th>
                    <th className="py-2 font-semibold tabular-nums text-right">Extr.</th>
                    <th className="py-2 font-semibold tabular-nums text-right">Reports</th>
                    <th className="py-2 font-semibold tabular-nums text-right">Cost</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {d.workspace_costs.map((w) => (
                    <tr key={w.workspace_id}>
                      <td className="py-2 truncate max-w-[14rem]" title={w.workspace_name}>
                        {w.workspace_name}
                      </td>
                      <td className="py-2 tabular-nums text-right">
                        {w.extractions.toLocaleString()}
                      </td>
                      <td className="py-2 tabular-nums text-right">
                        {w.reports.toLocaleString()}
                      </td>
                      <td className="py-2 tabular-nums text-right">
                        {w.cost_usd > 0 ? `$${w.cost_usd.toFixed(2)}` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
          <Card title="Top extraction errors · 30d">
            {d.top_errors.length === 0 ? (
              <Empty>No failures in the window.</Empty>
            ) : (
              <ul className="text-sm space-y-2">
                {d.top_errors.map((e) => (
                  <li
                    key={e.error_class}
                    className="flex items-center justify-between gap-3 py-1.5 border-b border-gray-100 last:border-b-0"
                  >
                    <span className="font-mono text-xs text-gray-700 truncate">
                      {e.error_class}
                    </span>
                    <span className="tabular-nums text-gray-900 font-medium">{e.count}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </BrowserFrame>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4">
      <Eyebrow>{label}</Eyebrow>
      <div className="mt-1.5 text-3xl font-semibold tabular-nums tracking-tightish text-ink">
        {value}
      </div>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <Eyebrow className="mb-3">{title}</Eyebrow>
      <div className="bg-white border border-gray-200 rounded-xl p-5">{children}</div>
    </div>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-gray-500 py-3">{children}</p>;
}

/** Tiny inline SVG sparkline. No deps. */
function Sparkline({
  points,
  color,
}: {
  points: { day: string; count: number }[];
  color: string;
}) {
  if (points.length === 0) {
    return <Empty>No data.</Empty>;
  }
  const w = 480;
  const h = 80;
  const max = Math.max(1, ...points.map((p) => p.count));
  const stepX = points.length > 1 ? w / (points.length - 1) : 0;
  const path = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${(i * stepX).toFixed(1)},${(h - (p.count / max) * (h - 8) - 4).toFixed(1)}`)
    .join(' ');
  const total = points.reduce((acc, p) => acc + p.count, 0);
  return (
    <div>
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-20">
        <path d={path} fill="none" stroke={color} strokeWidth="2" />
        {points.map((p, i) => (
          <circle
            key={p.day}
            cx={(i * stepX).toFixed(1)}
            cy={(h - (p.count / max) * (h - 8) - 4).toFixed(1)}
            r="2"
            fill={color}
          >
            <title>{`${p.day}: ${p.count}`}</title>
          </circle>
        ))}
      </svg>
      <div className="mt-2 flex items-center justify-between text-xs text-gray-500 tabular-nums">
        <span>{points[0].day}</span>
        <span>{total.toLocaleString()} total</span>
        <span>{points[points.length - 1].day}</span>
      </div>
    </div>
  );
}
