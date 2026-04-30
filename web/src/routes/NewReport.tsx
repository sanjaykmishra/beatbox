import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { useAuth } from '../lib/useAuth';
import { api, ApiError } from '../lib/api';

function defaultPeriod(): { start: string; end: string } {
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
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const client = useQuery({
    queryKey: ['client', clientId],
    queryFn: () => api.getClient(clientId),
  });

  const def = defaultPeriod();
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

  const urlCount = parseUrls(urls).length;
  const clientLabel = client.data?.name ?? '…';

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app` },
        { label: 'clients' },
        { label: clientLabel.toLowerCase() },
        { label: 'new report' },
      ]}
    >
      <form onSubmit={onSubmit} className="space-y-6">
        <div>
          <p className="text-sm text-gray-500">{clientLabel}</p>
          <h1 className="text-2xl font-semibold tracking-tightish text-ink mt-0.5">
            New coverage report
          </h1>
          <p className="mt-1 text-xs text-gray-500">
            Pick the reporting period and paste article URLs (one per line, or any whitespace
            separator). Beat extracts each in parallel; you'll review on the next screen before
            generating the PDF.
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <Field label="Period start">
            <input
              type="date"
              className="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
              required
            />
          </Field>
          <Field label="Period end">
            <input
              type="date"
              className="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
              value={periodEnd}
              onChange={(e) => setPeriodEnd(e.target.value)}
              required
            />
          </Field>
          <Field label="Template">
            <select
              className="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
              defaultValue="standard"
            >
              <option value="standard">Standard</option>
            </select>
          </Field>
        </div>

        <Field label="Coverage URLs">
          <textarea
            rows={10}
            className="block w-full rounded-lg border border-gray-300 px-3 py-2 font-mono text-[13px] leading-6 outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
            value={urls}
            placeholder={`techcrunch.com/2025/12/04/acme-raises-30m-series-b\nwsj.com/articles/acme-acquires-foo-corp\ntheverge.com/2025/12/18/acme-launches-mobile-app`}
            onChange={(e) => setUrls(e.target.value)}
          />
        </Field>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex items-center justify-between pt-2 border-t border-gray-100">
          <p className="text-sm text-gray-500">
            {urlCount} URL{urlCount === 1 ? '' : 's'} ready
          </p>
          <button
            type="submit"
            disabled={submit.isPending || urlCount === 0}
            className="ink-btn rounded-lg text-white px-5 py-2.5 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {submit.isPending ? 'Creating…' : 'Extract coverage →'}
          </button>
        </div>
      </form>
    </BrowserFrame>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs font-medium text-gray-500 mb-1.5">{label}</span>
      {children}
    </label>
  );
}

function parseUrls(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const piece of raw.split(/[\s,]+/)) {
    let u = piece.trim();
    if (!u) continue;
    if (!/^https?:\/\//i.test(u)) u = `https://${u}`;
    try {
      new URL(u);
    } catch {
      continue;
    }
    if (!seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  return out;
}
