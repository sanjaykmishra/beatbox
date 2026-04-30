import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Avatar } from '../components/Avatar';
import { BrowserFrame } from '../components/BrowserFrame';
import { Eyebrow, Pill, PrimaryButton, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import {
  ApiError,
  api,
  type ClientListItem,
  type OwnedPost,
  type PlatformVariant,
  type PostStatus,
  type PostTransition,
  type SocialPlatform,
} from '../lib/api';

const PLATFORMS: SocialPlatform[] = [
  'x',
  'linkedin',
  'bluesky',
  'threads',
  'instagram',
  'facebook',
  'tiktok',
  'reddit',
  'substack',
  'youtube',
  'mastodon',
];

const PLATFORM_LABELS: Record<SocialPlatform, string> = {
  x: 'X',
  linkedin: 'LinkedIn',
  bluesky: 'Bluesky',
  threads: 'Threads',
  instagram: 'Instagram',
  facebook: 'Facebook',
  tiktok: 'TikTok',
  reddit: 'Reddit',
  substack: 'Substack',
  youtube: 'YouTube',
  mastodon: 'Mastodon',
};

/** Soft per-platform character limits used by the composer counter. */
const PLATFORM_CHAR_LIMITS: Record<SocialPlatform, number> = {
  x: 280,
  linkedin: 3000,
  bluesky: 300,
  threads: 500,
  instagram: 2200,
  facebook: 63206,
  tiktok: 2200,
  reddit: 40000,
  substack: 5000,
  youtube: 5000,
  mastodon: 500,
};

const STATUS_TONE: Record<PostStatus, PillTone> = {
  draft: 'gray',
  internal_review: 'blue',
  client_review: 'amber',
  approved: 'green',
  scheduled: 'green',
  posted: 'green',
  rejected: 'red',
  archived: 'gray',
};

export function Calendar() {
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [weekStart, setWeekStart] = useState(() => mondayOf(new Date()));
  const [clientFilter, setClientFilter] = useState<string | undefined>(undefined);
  const [composer, setComposer] = useState<ComposerState>({ open: false });

  const clientsQ = useQuery({ queryKey: ['clients'], queryFn: api.listClients });

  const weekEnd = addDays(weekStart, 7);
  const postsQ = useQuery({
    queryKey: ['posts', clientFilter, weekStart.toISOString()],
    queryFn: () =>
      api.listPosts({
        client_id: clientFilter,
        from: weekStart.toISOString(),
        to: weekEnd.toISOString(),
        limit: 200,
      }),
  });

  const clientsById = useMemo(() => {
    const m = new Map<string, ClientListItem>();
    clientsQ.data?.items.forEach((c) => m.set(c.id, c));
    return m;
  }, [clientsQ.data]);

  function shiftWeek(deltaWeeks: number) {
    setWeekStart((d) => addDays(d, deltaWeeks * 7));
  }

  return (
    <BrowserFrame
      crumbs={[{ label: `${slug}.beat.app` }, { label: 'calendar' }]}
      rightSlot={
        <PrimaryButton
          onClick={() => setComposer({ open: true, mode: 'new', clientId: clientFilter })}
          className="!px-3 !py-1 text-[12px]"
        >
          + New post
        </PrimaryButton>
      }
    >
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tightish text-ink">Editorial calendar</h1>
          <p className="mt-1 text-xs text-gray-500">
            Plan owned content across clients and platforms. Beat doesn't publish — when a post
            ships, click <em>Mark posted</em> to record it. Drafts auto-save in the composer.
          </p>
        </div>

        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-1">
            <button
              onClick={() => shiftWeek(-1)}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-2.5 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
              aria-label="Previous week"
            >
              ‹
            </button>
            <button
              onClick={() => setWeekStart(mondayOf(new Date()))}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-3 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
            >
              This week
            </button>
            <button
              onClick={() => shiftWeek(1)}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-2.5 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
              aria-label="Next week"
            >
              ›
            </button>
            <span className="ml-3 text-sm text-gray-700 font-medium">
              {formatWeekRange(weekStart)}
            </span>
          </div>
          <ClientFilter
            clients={clientsQ.data?.items ?? []}
            value={clientFilter}
            onChange={setClientFilter}
          />
        </div>

        <WeekGrid
          weekStart={weekStart}
          posts={postsQ.data?.items ?? []}
          clientsById={clientsById}
          loading={postsQ.isLoading}
          onSelect={(p) => setComposer({ open: true, mode: 'edit', postId: p.id })}
          onEmptyDay={(day) =>
            setComposer({ open: true, mode: 'new', clientId: clientFilter, scheduledFor: day })
          }
        />
      </div>

      {composer.open && (
        <ComposerDrawer
          state={composer}
          clients={clientsQ.data?.items ?? []}
          onClose={() => setComposer({ open: false })}
        />
      )}
    </BrowserFrame>
  );
}

type ComposerState =
  | { open: false }
  | { open: true; mode: 'new'; clientId?: string; scheduledFor?: Date }
  | { open: true; mode: 'edit'; postId: string };

// --------------------- Week grid ---------------------

function WeekGrid({
  weekStart,
  posts,
  clientsById,
  loading,
  onSelect,
  onEmptyDay,
}: {
  weekStart: Date;
  posts: OwnedPost[];
  clientsById: Map<string, ClientListItem>;
  loading: boolean;
  onSelect: (p: OwnedPost) => void;
  onEmptyDay: (day: Date) => void;
}) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const byDay = new Map<string, OwnedPost[]>();
  for (const p of posts) {
    if (!p.scheduled_for) continue;
    const k = ymd(new Date(p.scheduled_for));
    const list = byDay.get(k) ?? [];
    list.push(p);
    byDay.set(k, list);
  }

  return (
    <div className="grid grid-cols-2 md:grid-cols-7 gap-2">
      {days.map((d) => {
        const k = ymd(d);
        const list = byDay.get(k) ?? [];
        const isToday = ymd(new Date()) === k;
        return (
          <div
            key={k}
            className={`min-h-[180px] bg-white rounded-xl border ${
              isToday ? 'border-ink/60' : 'border-gray-200'
            } p-3 flex flex-col`}
          >
            <div className="flex items-baseline justify-between mb-2">
              <div>
                <div className="text-[10px] font-semibold uppercase text-gray-500 tracking-wider">
                  {d.toLocaleDateString('en-US', { weekday: 'short' })}
                </div>
                <div
                  className={`text-lg font-semibold tabular-nums ${
                    isToday ? 'text-ink' : 'text-gray-700'
                  }`}
                >
                  {d.getDate()}
                </div>
              </div>
              <button
                onClick={() => onEmptyDay(d)}
                className="text-gray-400 hover:text-ink text-lg leading-none"
                title="Draft a post on this day"
                aria-label="Draft a post on this day"
              >
                +
              </button>
            </div>
            {loading && list.length === 0 ? (
              <div className="h-3 w-2/3 bg-gray-100 rounded animate-pulse" />
            ) : list.length === 0 ? (
              <div className="text-[11px] text-gray-300 italic">No posts</div>
            ) : (
              <ul className="space-y-1.5">
                {list
                  .sort(
                    (a, b) =>
                      (a.scheduled_for ?? '').localeCompare(b.scheduled_for ?? '') ||
                      a.id.localeCompare(b.id),
                  )
                  .map((p) => (
                    <li key={p.id}>
                      <PostCard
                        post={p}
                        client={clientsById.get(p.client_id)}
                        onClick={() => onSelect(p)}
                      />
                    </li>
                  ))}
              </ul>
            )}
          </div>
        );
      })}
    </div>
  );
}

