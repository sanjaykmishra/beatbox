import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, type ClientListItem, type Severity } from '../lib/api';

export function Clients() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['clients'], queryFn: api.listClients });
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: (n: string) => api.createClient({ name: n }),
    onSuccess: () => {
      setName('');
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

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Clients</h1>
          {summary && (
            <p className="text-sm text-gray-500 mt-1">
              {summary.total_clients} active ·{' '}
              {summary.total_attention_items > 0
                ? `${summary.total_attention_items} item${summary.total_attention_items === 1 ? '' : 's'} needing attention across all clients`
                : 'all caught up'}
            </p>
          )}
        </div>
      </div>

      <form onSubmit={onSubmit} className="bg-white rounded border border-gray-200 p-4 flex gap-3">
        <input
          className="flex-1 rounded border border-gray-300 px-3 py-2 outline-none focus:border-gray-900 focus:ring-1 focus:ring-gray-900"
          placeholder="New client name"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button
          type="submit"
          disabled={create.isPending}
          className="rounded bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-800 disabled:opacity-60"
        >
          {create.isPending ? 'Adding…' : 'Add client'}
        </button>
      </form>
      {error && <p className="text-sm text-red-600">{error}</p>}

      {list.isLoading && <p className="text-gray-500">Loading…</p>}
      {list.data && list.data.items.length === 0 && (
        <div className="bg-white rounded border border-gray-200 p-12 text-center">
          <p className="text-gray-500">No clients yet. Add one above to get started.</p>
        </div>
      )}
      {list.data && list.data.items.length > 0 && (
        <ul className="bg-white rounded border border-gray-200 divide-y divide-gray-200">
          {list.data.items.map((c) => (
            <ClientRow key={c.id} client={c} />
          ))}
        </ul>
      )}
    </div>
  );
}

function ClientRow({ client }: { client: ClientListItem }) {
  const a = client.alerts_summary;
  const allCaughtUp = a.total_score === 0;
  return (
    <li>
      <Link
        to={`/clients/${client.id}`}
        className="flex items-center gap-4 px-4 py-3 hover:bg-gray-50"
      >
        {client.logo_url ? (
          <img src={client.logo_url} alt="" className="h-9 w-9 rounded object-cover" />
        ) : (
          <div
            className="h-9 w-9 rounded"
            style={{ background: client.primary_color ? `#${client.primary_color}` : '#E5E7EB' }}
          />
        )}
        <div className="flex-1 min-w-0">
          <div className="font-medium text-gray-900 truncate">{client.name}</div>
          <div className="text-xs text-gray-500">
            Added {new Date(client.created_at).toLocaleDateString()}
            {client.default_cadence ? ` · ${client.default_cadence}` : ''}
          </div>
        </div>
        <div className="flex items-center gap-1.5 flex-none">
          {allCaughtUp ? (
            <Badge severity="green" label="All caught up" />
          ) : (
            <>
              {a.top_badges.map((b) => (
                <Badge
                  key={b.alert_type}
                  severity={b.severity}
                  label={b.label}
                  title={b.alert_type}
                />
              ))}
              {a.overflow_count > 0 && <Badge severity="overflow" label={`+${a.overflow_count}`} />}
            </>
          )}
        </div>
      </Link>
    </li>
  );
}

function Badge({
  severity,
  label,
  title,
}: {
  severity: Severity | 'overflow';
  label: string;
  title?: string;
}) {
  const cls = badgeClasses(severity);
  return (
    <span
      className={`inline-block rounded font-medium ${cls}`}
      style={{ fontSize: 10, padding: '2px 7px', borderRadius: 3 }}
      title={title}
    >
      {label}
    </span>
  );
}

function badgeClasses(s: Severity | 'overflow'): string {
  switch (s) {
    case 'red':
      return 'bg-red-100 text-red-800';
    case 'amber':
      return 'bg-amber-100 text-amber-800';
    case 'blue':
      return 'bg-blue-100 text-blue-800';
    case 'green':
      return 'bg-emerald-100 text-emerald-800';
    default:
      return 'bg-gray-100 text-gray-700';
  }
}
