import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Avatar } from '../components/Avatar';
import { BrowserFrame } from '../components/BrowserFrame';
import { Eyebrow, PrimaryLink, SecondaryLink } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import { api, ApiError, uploadLogo } from '../lib/api';

export function ClientEdit() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
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

  if (q.isLoading) {
    return (
      <BrowserFrame
        crumbs={[
          { label: `${slug}.beat.app`, to: '/clients' },
          { label: 'clients', to: '/clients' },
          { label: '…' },
        ]}
      >
        <p className="text-gray-500">Loading…</p>
      </BrowserFrame>
    );
  }
  if (q.error) return <p className="text-red-600">Failed to load client.</p>;
  if (!q.data) return null;
  const c = q.data;

  return (
    <BrowserFrame
      crumbs={[
        { label: `${slug}.beat.app`, to: '/clients' },
        { label: 'clients', to: '/clients' },
        { label: c.name.toLowerCase(), to: `/clients/${c.id}` },
        { label: 'settings' },
      ]}
    >
      <div className="space-y-7 max-w-3xl">
        <div className="flex items-center gap-4">
          <Avatar
            name={c.name}
            logoUrl={c.logo_url}
            primaryColor={c.primary_color}
            size="lg"
          />
          <div className="flex-1 min-w-0">
            <p className="text-sm text-gray-500">
              <Link to={`/clients/${c.id}`} className="hover:text-ink hover:underline">
                {c.name}
              </Link>
            </p>
            <h1 className="text-2xl font-semibold tracking-tightish text-ink mt-0.5">Settings</h1>
            <p className="mt-1 text-xs text-gray-500">
              Branding, cadence, and notes for this client. Field changes save automatically when
              you click away.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-none">
            <SecondaryLink to={`/clients/${c.id}/context`}>Context</SecondaryLink>
            <PrimaryLink to={`/clients/${c.id}/reports/new`}>+ New report</PrimaryLink>
          </div>
        </div>

        <Section title="Branding">
          <FormRow label="Logo">
            <input
              type="file"
              accept="image/png,image/jpeg,image/svg+xml,image/webp"
              onChange={onLogoChange}
              disabled={uploading}
              className="text-sm"
            />
            {uploading && <span className="text-sm text-gray-500">Uploading…</span>}
          </FormRow>
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
        </Section>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <Section title="Danger zone">
          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-sm font-medium text-ink">Delete this client</p>
              <p className="text-xs text-gray-500 mt-0.5">
                Past reports remain accessible via direct link, but the client disappears from the
                workspace.
              </p>
            </div>
            <button
              onClick={() => {
                if (confirm(`Delete ${c.name}? Existing reports remain accessible.`))
                  remove.mutate();
              }}
              className="rounded-lg border border-red-200 bg-white text-red-700 hover:bg-red-50 px-3 py-2 text-sm font-medium flex-none"
            >
              Delete client
            </button>
          </div>
        </Section>
      </div>
    </BrowserFrame>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <Eyebrow className="mb-3">{title}</Eyebrow>
      <div className="bg-white rounded-xl border border-gray-200 p-5 space-y-4">{children}</div>
    </section>
  );
}

function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-500 flex-none">{label}</span>
      <div className="flex-1 flex items-center gap-3 min-w-0">{children}</div>
    </label>
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
  const valid = /^[0-9A-Fa-f]{6}$/.test(v);
  return (
    <FormRow label={label}>
      <input
        className="rounded-lg border border-gray-300 px-3 py-1.5 w-32 font-mono text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
        placeholder="1F2937"
        value={v}
        onChange={(e) => setV(e.target.value.replace(/^#/, '').toUpperCase())}
        onBlur={() => valid && onSave(v)}
      />
      {valid && (
        <span
          className="h-7 w-7 rounded-md border border-gray-200"
          style={{ background: `#${v}` }}
        />
      )}
    </FormRow>
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
    <FormRow label="Default cadence">
      <select
        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
        value={value ?? ''}
        onChange={(e) => onSave(e.target.value)}
      >
        <option value="">—</option>
        <option value="weekly">Weekly</option>
        <option value="biweekly">Biweekly</option>
        <option value="monthly">Monthly</option>
        <option value="quarterly">Quarterly</option>
      </select>
    </FormRow>
  );
}

function NotesField({ value, onSave }: { value: string | null; onSave: (v: string) => void }) {
  const [v, setV] = useState(value ?? '');
  return (
    <label className="flex items-start gap-3 text-sm">
      <span className="w-32 text-gray-500 pt-2 flex-none">Notes</span>
      <textarea
        rows={3}
        className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
        value={v}
        onChange={(e) => setV(e.target.value)}
        onBlur={() => v !== (value ?? '') && onSave(v)}
      />
    </label>
  );
}
