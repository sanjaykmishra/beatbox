import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../lib/api';

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

  return (
    <div className="space-y-8">
      <div className="flex items-end justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Clients</h1>
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
            <li key={c.id}>
              <Link
                to={`/clients/${c.id}`}
                className="flex items-center gap-4 px-4 py-3 hover:bg-gray-50"
              >
                {c.logo_url ? (
                  <img src={c.logo_url} alt="" className="h-9 w-9 rounded object-cover" />
                ) : (
                  <div
                    className="h-9 w-9 rounded"
                    style={{ background: c.primary_color ? `#${c.primary_color}` : '#E5E7EB' }}
                  />
                )}
                <div className="flex-1">
                  <div className="font-medium text-gray-900">{c.name}</div>
                  <div className="text-xs text-gray-500">
                    Added {new Date(c.created_at).toLocaleDateString()}
                  </div>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
