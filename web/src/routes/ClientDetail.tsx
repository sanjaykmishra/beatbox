import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, uploadLogo } from '../lib/api';

export function ClientDetail() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['client', id], queryFn: () => api.getClient(id) });
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const update = useMutation({
    mutationFn: (b: Parameters<typeof api.updateClient>[1]) => api.updateClient(id, b),
    onSuccess: (data) => {
      qc.setQueryData(['client', id], data);
      void qc.invalidateQueries({ queryKey: ['clients'] });
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Update failed'),
  });

  const remove = useMutation({
    mutationFn: () => api.deleteClient(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['clients'] });
      navigate('/clients');
    },
  });

  async function onLogoChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploading(true);
    try {
      const url = await uploadLogo(file, 'client_logo');
      update.mutate({ logo_url: url });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  if (q.isLoading) return <p className="text-gray-500">Loading…</p>;
  if (q.error) return <p className="text-red-600">Failed to load client.</p>;
  if (!q.data) return null;
  const c = q.data;

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-4">
        {c.logo_url ? (
          <img src={c.logo_url} alt="" className="h-14 w-14 rounded object-cover" />
        ) : (
          <div
            className="h-14 w-14 rounded"
            style={{ background: c.primary_color ? `#${c.primary_color}` : '#E5E7EB' }}
          />
        )}
        <h1 className="text-2xl font-semibold tracking-tight flex-1">{c.name}</h1>
        <Link
          to={`/clients/${c.id}/context`}
          className="rounded border border-gray-300 px-4 py-2 font-medium text-gray-700 hover:border-gray-400"
        >
          Context
        </Link>
        <Link
          to={`/clients/${c.id}/reports/new`}
          className="rounded bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-800"
        >
          New report
        </Link>
      </div>

      <section className="bg-white rounded border border-gray-200 p-6 space-y-4">
        <h2 className="text-sm font-medium text-gray-700">Branding</h2>
        <label className="flex items-center gap-3 text-sm">
          <span className="w-32 text-gray-700">Logo</span>
          <input
            type="file"
            accept="image/png,image/jpeg,image/svg+xml,image/webp"
            onChange={onLogoChange}
            disabled={uploading}
          />
          {uploading && <span className="text-gray-500">Uploading…</span>}
        </label>
        <ColorField
          label="Primary color"
          value={c.primary_color}
          onSave={(v) => update.mutate({ primary_color: v })}
        />
        <CadenceField
          value={c.default_cadence}
          onSave={(v) => update.mutate({ default_cadence: v })}
        />
        <NotesField value={c.notes} onSave={(v) => update.mutate({ notes: v })} />
      </section>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <button
        onClick={() => {
          if (confirm(`Delete ${c.name}? Existing reports remain accessible.`)) remove.mutate();
        }}
        className="text-sm text-red-600 hover:text-red-700"
      >
        Delete client
      </button>
    </div>
  );
}

function ColorField({
  label,
  value,
  onSave,
}: {
  label: string;
  value: string | null;
  onSave: (v: string) => void;
}) {
  const [v, setV] = useState(value ?? '');
  return (
    <label className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-700">{label}</span>
      <input
        className="rounded border border-gray-300 px-3 py-1.5 w-32"
        placeholder="1F2937"
        value={v}
        onChange={(e) => setV(e.target.value.replace(/^#/, '').toUpperCase())}
        onBlur={() => v.match(/^[0-9A-Fa-f]{6}$/) && onSave(v)}
      />
      {v.match(/^[0-9A-Fa-f]{6}$/) && (
        <span className="h-6 w-6 rounded border border-gray-200" style={{ background: `#${v}` }} />
      )}
    </label>
  );
}

function CadenceField({
  value,
  onSave,
}: {
  value: string | null;
  onSave: (v: string) => void;
}) {
  return (
    <label className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-700">Default cadence</span>
      <select
        className="rounded border border-gray-300 px-2 py-1.5"
        value={value ?? ''}
        onChange={(e) => onSave(e.target.value)}
      >
        <option value="">—</option>
        <option value="weekly">Weekly</option>
        <option value="biweekly">Biweekly</option>
        <option value="monthly">Monthly</option>
        <option value="quarterly">Quarterly</option>
      </select>
    </label>
  );
}

function NotesField({ value, onSave }: { value: string | null; onSave: (v: string) => void }) {
  const [v, setV] = useState(value ?? '');
  return (
    <label className="flex items-start gap-3 text-sm">
      <span className="w-32 text-gray-700 pt-2">Notes</span>
      <textarea
        rows={3}
        className="flex-1 rounded border border-gray-300 px-3 py-2"
        value={v}
        onChange={(e) => setV(e.target.value)}
        onBlur={() => v !== (value ?? '') && onSave(v)}
      />
    </label>
  );
}
