import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { BrowserFrame } from '../components/BrowserFrame';
import { Eyebrow, Pill, type PillTone } from '../components/ui';
import { api, ApiError, uploadLogo, type Billing, type Member } from '../lib/api';
import { useAuth } from '../lib/useAuth';

export function Settings() {
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
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

  const admin = useQuery({ queryKey: ['admin-whoami'], queryFn: api.adminWhoami });

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
    <BrowserFrame crumbs={[{ label: `${slug}.beat.app` }, { label: 'settings' }]}>
      <div className="space-y-7 max-w-3xl">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tightish text-ink">Settings</h1>
            <p className="mt-1 text-xs text-gray-500">
              Workspace branding, members, and billing. Changes apply to every client and report.
            </p>
          </div>
          {admin.data?.is_admin && (
            <Link
              to="/admin/dashboard"
              className="text-sm font-medium text-gray-700 hover:text-ink hover:underline mt-1 flex-none"
            >
              Founder admin →
            </Link>
          )}
        </div>

        <Section title="Workspace">
          <Row label="Name" value={workspace.name} />
          <Row label="Slug" value={workspace.slug} />
          <Row label="Plan" value={workspace.plan} />
          {workspace.trial_ends_at && (
            <Row
              label="Trial ends"
              value={new Date(workspace.trial_ends_at).toLocaleDateString()}
            />
          )}
          <div className="flex items-center gap-3 pt-3 border-t border-gray-100">
            <span className="w-32 text-sm text-gray-500">Logo</span>
            {workspace.logo_url ? (
              <img
                src={workspace.logo_url}
                alt=""
                className="h-10 w-10 rounded-lg object-cover border border-gray-200"
              />
            ) : (
              <div className="h-10 w-10 rounded-lg bg-gray-100 border border-gray-200" />
            )}
            <input
              type="file"
              accept="image/png,image/jpeg,image/svg+xml,image/webp"
              onChange={onLogoChange}
              disabled={uploading}
              className="text-sm"
            />
            {uploading && <span className="text-sm text-gray-500">Uploading…</span>}
          </div>
        </Section>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <MembersSection />
        <BillingSection />
      </div>
    </BrowserFrame>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <Eyebrow className="mb-3">{title}</Eyebrow>
      <div className="bg-white rounded-xl border border-gray-200 p-5 space-y-3">{children}</div>
    </section>
  );
}

function MembersSection() {
  const members = useQuery({ queryKey: ['members'], queryFn: api.listMembers });
  return (
    <section>
      <Eyebrow className="mb-3">Members</Eyebrow>
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {members.isLoading && (
          <div className="p-5 text-sm text-gray-500">Loading members…</div>
        )}
        {members.error && (
          <div className="p-5 text-sm text-red-600">Failed to load members.</div>
        )}
        {members.data && (
          <ul className="divide-y divide-gray-100">
            {members.data.map((m) => (
              <MemberRow key={m.user_id} member={m} />
            ))}
          </ul>
        )}
        <div className="px-5 py-3 bg-gray-50 border-t border-gray-200 text-xs text-gray-500">
          Inviting teammates lands in Phase 2. Today, ask a teammate to sign up; we'll move them
          into your workspace on request.
        </div>
      </div>
    </section>
  );
}

function MemberRow({ member }: { member: Member }) {
  const initial = (member.name || member.email).charAt(0).toUpperCase();
  const tone: PillTone =
    member.role === 'owner' ? 'blue' : member.role === 'viewer' ? 'gray' : 'gray';
  return (
    <li className="px-5 py-3.5 flex items-center gap-4">
      <div className="h-9 w-9 rounded-full bg-gray-100 text-gray-600 flex items-center justify-center font-semibold text-sm flex-none">
        {initial}
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium text-ink truncate">{member.name || member.email}</div>
        <div className="text-xs text-gray-500 truncate">{member.email}</div>
      </div>
      <div className="text-xs text-gray-500 hidden sm:block flex-none">
        {member.last_login_at
          ? `last seen ${formatRelative(member.last_login_at)}`
          : 'never logged in'}
      </div>
      <Pill tone={tone} className="capitalize flex-none">
        {member.role}
      </Pill>
    </li>
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
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Could not start checkout.'),
  });

  const portal = useMutation({
    mutationFn: () => api.openPortal(),
    onSuccess: (r) => {
      window.location.href = r.portal_url;
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Could not open billing portal.'),
  });

  if (billing.isLoading) return null;
  if (billing.error || !billing.data) {
    return <p className="text-sm text-red-600">Failed to load billing.</p>;
  }
  const b = billing.data;
  const onPaidPlan = b.plan === 'solo' || b.plan === 'agency' || b.plan === 'enterprise';
  const trialDaysLeft = trialDays(b);

  return (
    <section>
      <Eyebrow className="mb-3">Billing</Eyebrow>
      <div className="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
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
            className="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:border-gray-400 hover:bg-gray-50 disabled:opacity-50"
          >
            {portal.isPending ? 'Opening…' : 'Manage subscription'}
          </button>
        )}
        {error && <p className="text-sm text-red-600">{error}</p>}
      </div>
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
    <div className="border border-gray-200 rounded-xl p-4 space-y-2">
      <div className="font-medium">{name}</div>
      <div className="text-2xl font-semibold tracking-tightish">{price}</div>
      <div className="text-xs text-gray-500">{yearly}</div>
      <div className="flex gap-2 pt-2">
        <button
          onClick={onMonthly}
          disabled={disabled}
          className="flex-1 ink-btn rounded-lg text-white text-sm px-3 py-1.5 font-medium disabled:opacity-60"
        >
          Monthly
        </button>
        <button
          onClick={onYearly}
          disabled={disabled}
          className="flex-1 rounded-lg border border-gray-300 text-sm px-3 py-1.5 font-medium text-gray-700 hover:border-gray-400 disabled:opacity-60"
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

function Row({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="flex items-center gap-3 text-sm">
      <span className="w-32 text-gray-500">{label}</span>
      <span className="text-ink capitalize">{value}</span>
    </div>
  );
}

function formatRelative(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const days = Math.floor(ms / 86_400_000);
  if (days < 1) {
    const hours = Math.floor(ms / 3_600_000);
    return hours <= 0 ? 'just now' : `${hours}h ago`;
  }
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}
