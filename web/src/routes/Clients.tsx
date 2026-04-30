import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Avatar } from '../components/Avatar';
import { BrowserFrame } from '../components/BrowserFrame';
import { Pill, PrimaryButton, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, ApiError, type ClientListItem, type Severity } from '../lib/api';

export function Clients() {
  const qc = useQueryClient();
  const { workspace } = useAuth();
  const list = useQuery({ queryKey: ['clients'], queryFn: api.listClients });
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: (n: string) => api.createClient({ name: n }),
    onSuccess: () => {
      setName('');
      setAdding(false);
      void qc.invalidateQueries({ queryKey: ['clients'] });
    },
    onError: (err) =>
      setError(err instanceof ApiError ? err.message : 'Failed to create client'),
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (name.trim()) create.mutate(name.trim());
  }

  const summary = list.data?.workspace_summary;
  const slug = workspace?.slug ?? 'workspace';

  return (
    <BrowserFrame crumbs={[{ label: `${slug}.beat.app` }, { label: 'clients' }]}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tightish text-ink">Clients</h1>
          {summary && (
            <p className="text-sm text-gray-500 mt-1">
              {summary.total_clients} active
              <span className="mx-1.5 text-gray-300">·</span>
              {summary.total_attention_items > 0
                ? `${summary.total_attention_items} item${summary.total_attention_items === 1 ? '' : 's'} needing attention across all clients`
                : 'all caught up'}
            </p>
          )}
        </div>
        <PrimaryButton onClick={() => setAdding((v) => !v)}>
          {adding ? 'Cancel' : '+ Add client'}
        </PrimaryButton>
      </div>

      {adding && (
        <form
          onSubmit={onSubmit}
          className="mt-5 bg-gray-50 rounded-lg border border-gray-200 p-3 flex gap-2"
        >
          <input
            autoFocus
            className="flex-1 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10 transition"
            placeholder="New client name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <button
            type="submit"
            disabled={create.isPending || !name.trim()}
            className="ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {create.isPending ? 'Adding…' : 'Add'}
          </button>
        </form>
      )}
      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

      <div className="mt-6 -mx-8 border-t border-gray-200">
        {list.isLoading && (
          <ul className="divide-y divide-gray-100">
            {[0, 1, 2].map((i) => (
              <li key={i} className="px-8 py-4 flex items-center gap-4 animate-pulse">
                <div className="h-11 w-11 rounded-lg bg-gray-200" />
                <div className="flex-1 space-y-2">
                  <div className="h-3.5 w-40 bg-gray-200 rounded" />
                  <div className="h-3 w-24 bg-gray-100 rounded" />
                </div>
              </li>
            ))}
          </ul>
        )}
        {list.data && list.data.items.length === 0 && (
          <div className="px-8 py-16 text-center">
            <p className="text-sm text-gray-500">No clients yet. Add one above to get started.</p>
          </div>
        )}
        {list.data && list.data.items.length > 0 && (
          <ul className="divide-y divide-gray-100">
            {list.data.items.map((c) => (
              <ClientRow key={c.id} client={c} />
            ))}
          </ul>
        )}
      </div>

      <div className="-mx-8 -mb-8 mt-0 px-8 py-3 bg-gray-50 border-t border-gray-200 text-[12px] text-gray-500 flex items-center gap-3 flex-wrap">
        <span className="font-medium text-gray-600">Badge legend:</span>
        <LegendItem tone="red" label="red" desc="blocking · time-sensitive" />
        <LegendItem tone="amber" label="amber" desc="needs review" />
        <LegendItem tone="blue" label="blue" desc="informational" />
        <LegendItem tone="green" label="green" desc="healthy state" />
      </div>
    </BrowserFrame>
  );
}

function LegendItem({ tone, label, desc }: { tone: PillTone; label: string; desc: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <Pill tone={tone}>{label}</Pill>
      <span className="text-gray-500">{desc}</span>
    </span>
  );
}

function ClientRow({ client }: { client: ClientListItem }) {
  const a = client.alerts_summary;
  const allCaughtUp = a.total_score === 0;
  const onlySetup =
    a.top_badges.length === 1 && a.top_badges[0].alert_type === 'client.setup_incomplete';

  const meta: string[] = [];
  if (client.default_cadence) meta.push(client.default_cadence);
  meta.push(`added ${formatRelative(client.created_at)}`);

  return (
    <li>
      <Link
        to={`/clients/${client.id}`}
        className="flex items-center gap-4 px-8 py-3.5 hover:bg-gray-50 transition-colors"
      >
        <Avatar
          name={client.name}
          logoUrl={client.logo_url}
          primaryColor={client.primary_color}
          size="md"
        />
        <div className="flex-1 min-w-0">
          <div className="font-medium text-ink truncate">{client.name}</div>
          <div className="text-xs text-gray-500 mt-0.5 capitalize">
            {meta.map((m, i) => (
              <span key={i}>
                {i > 0 && <span className="mx-1.5 text-gray-300 normal-case">·</span>}
                {m}
              </span>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-1.5 flex-none">
          {allCaughtUp ? (
            <Pill tone="green">All caught up</Pill>
          ) : onlySetup ? (
            <Pill tone="blue">Setup</Pill>
          ) : (
            <>
              {a.top_badges
                .filter((b) => b.alert_type !== 'client.setup_incomplete')
                .map((b) => (
                  <Pill key={b.alert_type} tone={severityTone(b.severity)} title={b.alert_type}>
                    {b.label}
                  </Pill>
                ))}
              {a.overflow_count > 0 && <Pill tone="gray">+{a.overflow_count}</Pill>}
            </>
          )}
          <span className="text-gray-300 ml-2 text-lg leading-none">›</span>
        </div>
      </Link>
    </li>
  );
}

function severityTone(s: Severity): PillTone {
  switch (s) {
    case 'red':
      return 'red';
    case 'amber':
      return 'amber';
    case 'blue':
      return 'blue';
    case 'green':
      return 'green';
  }
}

function formatRelative(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const days = Math.floor(ms / 86_400_000);
  if (days <= 0) {
    const hours = Math.floor(ms / 3_600_000);
    if (hours <= 0) return 'just now';
    return `${hours}h ago`;
  }
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}
