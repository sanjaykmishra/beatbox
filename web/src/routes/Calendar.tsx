import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router-dom';
import { Avatar } from '../components/Avatar';
import { BrowserFrame } from '../components/BrowserFrame';
import { Eyebrow, Pill, type PillTone } from '../components/ui';
import { useAuth } from '../lib/useAuth';
import {
  ApiError,
  api,
  type CalendarEventType,
  type ClientListItem,
  type FeedItem,
  type FeedItemType,
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

type CalendarView = 'week' | 'month';

export function Calendar() {
  const { workspace } = useAuth();
  const slug = workspace?.slug ?? 'workspace';
  const [searchParams, setSearchParams] = useSearchParams();
  const initialClientFilter = searchParams.get('client_id') ?? undefined;
  const [view, setView] = useState<CalendarView>('week');
  const [anchor, setAnchor] = useState(() => new Date());
  const [clientFilter, setClientFilterState] = useState<string | undefined>(initialClientFilter);
  const [needsReview, setNeedsReview] = useState(false);

  // Keep ?client_id=… in sync with the filter so the URL is shareable / bookmarkable.
  function setClientFilter(id: string | undefined) {
    setClientFilterState(id);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (id) next.set('client_id', id);
        else next.delete('client_id');
        return next;
      },
      { replace: true },
    );
  }
  const [composer, setComposer] = useState<ComposerState>({ open: false });
  const [eventDrawer, setEventDrawer] = useState<EventDrawerState>({ open: false });
  const [activeTypes, setActiveTypes] = useState<Set<FeedItemType> | null>(null);

  const clientsQ = useQuery({ queryKey: ['clients'], queryFn: api.listClients });

  // Lightweight count for the "Needs review" pill: posts currently in internal_review.
  const reviewCountQ = useQuery({
    queryKey: ['posts-review-count'],
    queryFn: () => api.listPosts({ status: 'internal_review', limit: 200 }),
    refetchInterval: 60_000,
  });
  const reviewCount = reviewCountQ.data?.items.length ?? 0;

  const range = useMemo(() => visibleRange(view, anchor), [view, anchor]);

  // Posts query (still used for the "Needs review" filter list).
  const postsQ = useQuery({
    queryKey: ['posts', clientFilter, view, needsReview, range.from.toISOString()],
    queryFn: () =>
      api.listPosts({
        client_id: clientFilter,
        status: needsReview ? 'internal_review' : undefined,
        from: needsReview ? undefined : range.from.toISOString(),
        to: needsReview ? undefined : range.to.toISOString(),
        limit: 500,
      }),
    enabled: needsReview,
  });

  // Unified calendar feed (posts + reports + standalone events).
  const typesParam = activeTypes
    ? Array.from(activeTypes).sort().join(',') || undefined
    : undefined;
  const feedQ = useQuery({
    queryKey: [
      'calendar-feed',
      clientFilter,
      view,
      range.from.toISOString(),
      typesParam ?? 'all',
    ],
    queryFn: () =>
      api.getCalendarFeed({
        client_id: clientFilter,
        types: typesParam,
        from: range.from.toISOString(),
        to: range.to.toISOString(),
      }),
    enabled: !needsReview,
  });
  const availableTypes = feedQ.data?.available_types ?? DEFAULT_FEED_TYPES;
  const feedItems = feedQ.data?.items ?? [];

  const clientsById = useMemo(() => {
    const m = new Map<string, ClientListItem>();
    clientsQ.data?.items.forEach((c) => m.set(c.id, c));
    return m;
  }, [clientsQ.data]);

  function shift(delta: number) {
    setAnchor((d) => (view === 'week' ? addDays(d, delta * 7) : addMonths(d, delta)));
  }

  return (
    <BrowserFrame
      crumbs={[{ label: `${slug}.beat.app`, to: '/clients' }, { label: 'calendar' }]}
      rightSlot={
        <NewMenu
          onPickPost={() => setComposer({ open: true, mode: 'new', clientId: clientFilter })}
          onPickEvent={() => setEventDrawer({ open: true, mode: 'new', clientId: clientFilter })}
        />
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
              onClick={() => shift(-1)}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-2.5 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
              aria-label={view === 'week' ? 'Previous week' : 'Previous month'}
            >
              ‹
            </button>
            <button
              onClick={() => setAnchor(new Date())}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-3 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
            >
              {view === 'week' ? 'This week' : 'This month'}
            </button>
            <button
              onClick={() => shift(1)}
              className="rounded-md border border-gray-300 bg-white text-gray-700 px-2.5 py-1.5 text-sm hover:border-gray-400 hover:bg-gray-50"
              aria-label={view === 'week' ? 'Next week' : 'Next month'}
            >
              ›
            </button>
            <span className="ml-3 text-sm text-gray-700 font-medium">
              {view === 'week' ? formatWeekRange(range.from) : formatMonthLabel(anchor)}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setNeedsReview((v) => !v)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium border transition-colors flex items-center gap-1.5 ${
                needsReview
                  ? 'bg-amber-100 border-amber-200 text-amber-800'
                  : 'bg-white border-gray-300 text-gray-700 hover:border-gray-400 hover:bg-gray-50'
              }`}
              title={
                needsReview
                  ? 'Showing only posts awaiting internal review. Click to clear.'
                  : 'Show only posts submitted for internal review across all weeks.'
              }
            >
              Needs review
              {reviewCount > 0 && (
                <span
                  className={`tabular-nums text-[11px] font-semibold rounded-full px-1.5 py-0.5 ${
                    needsReview ? 'bg-amber-200 text-amber-900' : 'bg-amber-100 text-amber-800'
                  }`}
                >
                  {reviewCount}
                </span>
              )}
            </button>
            <ViewToggle value={view} onChange={setView} />
            <ClientFilter
              clients={clientsQ.data?.items ?? []}
              value={clientFilter}
              onChange={setClientFilter}
            />
          </div>
        </div>

        {!needsReview && (
          <TypeChipStrip
            available={availableTypes}
            active={activeTypes}
            onChange={setActiveTypes}
          />
        )}

        {needsReview ? (
          <ReviewList
            posts={postsQ.data?.items ?? []}
            clientsById={clientsById}
            loading={postsQ.isLoading}
            onSelect={(p) => setComposer({ open: true, mode: 'edit', postId: p.id })}
          />
        ) : view === 'week' ? (
          <WeekGrid
            weekStart={range.from}
            items={feedItems}
            clientsById={clientsById}
            loading={feedQ.isLoading}
            onSelect={(item) => openItem(item, setComposer, setEventDrawer)}
            onEmptyDay={(day) =>
              setComposer({
                open: true,
                mode: 'new',
                clientId: clientFilter,
                // Don't pre-fill a past date — let the user pick. The composer's min={today}
                // would otherwise reject the pre-filled value silently.
                scheduledFor: isPastDay(day) ? undefined : day,
              })
            }
          />
        ) : (
          <MonthGrid
            anchor={anchor}
            items={feedItems}
            clientsById={clientsById}
            loading={feedQ.isLoading}
            onSelect={(item) => openItem(item, setComposer, setEventDrawer)}
            onEmptyDay={(day) =>
              setComposer({
                open: true,
                mode: 'new',
                clientId: clientFilter,
                // Don't pre-fill a past date — let the user pick. The composer's min={today}
                // would otherwise reject the pre-filled value silently.
                scheduledFor: isPastDay(day) ? undefined : day,
              })
            }
          />
        )}
      </div>

      {composer.open && (
        <ComposerDrawer
          state={composer}
          clients={clientsQ.data?.items ?? []}
          onClose={() => setComposer({ open: false })}
        />
      )}
      {eventDrawer.open && (
        <CalendarEventDrawer
          state={eventDrawer}
          clients={clientsQ.data?.items ?? []}
          onClose={() => setEventDrawer({ open: false })}
        />
      )}
    </BrowserFrame>
  );
}

type ComposerState =
  | { open: false }
  | { open: true; mode: 'new'; clientId?: string; scheduledFor?: Date }
  | { open: true; mode: 'edit'; postId: string };

type EventDrawerState =
  | { open: false }
  | { open: true; mode: 'new'; clientId?: string; occursAt?: Date }
  | { open: true; mode: 'edit'; eventId: string };

const DEFAULT_FEED_TYPES: FeedItemType[] = [
  'post',
  'report_due',
  'embargo',
  'launch',
  'earnings',
  'meeting',
  'blackout',
  'milestone',
  'other',
];

const FEED_TYPE_LABELS: Record<FeedItemType, string> = {
  post: 'Posts',
  report_due: 'Reports',
  embargo: 'Embargo',
  launch: 'Launch',
  earnings: 'Earnings',
  meeting: 'Meeting',
  blackout: 'Blackout',
  milestone: 'Milestone',
  other: 'Other',
};

const FEED_TYPE_DOT: Record<FeedItemType, string> = {
  post: '#0b0f19',
  report_due: '#059669',
  embargo: '#dc2626',
  launch: '#7c3aed',
  earnings: '#0891b2',
  meeting: '#2563eb',
  blackout: '#374151',
  milestone: '#d97706',
  other: '#9ca3af',
};

const CALENDAR_EVENT_TYPES: CalendarEventType[] = [
  'embargo',
  'launch',
  'earnings',
  'meeting',
  'blackout',
  'milestone',
  'other',
];

// --------------------- Week grid ---------------------

function WeekGrid({
  weekStart,
  items,
  clientsById,
  loading,
  onSelect,
  onEmptyDay,
}: {
  weekStart: Date;
  items: FeedItem[];
  clientsById: Map<string, ClientListItem>;
  loading: boolean;
  onSelect: (item: FeedItem) => void;
  onEmptyDay: (day: Date) => void;
}) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const byDay = new Map<string, FeedItem[]>();
  for (const it of items) {
    const k = ymd(new Date(it.occurs_at));
    const list = byDay.get(k) ?? [];
    list.push(it);
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
              <div className="text-[11px] text-gray-300 italic">Nothing</div>
            ) : (
              <ul className="space-y-1.5">
                {list
                  .sort((a, b) => a.occurs_at.localeCompare(b.occurs_at) || a.id.localeCompare(b.id))
                  .map((it) => (
                    <li key={it.id}>
                      <FeedCard
                        item={it}
                        client={it.client_id ? clientsById.get(it.client_id) : undefined}
                        onClick={() => onSelect(it)}
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

function FeedCard({
  item,
  client,
  onClick,
}: {
  item: FeedItem;
  client: ClientListItem | undefined;
  onClick: () => void;
}) {
  const time = item.all_day
    ? null
    : new Date(item.occurs_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const dotColor = item.color
    ? `#${item.color}`
    : FEED_TYPE_DOT[item.type] ?? FEED_TYPE_DOT.other;
  return (
    <button
      onClick={onClick}
      className="w-full text-left bg-gray-50 hover:bg-white border border-gray-200 hover:border-gray-300 rounded-md p-2 transition-colors"
    >
      <div className="flex items-center gap-1.5 mb-1">
        <span
          className="h-1.5 w-1.5 rounded-full flex-none"
          style={{ background: dotColor }}
          aria-hidden
        />
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
      <div className="text-xs font-medium text-ink line-clamp-2">{item.title}</div>
      <div className="mt-1.5 flex items-center justify-between gap-1">
        <span className="text-[9px] uppercase tracking-wider text-gray-500">
          {FEED_TYPE_LABELS[item.type]}
        </span>
        {item.subtitle && (
          <span className="text-[9px] text-gray-400 truncate ml-1">{item.subtitle}</span>
        )}
      </div>
    </button>
  );
}

// --------------------- Review list ---------------------

function ReviewList({
  posts,
  clientsById,
  loading,
  onSelect,
}: {
  posts: OwnedPost[];
  clientsById: Map<string, ClientListItem>;
  loading: boolean;
  onSelect: (p: OwnedPost) => void;
}) {
  if (loading && posts.length === 0) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl p-5">
        <div className="h-3 w-1/3 bg-gray-100 rounded animate-pulse" />
      </div>
    );
  }
  if (posts.length === 0) {
    return (
      <div className="bg-white rounded-xl border border-dashed border-gray-300 p-10 text-center">
        <p className="text-sm font-medium text-ink">Nothing waiting on you</p>
        <p className="text-sm text-gray-500 mt-1">
          When a teammate clicks "Submit for review" on a post, it shows up here. You'll also get
          an email if your workspace has email configured.
        </p>
      </div>
    );
  }
  const sorted = [...posts].sort((a, b) => {
    const aSub = a.submitted_for_review_at ?? a.updated_at;
    const bSub = b.submitted_for_review_at ?? b.updated_at;
    return bSub.localeCompare(aSub);
  });
  return (
    <ul className="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100 overflow-hidden">
      {sorted.map((p) => {
        const client = clientsById.get(p.client_id);
        const summary =
          p.title?.trim() ||
          (p.primary_content_text || '').slice(0, 120) ||
          '(empty draft)';
        const submittedAgo = p.submitted_for_review_at
          ? relativeTime(p.submitted_for_review_at)
          : null;
        const scheduled = p.scheduled_for
          ? new Date(p.scheduled_for).toLocaleDateString(undefined, {
              month: 'short',
              day: 'numeric',
            })
          : 'no date';
        return (
          <li key={p.id}>
            <button
              onClick={() => onSelect(p)}
              className="w-full text-left flex items-start gap-4 px-5 py-3.5 hover:bg-gray-50 transition-colors"
            >
              {client && (
                <Avatar
                  name={client.name}
                  logoUrl={client.logo_url}
                  primaryColor={client.primary_color}
                  size="sm"
                />
              )}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-sm font-medium text-ink truncate">{summary}</span>
                  <Pill tone={STATUS_TONE[p.status]} className="!text-[10px] !px-1.5 !py-0">
                    {p.status.replace('_', ' ')}
                  </Pill>
                </div>
                <div className="text-xs text-gray-500 mt-0.5 truncate">
                  {client?.name ?? '—'}
                  <span className="mx-1.5 text-gray-300">·</span>
                  {p.target_platforms.length} platform
                  {p.target_platforms.length === 1 ? '' : 's'}
                  <span className="mx-1.5 text-gray-300">·</span>
                  scheduled {scheduled}
                </div>
              </div>
              {submittedAgo && (
                <span className="text-xs text-gray-400 tabular-nums flex-none mt-0.5">
                  submitted {submittedAgo}
                </span>
              )}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

// --------------------- Month grid ---------------------

function MonthGrid({
  anchor,
  items,
  clientsById,
  loading,
  onSelect,
  onEmptyDay,
}: {
  anchor: Date;
  items: FeedItem[];
  clientsById: Map<string, ClientListItem>;
  loading: boolean;
  onSelect: (item: FeedItem) => void;
  onEmptyDay: (day: Date) => void;
}) {
  const gridStart = mondayOf(firstOfMonth(anchor));
  // Render until we've passed the end of the month, padded to a full week.
  const monthIdx = anchor.getMonth();
  const cells: Date[] = [];
  for (let i = 0; i < 42; i++) {
    const d = addDays(gridStart, i);
    cells.push(d);
    // Stop after we've covered the month *and* completed the week (Sunday).
    if (i >= 27 && d.getMonth() !== monthIdx && d.getDay() === 0) break;
  }
  const byDay = new Map<string, FeedItem[]>();
  for (const it of items) {
    const k = ymd(new Date(it.occurs_at));
    const list = byDay.get(k) ?? [];
    list.push(it);
    byDay.set(k, list);
  }

  return (
    <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div className="grid grid-cols-7 bg-gray-50 border-b border-gray-200">
        {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => (
          <div
            key={d}
            className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 px-3 py-2"
          >
            {d}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {cells.map((d) => {
          const k = ymd(d);
          const list = (byDay.get(k) ?? []).sort((a, b) =>
            a.occurs_at.localeCompare(b.occurs_at),
          );
          const inMonth = d.getMonth() === monthIdx;
          const isToday = ymd(new Date()) === k;
          const visible = list.slice(0, 3);
          const overflow = list.length - visible.length;
          return (
            <div
              key={k}
              className={`min-h-[110px] border-r border-b border-gray-100 p-1.5 flex flex-col gap-1 ${
                inMonth ? 'bg-white' : 'bg-gray-50/60'
              }`}
            >
              <div className="flex items-center justify-between">
                <span
                  className={`text-[11px] font-semibold tabular-nums ${
                    isToday
                      ? 'text-white bg-ink rounded-full px-1.5 py-0.5'
                      : inMonth
                        ? 'text-gray-700'
                        : 'text-gray-400'
                  }`}
                >
                  {d.getDate()}
                </span>
                <button
                  onClick={() => onEmptyDay(d)}
                  className="text-gray-300 hover:text-ink text-sm leading-none px-1"
                  title="Draft a post on this day"
                  aria-label="Draft a post on this day"
                >
                  +
                </button>
              </div>
              {loading && list.length === 0 ? null : (
                <ul className="space-y-0.5 flex-1">
                  {visible.map((it) => {
                    const clientPrimary = it.client_id
                      ? clientsById.get(it.client_id)?.primary_color
                      : null;
                    const dot = it.color
                      ? `#${it.color}`
                      : clientPrimary
                        ? `#${clientPrimary}`
                        : FEED_TYPE_DOT[it.type] ?? FEED_TYPE_DOT.other;
                    return (
                      <li key={it.id}>
                        <button
                          onClick={() => onSelect(it)}
                          className="w-full text-left flex items-center gap-1 px-1 py-0.5 rounded hover:bg-gray-100 transition-colors"
                          title={it.title}
                        >
                          <span
                            className="h-1.5 w-1.5 rounded-full flex-none"
                            style={{ background: dot }}
                            aria-hidden
                          />
                          <span className="text-[11px] text-ink truncate">{it.title}</span>
                        </button>
                      </li>
                    );
                  })}
                  {overflow > 0 && (
                    <li>
                      <button
                        onClick={() => onSelect(list[3])}
                        className="text-[10px] text-gray-500 hover:text-ink hover:underline"
                      >
                        + {overflow} more
                      </button>
                    </li>
                  )}
                </ul>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// --------------------- View toggle ---------------------

function ViewToggle({
  value,
  onChange,
}: {
  value: CalendarView;
  onChange: (v: CalendarView) => void;
}) {
  const options: { id: CalendarView; label: string }[] = [
    { id: 'week', label: 'Week' },
    { id: 'month', label: 'Month' },
  ];
  return (
    <div className="inline-flex rounded-md border border-gray-300 bg-white p-0.5">
      {options.map((o) => (
        <button
          key={o.id}
          onClick={() => onChange(o.id)}
          className={`px-3 py-1 text-sm font-medium rounded ${
            value === o.id
              ? 'bg-gray-900 text-white'
              : 'text-gray-600 hover:text-gray-900'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

// --------------------- Type filter chips ---------------------

function TypeChipStrip({
  available,
  active,
  onChange,
}: {
  available: FeedItemType[];
  active: Set<FeedItemType> | null;
  onChange: (next: Set<FeedItemType> | null) => void;
}) {
  const all = available.length > 0 ? available : DEFAULT_FEED_TYPES;
  // null === "all" (treat all chips as on without explicitly selecting them).
  const allOn = active === null;
  const isOn = (t: FeedItemType) => allOn || (active?.has(t) ?? false);
  function toggle(t: FeedItemType) {
    const next = new Set<FeedItemType>(allOn ? all : Array.from(active ?? []));
    if (next.has(t)) next.delete(t);
    else next.add(t);
    if (next.size === 0 || next.size === all.length) onChange(null);
    else onChange(next);
  }
  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      <button
        type="button"
        onClick={() => onChange(null)}
        className={`rounded-full px-2.5 py-0.5 text-[11px] font-medium border transition-colors ${
          allOn
            ? 'border-ink bg-ink text-white'
            : 'border-gray-300 bg-white text-gray-600 hover:border-gray-400'
        }`}
      >
        All
      </button>
      {all.map((t) => {
        const on = isOn(t);
        const dot = FEED_TYPE_DOT[t] ?? FEED_TYPE_DOT.other;
        return (
          <button
            key={t}
            type="button"
            onClick={() => toggle(t)}
            className={`rounded-full px-2.5 py-0.5 text-[11px] font-medium border flex items-center gap-1.5 transition-colors ${
              on
                ? 'border-gray-400 bg-white text-gray-700'
                : 'border-gray-200 bg-gray-50 text-gray-400'
            }`}
          >
            <span
              className="h-1.5 w-1.5 rounded-full"
              style={{ background: on ? dot : '#d1d5db' }}
              aria-hidden
            />
            {FEED_TYPE_LABELS[t]}
          </button>
        );
      })}
    </div>
  );
}

// --------------------- New ▾ menu ---------------------

function NewMenu({
  onPickPost,
  onPickEvent,
}: {
  onPickPost: () => void;
  onPickEvent: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState<{ top: number; right: number } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  function toggle() {
    if (!open && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setAnchor({ top: rect.bottom + 4, right: window.innerWidth - rect.right });
    }
    setOpen((v) => !v);
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        className="ink-btn rounded-md text-white px-3 py-1 text-[12px] font-medium transition-colors flex items-center gap-1"
      >
        + New <span className="text-[10px] leading-none">▾</span>
      </button>
      {open &&
        anchor &&
        createPortal(
          <>
            <button
              type="button"
              aria-label="close menu"
              className="fixed inset-0 z-40 cursor-default"
              onClick={() => setOpen(false)}
            />
            <div
              className="fixed z-50 min-w-[200px] bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden"
              style={{ top: anchor.top, right: anchor.right }}
            >
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  onPickPost();
                }}
                className="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
              >
                <div className="font-medium text-ink">Owned post</div>
                <div className="text-[11px] text-gray-500">Multi-platform composer</div>
              </button>
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  onPickEvent();
                }}
                className="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 border-t border-gray-100"
              >
                <div className="font-medium text-ink">Calendar event</div>
                <div className="text-[11px] text-gray-500">
                  Embargo, launch, meeting, blackout, …
                </div>
              </button>
            </div>
          </>,
          document.body,
        )}
    </>
  );
}

// --------------------- Click routing ---------------------

function openItem(
  item: FeedItem,
  setComposer: React.Dispatch<React.SetStateAction<ComposerState>>,
  setEventDrawer: React.Dispatch<React.SetStateAction<EventDrawerState>>,
) {
  if (item.type === 'post') {
    setComposer({ open: true, mode: 'edit', postId: item.source_id });
  } else if (item.type === 'report_due') {
    if (item.href) window.location.href = item.href;
  } else {
    setEventDrawer({ open: true, mode: 'edit', eventId: item.source_id });
  }
}

// --------------------- Calendar event drawer ---------------------

function CalendarEventDrawer({
  state,
  clients,
  onClose,
}: {
  state: EventDrawerState;
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
      <div className="w-full max-w-md bg-white shadow-xl border-l border-gray-200 overflow-y-auto">
        {state.mode === 'edit' ? (
          <EditCalendarEvent eventId={state.eventId} clients={clients} onClose={onClose} />
        ) : (
          <NewCalendarEvent
            clients={clients}
            initialClientId={state.clientId}
            initialOccursAt={state.occursAt}
            onClose={onClose}
          />
        )}
      </div>
    </div>
  );
}

function NewCalendarEvent({
  clients,
  initialClientId,
  initialOccursAt,
  onClose,
}: {
  clients: ClientListItem[];
  initialClientId?: string;
  initialOccursAt?: Date;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [eventType, setEventType] = useState<CalendarEventType>('milestone');
  const [clientId, setClientId] = useState<string>(initialClientId ?? '');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [occursAt, setOccursAt] = useState<string>(
    initialOccursAt ? toLocalInputFromDate(defaultEventTime(initialOccursAt)) : '',
  );
  const [endsAt, setEndsAt] = useState<string>('');
  const [allDay, setAllDay] = useState(false);
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      api.createCalendarEvent({
        client_id: clientId || undefined,
        event_type: eventType,
        title: title.trim(),
        description: description.trim() || undefined,
        occurs_at: new Date(occursAt).toISOString(),
        ends_at: endsAt ? new Date(endsAt).toISOString() : undefined,
        all_day: allDay,
        url: url.trim() || undefined,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['calendar-feed'] });
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Create failed'),
  });

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-start justify-between gap-3 px-6 pt-6 pb-4 border-b border-gray-100">
        <div>
          <Eyebrow>Calendar event</Eyebrow>
          <h2 className="text-lg font-semibold tracking-tightish text-ink mt-0.5">New event</h2>
        </div>
        <button onClick={onClose} className="text-gray-500 hover:text-ink text-2xl leading-none">
          ×
        </button>
      </div>
      <div className="px-6 py-5 space-y-4 flex-1">
        {error && <p className="text-sm text-red-600">{error}</p>}

        <Field label="Type">
          <select
            value={eventType}
            onChange={(e) => setEventType(e.target.value as CalendarEventType)}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          >
            {CALENDAR_EVENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {FEED_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Client">
          <select
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          >
            <option value="">Workspace-wide</option>
            {clients.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Title">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            placeholder="e.g. Series B embargo lifts"
            autoFocus
          />
        </Field>

        <Field label="Description (optional)">
          <textarea
            rows={3}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Occurs at">
            <input
              type="datetime-local"
              value={occursAt}
              onChange={(e) => setOccursAt(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            />
          </Field>
          <Field label="Ends at (optional)">
            <input
              type="datetime-local"
              value={endsAt}
              onChange={(e) => setEndsAt(e.target.value)}
              min={occursAt || undefined}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            />
          </Field>
        </div>

        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={allDay}
            onChange={(e) => setAllDay(e.target.checked)}
          />
          All-day
        </label>

        <Field label="Link (optional)">
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            placeholder="https://…"
          />
        </Field>

        <div className="flex justify-end gap-2 pt-3 border-t border-gray-100">
          <button onClick={onClose} className="text-sm text-gray-600 hover:text-ink px-3 py-2">
            Cancel
          </button>
          <button
            onClick={() => create.mutate()}
            disabled={create.isPending || !title.trim() || !occursAt}
            className="ink-btn rounded-lg text-white px-4 py-2 text-sm font-medium disabled:opacity-50"
          >
            {create.isPending ? 'Creating…' : 'Create event'}
          </button>
        </div>
      </div>
    </div>
  );
}

function EditCalendarEvent({
  eventId,
  clients,
  onClose,
}: {
  eventId: string;
  clients: ClientListItem[];
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const eventQ = useQuery({
    queryKey: ['calendar-event', eventId],
    queryFn: () => api.getCalendarEvent(eventId),
  });
  const [hydrated, setHydrated] = useState(false);
  const [eventType, setEventType] = useState<CalendarEventType>('milestone');
  const [clientId, setClientId] = useState<string>('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [occursAt, setOccursAt] = useState<string>('');
  const [endsAt, setEndsAt] = useState<string>('');
  const [allDay, setAllDay] = useState(false);
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (eventQ.data && !hydrated) {
      const e = eventQ.data;
      setEventType(e.event_type);
      setClientId(e.client_id ?? '');
      setTitle(e.title);
      setDescription(e.description ?? '');
      setOccursAt(toLocalInput(e.occurs_at));
      setEndsAt(e.ends_at ? toLocalInput(e.ends_at) : '');
      setAllDay(e.all_day);
      setUrl(e.url ?? '');
      setHydrated(true);
    }
  }, [eventQ.data, hydrated]);

  const save = useMutation({
    mutationFn: () =>
      api.updateCalendarEvent(eventId, {
        client_id: clientId || undefined,
        event_type: eventType,
        title: title.trim() || undefined,
        description: description.trim() || undefined,
        occurs_at: occursAt ? new Date(occursAt).toISOString() : undefined,
        ends_at: endsAt ? new Date(endsAt).toISOString() : undefined,
        all_day: allDay,
        url: url.trim() || undefined,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['calendar-feed'] });
      void qc.invalidateQueries({ queryKey: ['calendar-event', eventId] });
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : 'Save failed'),
  });

  const remove = useMutation({
    mutationFn: () => api.deleteCalendarEvent(eventId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['calendar-feed'] });
      onClose();
    },
  });

  if (eventQ.isLoading || !hydrated) {
    return (
      <div className="flex flex-col h-full">
        <div className="px-6 pt-6 pb-4 border-b border-gray-100">
          <Eyebrow>Calendar event</Eyebrow>
          <h2 className="text-lg font-semibold tracking-tightish text-ink mt-0.5">Loading…</h2>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-start justify-between gap-3 px-6 pt-6 pb-4 border-b border-gray-100">
        <div>
          <Eyebrow>{FEED_TYPE_LABELS[eventType]}</Eyebrow>
          <h2 className="text-lg font-semibold tracking-tightish text-ink mt-0.5 truncate">
            {title || 'Calendar event'}
          </h2>
        </div>
        <button onClick={onClose} className="text-gray-500 hover:text-ink text-2xl leading-none">
          ×
        </button>
      </div>
      <div className="px-6 py-5 space-y-4 flex-1">
        {error && <p className="text-sm text-red-600">{error}</p>}

        <Field label="Type">
          <select
            value={eventType}
            onChange={(e) => setEventType(e.target.value as CalendarEventType)}
            onBlur={() => save.mutate()}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          >
            {CALENDAR_EVENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {FEED_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Client">
          <select
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
            onBlur={() => save.mutate()}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          >
            <option value="">Workspace-wide</option>
            {clients.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Title">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onBlur={() => save.mutate()}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          />
        </Field>

        <Field label="Description">
          <textarea
            rows={3}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            onBlur={() => save.mutate()}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Occurs at">
            <input
              type="datetime-local"
              value={occursAt}
              onChange={(e) => setOccursAt(e.target.value)}
              onBlur={() => save.mutate()}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            />
          </Field>
          <Field label="Ends at">
            <input
              type="datetime-local"
              value={endsAt}
              onChange={(e) => setEndsAt(e.target.value)}
              onBlur={() => save.mutate()}
              min={occursAt || undefined}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            />
          </Field>
        </div>

        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={allDay}
            onChange={(e) => {
              setAllDay(e.target.checked);
              setTimeout(() => save.mutate(), 0);
            }}
          />
          All-day
        </label>

        <Field label="Link">
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onBlur={() => save.mutate()}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
          />
        </Field>

        <div className="flex justify-between gap-2 pt-3 border-t border-gray-100">
          <button
            onClick={() => {
              if (confirm('Delete this event?')) remove.mutate();
            }}
            disabled={remove.isPending}
            className="text-sm text-red-600 hover:underline"
          >
            Delete
          </button>
          <p className="text-xs text-gray-400">
            {save.isPending ? 'Saving…' : 'Auto-saves on blur'}
          </p>
        </div>
      </div>
    </div>
  );
}

function defaultEventTime(day: Date): Date {
  const out = new Date(day);
  out.setHours(10, 0, 0, 0);
  return out;
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
      void qc.invalidateQueries({ queryKey: ['posts-review-count'] });
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
            min={isPrePublishStatus(post.status) ? startOfTodayLocalInput() : undefined}
            onChange={(e) => setScheduledFor(e.target.value)}
            onBlur={() => save.mutate()}
            title={
              isPrePublishStatus(post.status)
                ? 'Pick a future date. To log a post you already shipped, use Mark posted.'
                : 'Past dates are allowed for posts that have already been marked posted or archived.'
            }
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

function firstOfMonth(d: Date): Date {
  const out = new Date(d);
  out.setHours(0, 0, 0, 0);
  out.setDate(1);
  return out;
}

function addDays(d: Date, n: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + n);
  return out;
}

function addMonths(d: Date, n: number): Date {
  const out = new Date(d);
  out.setMonth(out.getMonth() + n);
  return out;
}

function visibleRange(view: CalendarView, anchor: Date): { from: Date; to: Date } {
  if (view === 'week') {
    const from = mondayOf(anchor);
    return { from, to: addDays(from, 7) };
  }
  // Month: render the grid which can extend a few days into the prior and next month.
  const from = mondayOf(firstOfMonth(anchor));
  return { from, to: addDays(from, 42) };
}

function formatMonthLabel(d: Date): string {
  return d.toLocaleDateString('en-US', {
    month: 'long',
    year: d.getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined,
  });
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

function isPastDay(d: Date): boolean {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const today = ymd(start);
  return ymd(d) < today;
}

function isPrePublishStatus(status: PostStatus): boolean {
  return (
    status === 'draft' ||
    status === 'internal_review' ||
    status === 'client_review' ||
    status === 'approved' ||
    status === 'scheduled'
  );
}

function startOfTodayLocalInput(): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return toLocalInputFromDate(d);
}

function toLocalInputFromDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
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
