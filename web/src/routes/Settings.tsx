import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { api, ApiError, uploadLogo } from '../lib/api';
import { useAuth } from '../lib/useAuth';

export function Settings() {
  const { workspace } = useAuth();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const update = useMutation({
    mutationFn: (b: Parameters<typeof api.updateWorkspace>[0]) => api.updateWorkspace(b),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['workspace'] });
      window.location.reload();
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Update failed'),
  });

  if (!workspace) return null;

  async function onLogoChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploading(true);
    try {
      const url = await uploadLogo(file, 'logo');
      update.mutate({ logo_url: url });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-6 max-w-xl">
      <h1 className="text-2xl font-semibold tracking-tight">Workspace</h1>
      <section className="bg-white rounded border border-gray-200 p-6 space-y-4">
        <Row label="Name" value={workspace.name} />
        <Row label="Slug" value={workspace.slug} />
        <Row label="Plan" value={workspace.plan} />
        {workspace.trial_ends_at && (
          <Row
            label="Trial ends"
            value={new Date(workspace.trial_ends_at).toLocaleDateString()}
          />
        )}
        <label className="flex items-center gap-3 text-sm pt-2 border-t border-gray-100">
          <span className="w-32 text-gray-700">Logo</span>
          {workspace.logo_url ? (
            <img src={workspace.logo_url} alt="" className="h-10 w-10 rounded object-cover" />
          ) : (
            <div className="h-10 w-10 rounded bg-gray-200" />
          )}
          <input
            type="file"
            accept="image/png,image/jpeg,image/svg+xml,image/webp"
            onChange={onLogoChange}
            disabled={uploading}
          />
          {uploading && <span className="text-gray-500">Uploading…</span>}
        </label>
      </section>
      {error && <p className="text-sm text-red-600">{error}</p>}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-700">{label}</span>
      <span className="text-gray-900">{value}</span>
    </div>
  );
}
