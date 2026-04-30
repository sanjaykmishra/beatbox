import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { api, ApiError } from '../lib/api';

function defaultPeriod(): { start: string; end: string } {
  // Last full month relative to today.
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  const end = new Date(now.getFullYear(), now.getMonth(), 0);
  return { start: iso(start), end: iso(end) };
}

function iso(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function NewReport() {
  const { id: clientId = '' } = useParams();
  const navigate = useNavigate();
  const client = useQuery({ queryKey: ['client', clientId], queryFn: () => api.getClient(clientId) });

  const def = defaultPeriod();
  const [title, setTitle] = useState('');
  const [periodStart, setPeriodStart] = useState(def.start);
  const [periodEnd, setPeriodEnd] = useState(def.end);
  const [urls, setUrls] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = useMutation({
    mutationFn: async () => {
      const list = parseUrls(urls);
      if (list.length === 0) throw new Error('Paste at least one http(s) URL.');
      if (periodEnd < periodStart) throw new Error('period_end must be on or after period_start.');
      const r = await api.createReport(clientId, {
        title: title.trim() || undefined,
        period_start: periodStart,
        period_end: periodEnd,
      });
      await api.addCoverage(r.id, list);
      return r;
    },
    onSuccess: (r) => navigate(`/reports/${r.id}`),
    onError: (e) => setError(e instanceof ApiError ? e.message : (e as Error).message),
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    submit.mutate();
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <nav className="text-sm text-gray-500">
        <Link to="/clients" className="hover:text-gray-900">
          Clients
        </Link>{' '}
        ›{' '}
        {client.data ? (
          <Link to={`/clients/${clientId}`} className="hover:text-gray-900">
            {client.data.name}
          </Link>
        ) : (
          <span>…</span>
        )}{' '}
        › <span className="text-gray-900">New report</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight">
        New report{client.data ? ` for ${client.data.name}` : ''}
      </h1>

      <form onSubmit={onSubmit} className="bg-white rounded border border-gray-200 p-6 space-y-4">
        <label className="block">
          <span className="block text-sm font-medium text-gray-700">Title (optional)</span>
          <input
            className="mt-1 block w-full rounded border border-gray-300 px-3 py-2 outline-none focus:border-gray-900 focus:ring-1 focus:ring-gray-900"
            value={title}
            placeholder={`e.g. ${monthLabel(periodEnd)}`}
            onChange={(e) => setTitle(e.target.value)}
          />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="block text-sm font-medium text-gray-700">Period start</span>
            <input
              type="date"
              className="mt-1 block w-full rounded border border-gray-300 px-3 py-2"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
              required
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium text-gray-700">Period end</span>
            <input
              type="date"
              className="mt-1 block w-full rounded border border-gray-300 px-3 py-2"
              value={periodEnd}
              onChange={(e) => setPeriodEnd(e.target.value)}
              required
            />
          </label>
        </div>
        <label className="block">
          <span className="block text-sm font-medium text-gray-700">
            Coverage URLs (one per line, or separated by commas/spaces)
          </span>
          <textarea
            rows={10}
            className="mt-1 block w-full font-mono text-sm rounded border border-gray-300 px-3 py-2 outline-none focus:border-gray-900 focus:ring-1 focus:ring-gray-900"
            value={urls}
            placeholder="https://techcrunch.com/...
https://wsj.com/articles/..."
            onChange={(e) => setUrls(e.target.value)}
          />
          <span className="mt-1 block text-xs text-gray-500">
            {parseUrls(urls).length} URL{parseUrls(urls).length === 1 ? '' : 's'} detected
          </span>
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <div className="flex justify-end">
          <button
            type="submit"
            disabled={submit.isPending}
            className="rounded bg-gray-900 text-white px-4 py-2.5 font-medium hover:bg-gray-800 disabled:opacity-60"
          >
            {submit.isPending ? 'Creating…' : 'Extract coverage →'}
          </button>
        </div>
      </form>
    </div>
  );
}

function parseUrls(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const piece of raw.split(/[\s,]+/)) {
    const u = piece.trim();
    if (!u) continue;
    if (!/^https?:\/\//i.test(u)) continue;
    if (!seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  return out;
}

function monthLabel(iso: string): string {
  try {
    const d = new Date(iso + 'T00:00:00Z');
    return d.toLocaleString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  } catch {
    return '';
  }
}
