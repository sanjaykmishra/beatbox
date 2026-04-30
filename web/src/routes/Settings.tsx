import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, ApiError, uploadLogo, type Billing } from '../lib/api';
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

      <BillingSection />
    </div>
  );
}

function BillingSection() {
  const [params] = useSearchParams();
  const billing = useQuery({ queryKey: ['billing'], queryFn: () => api.getBilling() });
  const [error, setError] = useState<string | null>(
    params.get('billing') === 'cancelled' ? 'Checkout cancelled — try again any time.' : null,
  );

  const checkout = useMutation({
    mutationFn: ({ plan, interval }: { plan: 'solo' | 'agency'; interval: 'monthly' | 'yearly' }) =>
      api.startCheckout(plan, interval),
    onSuccess: (r) => {
      window.location.href = r.checkout_url;
    },
    onError: (e) =>
      setError(e instanceof ApiError ? e.message : 'Could not start checkout.'),
  });

  const portal = useMutation({
    mutationFn: () => api.openPortal(),
    onSuccess: (r) => {
      window.location.href = r.portal_url;
    },
    onError: (e) =>
      setError(e instanceof ApiError ? e.message : 'Could not open billing portal.'),
  });

  if (billing.isLoading) return null;
  if (billing.error || !billing.data) {
    return <p className="text-sm text-red-600">Failed to load billing.</p>;
  }
  const b = billing.data;
  const onPaidPlan = b.plan === 'solo' || b.plan === 'agency' || b.plan === 'enterprise';
  const trialDaysLeft = trialDays(b);

  return (
    <section className="bg-white rounded border border-gray-200 p-6 space-y-4">
      <h2 className="text-sm font-medium text-gray-700">Billing</h2>
      <Row label="Plan" value={b.plan} />
      <Row
        label="Limits"
        value={`${b.plan_limit_clients} clients · ${
          b.plan_limit_reports_monthly === 2147483647
            ? 'unlimited'
            : b.plan_limit_reports_monthly
        } reports / month`}
      />
      {b.plan === 'trial' && (
        <p className={trialDaysLeft <= 3 ? 'text-sm text-amber-700' : 'text-sm text-gray-600'}>
          {trialDaysLeft > 0
            ? `Trial: ${trialDaysLeft} day${trialDaysLeft === 1 ? '' : 's'} remaining.`
            : 'Trial ended. Add a card to continue creating reports.'}
        </p>
      )}
      {!b.stripe_configured && (
        <p className="text-xs text-gray-500">
          Billing isn't configured on the server. Set Stripe env vars to enable upgrades.
        </p>
      )}
      {b.stripe_configured && !onPaidPlan && (
        <div className="grid grid-cols-2 gap-3 pt-2">
          <PlanCard
            name="Solo"
            price="$39/mo"
            yearly="$33/mo billed annually"
            disabled={checkout.isPending}
            onMonthly={() => checkout.mutate({ plan: 'solo', interval: 'monthly' })}
            onYearly={() => checkout.mutate({ plan: 'solo', interval: 'yearly' })}
          />
          <PlanCard
            name="Agency"
            price="$99/mo"
            yearly="$84/mo billed annually"
            disabled={checkout.isPending}
            onMonthly={() => checkout.mutate({ plan: 'agency', interval: 'monthly' })}
            onYearly={() => checkout.mutate({ plan: 'agency', interval: 'yearly' })}
          />
        </div>
      )}
      {b.stripe_configured && onPaidPlan && (
        <button
          onClick={() => portal.mutate()}
          disabled={portal.isPending}
          className="rounded border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:border-gray-400 disabled:opacity-50"
        >
          {portal.isPending ? 'Opening…' : 'Manage subscription'}
        </button>
      )}
      {error && <p className="text-sm text-red-600">{error}</p>}
    </section>
  );
}

function PlanCard({
  name,
  price,
  yearly,
  disabled,
  onMonthly,
  onYearly,
}: {
  name: string;
  price: string;
  yearly: string;
  disabled: boolean;
  onMonthly: () => void;
  onYearly: () => void;
}) {
  return (
    <div className="border border-gray-200 rounded p-4 space-y-2">
      <div className="font-medium">{name}</div>
      <div className="text-2xl font-semibold tracking-tight">{price}</div>
      <div className="text-xs text-gray-500">{yearly}</div>
      <div className="flex gap-2 pt-2">
        <button
          onClick={onMonthly}
          disabled={disabled}
          className="flex-1 rounded bg-gray-900 text-white text-sm px-3 py-1.5 font-medium hover:bg-gray-800 disabled:opacity-60"
        >
          Monthly
        </button>
        <button
          onClick={onYearly}
          disabled={disabled}
          className="flex-1 rounded border border-gray-300 text-sm px-3 py-1.5 font-medium text-gray-700 hover:border-gray-400 disabled:opacity-60"
        >
          Yearly
        </button>
      </div>
    </div>
  );
}

function trialDays(b: Billing): number {
  if (!b.trial_ends_at) return 0;
  const ms = new Date(b.trial_ends_at).getTime() - Date.now();
  return Math.max(0, Math.ceil(ms / (1000 * 60 * 60 * 24)));
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-700">{label}</span>
      <span className="text-gray-900">{value}</span>
    </div>
  );
}