function PostCard({
  post,
  client,
  onClick,
}: {
  post: OwnedPost;
  client: ClientListItem | undefined;
  onClick: () => void;
}) {
  const time = post.scheduled_for
    ? new Date(post.scheduled_for).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
    : null;
  const summary =
    post.title?.trim() ||
    (post.primary_content_text || '').slice(0, 60) ||
    '(empty draft)';
  return (
    <button
      onClick={onClick}
      className="w-full text-left bg-gray-50 hover:bg-white border border-gray-200 hover:border-gray-300 rounded-md p-2 transition-colors"
    >
      <div className="flex items-center gap-1.5 mb-1">
        {client && (
          <Avatar
            name={client.name}
            logoUrl={client.logo_url}
            primaryColor={client.primary_color}
            size="sm"
          />
        )}
        {time && <span className="text-[10px] text-gray-500 tabular-nums">{time}</span>}
      </div>
      <div className="text-xs font-medium text-ink line-clamp-2">{summary}</div>
      <div className="mt-1.5 flex items-center justify-between gap-1">
        <Pill tone={STATUS_TONE[post.status]} className="!text-[9px] !px-1.5 !py-0">
          {post.status.replace('_', ' ')}
        </Pill>
        <span className="text-[9px] text-gray-400 tabular-nums">
          {post.target_platforms.length}p
        </span>
      </div>
    </button>
  );
}

// --------------------- Client filter ---------------------

function ClientFilter({
  clients,
  value,
  onChange,
}: {
  clients: ClientListItem[];
  value: string | undefined;
  onChange: (id: string | undefined) => void;
}) {
  return (
    <select
      className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || undefined)}
    >
      <option value="">All clients</option>
      {clients.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name}
        </option>
      ))}
    </select>
  );
}

// --------------------- Composer drawer ---------------------

function ComposerDrawer({
  state,
  clients,
  onClose,
}: {
  state: ComposerState;
  clients: ClientListItem[];
  onClose: () => void;
}) {
  if (!state.open) return null;
  return (
    <div className="fixed inset-0 z-50 flex">
      <button
        type="button"
        aria-label="Close"
        className="flex-1 bg-black/40"
        onClick={onClose}
      />
      <div className="w-full max-w-3xl bg-white shadow-xl border-l border-gray-200 overflow-y-auto">
        {state.mode === 'edit' ? (
          <EditComposer postId={state.postId} clients={clients} onClose={onClose} />
        ) : (
          <NewComposer
            clients={clients}
            initialClientId={state.clientId}
            initialScheduledFor={state.scheduledFor}
            onClose={onClose}
          />
        )}
      </div>
    </div>
  );
}

function NewComposer({
  clients,
  initialClientId,
  initialScheduledFor,
  onClose,
}: {
  clients: ClientListItem[];
  initialClientId?: string;
  initialScheduledFor?: Date;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [clientId, setClientId] = useState(initialClientId ?? clients[0]?.id ?? '');
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: (clientId: string) =>
      api.createPost({
        client_id: clientId,
        target_platforms: ['linkedin', 'x'],
        scheduled_for: initialScheduledFor
          ? defaultPostingTime(initialScheduledFor).toISOString()
          : undefined,
      }),
    onSuccess: (post) => {
      void qc.invalidateQueries({ queryKey: ['posts'] });
      setStubPost(post);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Create failed'),
  });

  // Once created, swap to the edit composer for the new post id.
  const [stubPost, setStubPost] = useState<OwnedPost | null>(null);
  useEffect(() => {
    if (clientId && !stubPost && !create.isPending && !error) {
      create.mutate(clientId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientId]);

  if (stubPost) {
    return <EditComposer postId={stubPost.id} clients={clients} onClose={onClose} />;
  }

  if (clients.length === 0) {
    return (
      <DrawerFrame onClose={onClose} title="New post">
        <p className="text-sm text-gray-600">
          Create a client first — posts always belong to a client.
        </p>
      </DrawerFrame>
    );
  }

  return (
    <DrawerFrame onClose={onClose} title="New post">
      {error && <p className="text-sm text-red-600">{error}</p>}
      <label className="block">
        <span className="block text-xs font-medium text-gray-500 mb-1">Client</span>
        <select
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          value={clientId}
          onChange={(e) => setClientId(e.target.value)}
        >
          {clients.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      <p className="text-xs text-gray-500">Creating draft…</p>
    </DrawerFrame>
  );
}

function EditComposer({
  postId,
  clients,
  onClose,
}: {
  postId: string;
  clients: ClientListItem[];
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const postQ = useQuery({ queryKey: ['post', postId], queryFn: () => api.getPost(postId) });

  const [title, setTitle] = useState('');
  const [master, setMaster] = useState('');
  const [scheduledFor, setScheduledFor] = useState<string>('');
  const [seriesTag, setSeriesTag] = useState('');
  const [targetPlatforms, setTargetPlatforms] = useState<SocialPlatform[]>([]);
  const [variants, setVariants] = useState<Record<string, PlatformVariant>>({});
  const [warnings, setWarnings] = useState<Record<string, string[]>>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    if (postQ.data && !hydrated) {
      const p = postQ.data;
      setTitle(p.title ?? '');
      setMaster(p.primary_content_text ?? '');
      setScheduledFor(p.scheduled_for ? toLocalInput(p.scheduled_for) : '');
      setSeriesTag(p.series_tag ?? '');
      setTargetPlatforms(p.target_platforms);
      setVariants(p.platform_variants ?? {});
      setHydrated(true);
    }
  }, [postQ.data, hydrated]);

  const save = useMutation({
    mutationFn: () =>
      api.updatePost(postId, {
        title: title || undefined,
        primary_content_text: master || undefined,
        target_platforms: targetPlatforms,
        scheduled_for: scheduledFor ? new Date(scheduledFor).toISOString() : undefined,
        series_tag: seriesTag || undefined,
        platform_variants: variants,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['posts'] });
      void qc.invalidateQueries({ queryKey: ['post', postId] });
    },
    onError: (e) => setSaveError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  const transition = useMutation({
    mutationFn: (vars: { t: PostTransition; reason?: string }) =>
      api.transitionPost(postId, vars.t, vars.reason ? { reason: vars.reason } : undefined),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['posts'] });
      void qc.invalidateQueries({ queryKey: ['post', postId] });
    },
    onError: (e) => setSaveError(e instanceof ApiError ? e.message : 'Transition failed'),
  });

  const remove = useMutation({
    mutationFn: () => api.deletePost(postId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['posts'] });
      onClose();
    },
  });

  const regenerate = useMutation({
    mutationFn: () => api.regenerateVariants(postId, targetPlatforms),
    onSuccess: (r) => {
      setVariants((prev) => ({ ...prev, ...r.variants }));
      setWarnings(r.warnings);
    },
    onError: (e) => setSaveError(e instanceof ApiError ? e.message : 'Regenerate failed'),
  });

  if (postQ.isLoading || !hydrated) {
    return (
      <DrawerFrame onClose={onClose} title="Loading…">
        <div className="h-3 w-1/3 bg-gray-100 rounded animate-pulse" />
        <div className="h-3 w-2/3 bg-gray-100 rounded animate-pulse" />
      </DrawerFrame>
    );
  }
  const post = postQ.data!;

  function togglePlatform(p: SocialPlatform) {
    setTargetPlatforms((prev) =>
      prev.includes(p) ? prev.filter((x) => x !== p) : [...prev, p],
    );
  }

  function setVariant(p: string, content: string) {
    setVariants((prev) => ({
      ...prev,
      [p]: { content, char_count: content.length, edited_at: new Date().toISOString() },
    }));
  }

  return (
    <DrawerFrame
      onClose={onClose}
      title={post.title || 'Untitled post'}
      eyebrow={clients.find((c) => c.id === post.client_id)?.name ?? 'Post'}
      headerRight={
        <Pill tone={STATUS_TONE[post.status]}>{post.status.replace('_', ' ')}</Pill>
      }
    >
      {saveError && <p className="text-sm text-red-600">{saveError}</p>}

      <Field label="Title (internal, never published)">
        <input
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => save.mutate()}
          placeholder="e.g. Series B announcement"
        />
      </Field>

      <Field label="Master content">
        <textarea
          rows={6}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          value={master}
          onChange={(e) => setMaster(e.target.value)}
          onBlur={() => save.mutate()}
          placeholder="Write the long-form version. Variants are adapted from this."
        />
      </Field>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Scheduled for">
          <input
            type="datetime-local"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            value={scheduledFor}
            onChange={(e) => setScheduledFor(e.target.value)}
            onBlur={() => save.mutate()}
          />
        </Field>
        <Field label="Series tag (optional)">
          <input
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            value={seriesTag}
            onChange={(e) => setSeriesTag(e.target.value)}
            onBlur={() => save.mutate()}
            placeholder="e.g. funding, launch"
          />
        </Field>
      </div>

      <div>
        <Eyebrow className="mb-2">Target platforms</Eyebrow>
        <div className="flex flex-wrap gap-1.5">
          {PLATFORMS.map((p) => {
            const active = targetPlatforms.includes(p);
            return (
              <button
                key={p}
                onClick={() => {
                  togglePlatform(p);
                  setTimeout(() => save.mutate(), 0);
                }}
                className={`rounded-full px-3 py-1 text-xs font-medium border transition-colors ${
                  active
                    ? 'border-ink bg-ink text-white'
                    : 'border-gray-300 bg-white text-gray-700 hover:border-gray-400'
                }`}
              >
                {PLATFORM_LABELS[p]}
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <Eyebrow>Per-platform variants</Eyebrow>
          <button
            onClick={() => regenerate.mutate()}
            disabled={regenerate.isPending || master.length === 0 || targetPlatforms.length === 0}
            className="text-xs font-medium text-gray-700 hover:text-ink hover:underline disabled:opacity-50"
            title={
              master.length === 0
                ? 'Add master content first'
                : targetPlatforms.length === 0
                  ? 'Pick at least one platform'
                  : 'Generate variants from the master content'
            }
          >
            {regenerate.isPending ? 'Regenerating…' : 'Regenerate variants ↻'}
          </button>
        </div>
        {targetPlatforms.length === 0 ? (
          <p className="text-xs text-gray-500">Pick a platform above to compose a variant.</p>
        ) : (
          <div className="space-y-3">
            {targetPlatforms.map((p) => (
              <VariantEditor
                key={p}
                platform={p}
                value={variants[p]?.content ?? ''}
                onChange={(content) => setVariant(p, content)}
                onBlur={() => save.mutate()}
                warnings={warnings[p] ?? []}
              />
            ))}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between gap-3 pt-4 border-t border-gray-100">
        <div className="flex flex-wrap gap-1.5">
          {transitionOptionsFor(post.status).map((opt) => (
            <button
              key={opt.transition}
              onClick={() => {
                if (opt.transition === 'reject') {
                  const reason = prompt('Reason for rejection?');
                  if (!reason) return;
                  transition.mutate({ t: opt.transition, reason });
                } else {
                  transition.mutate({ t: opt.transition });
                }
              }}
              disabled={transition.isPending}
              className={`rounded-lg text-sm font-medium px-3 py-1.5 transition-colors ${
                opt.primary
                  ? 'ink-btn text-white'
                  : 'border border-gray-300 bg-white text-gray-700 hover:border-gray-400 hover:bg-gray-50'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
        <button
          onClick={() => {
            if (confirm('Delete this post? This cannot be undone.')) remove.mutate();
          }}
          disabled={remove.isPending}
          className="text-sm text-red-600 hover:underline disabled:opacity-50"
        >
          Delete
        </button>
      </div>
      <p className="text-xs text-gray-400 text-right">
        {save.isPending ? 'Saving…' : save.data ? 'Saved' : 'Auto-saves on blur'}
      </p>
    </DrawerFrame>
  );
}

function VariantEditor({
  platform,
  value,
  onChange,
  onBlur,
  warnings,
}: {
  platform: SocialPlatform;
  value: string;
  onChange: (s: string) => void;
  onBlur: () => void;
  warnings: string[];
}) {
  const limit = PLATFORM_CHAR_LIMITS[platform];
  const len = value.length;
  const over = len > limit;
  const near = !over && len > limit * 0.9;
  const counterCls = over
    ? 'text-red-600 font-semibold'
    : near
      ? 'text-amber-700 font-medium'
      : 'text-gray-500';
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-3">
      <div className="flex items-center justify-between mb-1.5">
        <div className="text-xs font-semibold text-ink">{PLATFORM_LABELS[platform]}</div>
        <div className={`text-[11px] tabular-nums ${counterCls}`}>
          {len.toLocaleString()} / {limit.toLocaleString()}
        </div>
      </div>
      <textarea
        rows={4}
        className="w-full rounded-md border border-gray-200 px-2.5 py-1.5 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={`What goes out on ${PLATFORM_LABELS[platform]}?`}
      />
      {warnings.length > 0 && (
        <ul className="mt-1.5 space-y-0.5">
          {warnings.map((w, i) => (
            <li key={i} className="text-[11px] text-amber-700">
              • {w}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function transitionOptionsFor(status: PostStatus): {
  transition: PostTransition;
  label: string;
  primary?: boolean;
}[] {
  switch (status) {
    case 'draft':
      return [
        { transition: 'submit_for_internal_review', label: 'Submit for review', primary: true },
        { transition: 'request_client_approval', label: 'Send to client' },
      ];
    case 'internal_review':
      return [
        { transition: 'request_client_approval', label: 'Send to client', primary: true },
        { transition: 'approve', label: 'Approve' },
        { transition: 'reject', label: 'Reject' },
        { transition: 'reopen', label: 'Back to draft' },
      ];
    case 'client_review':
      return [
        { transition: 'approve', label: 'Approve', primary: true },
        { transition: 'reject', label: 'Reject' },
        { transition: 'reopen', label: 'Back to draft' },
      ];
    case 'approved':
      return [
        { transition: 'mark_posted', label: 'Mark posted', primary: true },
        { transition: 'schedule', label: 'Schedule' },
      ];
    case 'scheduled':
      return [{ transition: 'mark_posted', label: 'Mark posted', primary: true }];
    case 'posted':
      return [{ transition: 'archive', label: 'Archive' }];
    case 'rejected':
      return [{ transition: 'reopen', label: 'Reopen as draft', primary: true }];
    case 'archived':
      return [];
  }
}

function DrawerFrame({
  title,
  eyebrow,
  headerRight,
  children,
  onClose,
}: {
  title: string;
  eyebrow?: string;
  headerRight?: ReactNode;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="flex flex-col h-full">
      <div className="flex items-start justify-between gap-3 px-6 pt-6 pb-4 border-b border-gray-100">
        <div className="min-w-0 flex-1">
          {eyebrow && <Eyebrow>{eyebrow}</Eyebrow>}
          <h2 className="text-lg font-semibold tracking-tightish text-ink mt-0.5 truncate">
            {title}
          </h2>
        </div>
        <div className="flex items-center gap-2 flex-none">
          {headerRight}
          <button onClick={onClose} className="text-gray-500 hover:text-ink text-2xl leading-none">
            ×
          </button>
        </div>
      </div>
      <div className="px-6 py-5 space-y-5 flex-1">{children}</div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>
      {children}
    </label>
  );
}

// --------------------- date helpers ---------------------

function mondayOf(d: Date): Date {
  const out = new Date(d);
  out.setHours(0, 0, 0, 0);
  const dow = out.getDay(); // 0=Sun..6=Sat
  const delta = (dow + 6) % 7; // distance back to Monday
  out.setDate(out.getDate() - delta);
  return out;
}

function addDays(d: Date, n: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + n);
  return out;
}

function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`;
}

function formatWeekRange(start: Date): string {
  const end = addDays(start, 6);
  const sameMonth = start.getMonth() === end.getMonth();
  const startFmt = start.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
  });
  const endFmt = end.toLocaleDateString('en-US', {
    month: sameMonth ? undefined : 'short',
    day: 'numeric',
    year: end.getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined,
  });
  return `${startFmt} – ${endFmt}`;
}

function defaultPostingTime(day: Date): Date {
  const out = new Date(day);
  out.setHours(9, 0, 0, 0); // 9am local
  return out;
}

function toLocalInput(iso: string): string {
  // Convert UTC ISO to a value the <input type="datetime-local"> accepts.
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}
