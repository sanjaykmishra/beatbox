const TOKEN_KEY = 'beat.session';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  status: number;
  problem: ProblemDetail;
  constructor(problem: ProblemDetail, status: number) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.status = status;
    this.problem = problem;
  }
}

export type ProblemDetail = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  request_id?: string;
};

export type User = { id: string; email: string; name: string };
export type Workspace = {
  id: string;
  name: string;
  slug: string;
  logo_url: string | null;
  primary_color: string | null;
  plan: string;
  plan_limit_clients: number;
  plan_limit_reports_monthly: number;
  trial_ends_at: string | null;
  default_template_id: string | null;
};
export type AuthResponse = { user: User; workspace: { id: string; name: string; slug: string; plan: string; trial_ends_at: string | null }; session_token: string };
export type Client = {
  id: string;
  name: string;
  logo_url: string | null;
  primary_color: string | null;
  notes: string | null;
  default_cadence: string | null;
  created_at: string;
};
export type ListResponse<T> = { items: T[]; next_cursor: string | null };

// Augmented client list per docs/16-client-dashboard.md.
export type ClientListItem = {
  id: string;
  name: string;
  logo_url: string | null;
  primary_color: string | null;
  default_cadence: string | null;
  created_at: string;
  alerts_summary: AlertsSummary;
};
export type AlertsSummary = {
  total_score: number;
  by_severity: { red: number; amber: number; blue: number };
  top_badges: { alert_type: string; severity: Severity; label: string }[];
  overflow_count: number;
};
export type Severity = 'red' | 'amber' | 'blue' | 'green';
export type ClientListResponse = {
  items: ClientListItem[];
  workspace_summary: {
    total_clients: number;
    total_attention_items: number;
    by_severity: { red: number; amber: number; blue: number };
  };
  next_cursor: string | null;
};

export type DashboardStat = {
  value: number;
  delta_pct: number | null;
  delta_pts: number | null;
  delta_label: string;
};
export type AlertCard = {
  alert_type: string;
  severity: Severity;
  count: number;
  badge_label: string;
  card_title: string;
  card_subtitle: string | null;
  card_action_label: string | null;
  card_action_path: string | null;
};
export type ComingUpItem = {
  kind: string;
  title: string;
  subtitle: string | null;
  path: string | null;
};
export type ActivityItem = {
  occurred_at: string;
  kind: string;
  label: string;
  detail: string | null;
  tag: { label: string; tone: string } | null;
  actor_label: string | null;
};
export type ClientDashboard = {
  client: {
    id: string;
    name: string;
    logo_url: string | null;
    primary_color: string | null;
    default_cadence: string | null;
    setup_dismissed_at: string | null;
  };
  stats_30d: {
    coverage_count: DashboardStat;
    tier_1_count: DashboardStat;
    sentiment: DashboardStat;
    reach: DashboardStat;
  };
  alerts: AlertCard[];
  coming_up: ComingUpItem[];
  recent_activity: ActivityItem[];
};

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? (JSON.parse(text) as unknown) : null;
  if (!res.ok) {
    throw new ApiError((data as ProblemDetail) ?? {}, res.status);
  }
  return data as T;
}

export const api = {
  signup: (b: { email: string; password: string; name: string; workspace_name: string }) =>
    request<AuthResponse>('POST', '/v1/auth/signup', b),
  login: (b: { email: string; password: string }) =>
    request<AuthResponse>('POST', '/v1/auth/login', b),
  logout: () => request<void>('POST', '/v1/auth/logout'),
  me: () => request<User>('GET', '/v1/auth/me'),
  workspace: () => request<Workspace>('GET', '/v1/workspace'),
  updateWorkspace: (
    b: Partial<{ name: string; logo_url: string; primary_color: string; default_template_id: string }>,
  ) => request<Workspace>('PATCH', '/v1/workspace', b),
  listClients: () => request<ClientListResponse>('GET', '/v1/clients'),
  createClient: (b: {
    name: string;
    logo_url?: string;
    primary_color?: string;
    notes?: string;
    default_cadence?: string;
  }) => request<Client>('POST', '/v1/clients', b),
  getClient: (id: string) => request<Client>('GET', `/v1/clients/${id}`),
  updateClient: (
    id: string,
    b: Partial<{
      name: string;
      logo_url: string;
      primary_color: string;
      notes: string;
      default_cadence: string;
    }>,
  ) => request<Client>('PATCH', `/v1/clients/${id}`, b),
  deleteClient: (id: string) => request<void>('DELETE', `/v1/clients/${id}`),
  getClientDashboard: (id: string) =>
    request<ClientDashboard>('GET', `/v1/clients/${id}/dashboard`),
  getClientActivity: (id: string, limit = 100) =>
    request<ActivityItem[]>('GET', `/v1/clients/${id}/activity?limit=${limit}`),
  refreshClientAlerts: (id: string) =>
    request<AlertCard[]>('POST', `/v1/clients/${id}/alerts/refresh`),
  dismissSetup: (id: string) =>
    request<void>('POST', `/v1/clients/${id}/setup-checklist/dismiss`),
  presignUpload: (b: { purpose: 'logo' | 'client_logo'; content_type: string }) =>
    request<{ url: string; key: string; public_url: string; expires_in: number }>(
      'POST',
      '/v1/uploads/presign',
      b,
    ),

  // ----- Reports + coverage (week 5) -----
  createReport: (
    clientId: string,
    b: { title?: string; period_start: string; period_end: string; template_id?: string },
  ) => request<Report>('POST', `/v1/clients/${clientId}/reports`, b),
  getReport: (id: string) => request<Report>('GET', `/v1/reports/${id}`),
  listClientReports: (clientId: string) =>
    request<ReportSummary[]>('GET', `/v1/clients/${clientId}/reports`),
  addCoverage: (reportId: string, urls: string[]) =>
    request<{
      items: {
        id: string;
        source_url: string;
        extraction_status: string;
        kind: 'article' | 'social';
        platform: SocialPlatformId | null;
      }[];
    }>('POST', `/v1/reports/${reportId}/coverage`, { urls }),
  patchCoverage: (
    reportId: string,
    itemId: string,
    edits: Partial<{
      headline: string;
      subheadline: string;
      publish_date: string;
      lede: string;
      summary: string;
      key_quote: string;
      sentiment: 'positive' | 'neutral' | 'negative' | 'mixed';
      sentiment_rationale: string;
      subject_prominence: 'feature' | 'mention' | 'passing';
      topics: string[];
    }>,
  ) =>
    request<EditedCoverage>('PATCH', `/v1/reports/${reportId}/coverage/${itemId}`, edits),
  retryCoverage: (reportId: string, itemId: string) =>
    request<void>('POST', `/v1/reports/${reportId}/coverage/${itemId}/retry`),
  deleteCoverage: (reportId: string, itemId: string) =>
    request<void>('DELETE', `/v1/reports/${reportId}/coverage/${itemId}`),
  patchSocialMention: (
    reportId: string,
    itemId: string,
    edits: Partial<{
      summary: string;
      sentiment: 'positive' | 'neutral' | 'negative' | 'mixed';
      sentiment_rationale: string;
      subject_prominence: 'feature' | 'mention' | 'passing';
      topics: string[];
    }>,
  ) =>
    request<{
      id: string;
      summary: string | null;
      sentiment: string | null;
      sentiment_rationale: string | null;
      subject_prominence: string | null;
      topics: string[];
      is_user_edited: boolean;
      edited_fields: string[];
    }>('PATCH', `/v1/reports/${reportId}/social-mentions/${itemId}`, edits),
  retrySocialMention: (reportId: string, itemId: string) =>
    request<void>('POST', `/v1/reports/${reportId}/social-mentions/${itemId}/retry`),
  deleteSocialMention: (reportId: string, itemId: string) =>
    request<void>('DELETE', `/v1/reports/${reportId}/social-mentions/${itemId}`),

  // ----- Client context (docs/15-additions.md §15.1) -----
  getClientContext: (clientId: string) =>
    request<ClientContext>('GET', `/v1/clients/${clientId}/context`),
  putClientContext: (clientId: string, b: ClientContextInput) =>
    request<ClientContext>('PUT', `/v1/clients/${clientId}/context`, b),

  // ----- Report generation, share, PDF (week 6) -----
  generateReport: (id: string) =>
    request<{ id: string; status: string }>('POST', `/v1/reports/${id}/generate`),
  shareReport: (id: string, expires_in_days?: number) =>
    request<{ share_url: string; expires_at: string }>(
      'POST',
      `/v1/reports/${id}/share`,
      expires_in_days ? { expires_in_days } : {},
    ),
  revokeShare: (id: string) => request<void>('DELETE', `/v1/reports/${id}/share`),
  editSummary: (id: string, summary: string) =>
    request<{ id: string; executive_summary: string; executive_summary_edited: boolean }>(
      'PATCH',
      `/v1/reports/${id}/summary`,
      { summary },
    ),
  fetchReportPreviewHtml: async (id: string): Promise<string> => {
    const headers: Record<string, string> = {};
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`/v1/reports/${id}/preview`, { headers });
    if (!res.ok) throw new ApiError({ title: `HTTP ${res.status}` }, res.status);
    return res.text();
  },
  fetchReportPdfBlob: async (id: string): Promise<Blob> => {
    const headers: Record<string, string> = {};
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`/v1/reports/${id}/pdf`, { headers });
    if (!res.ok) throw new ApiError({ title: `HTTP ${res.status}` }, res.status);
    return res.blob();
  },

  // ----- Billing (week 8) -----
  getBilling: () => request<Billing>('GET', '/v1/billing'),
  startCheckout: (plan: 'solo' | 'agency' | 'studio', interval: 'monthly' | 'yearly') =>
    request<{ checkout_url: string }>('POST', '/v1/billing/checkout', { plan, interval }),
  openPortal: () => request<{ portal_url: string }>('POST', '/v1/billing/portal', {}),

  // ----- Members + Admin (week 9) -----
  listMembers: () => request<Member[]>('GET', '/v1/workspace/members'),
  adminWhoami: () => request<{ is_admin: boolean }>('GET', '/v1/admin/whoami'),
  adminDashboard: () => request<AdminDashboard>('GET', '/v1/admin/dashboard'),

  // ----- Owned posts / editorial calendar (Phase 1.5 §17.2) -----
  listPosts: (params?: ListPostsParams) =>
    request<{ items: OwnedPost[] }>('GET', `/v1/posts${qs(params)}`),
  getPost: (id: string) => request<OwnedPost>('GET', `/v1/posts/${id}`),
  createPost: (b: CreatePostInput) => request<OwnedPost>('POST', '/v1/posts', b),
  updatePost: (id: string, b: UpdatePostInput) =>
    request<OwnedPost>('PATCH', `/v1/posts/${id}`, b),
  transitionPost: (id: string, transition: PostTransition, body?: { reason?: string }) =>
    request<OwnedPost>('POST', `/v1/posts/${id}/transitions/${transition}`, body ?? {}),
  regenerateVariants: (id: string, platforms?: string[]) =>
    request<RegenerateVariantsResponse>(
      'POST',
      `/v1/posts/${id}/regenerate-variants`,
      platforms ? { platforms } : {},
    ),
  deletePost: (id: string) => request<void>('DELETE', `/v1/posts/${id}`),

  // ----- Generalized calendar (V008) -----
  getCalendarFeed: (params?: CalendarFeedParams) =>
    request<CalendarFeedResponse>('GET', `/v1/calendar/feed${qs(params)}`),
  createCalendarEvent: (b: CreateCalendarEventInput) =>
    request<CalendarEventEntry>('POST', '/v1/calendar/events', b),
  getCalendarEvent: (id: string) =>
    request<CalendarEventEntry>('GET', `/v1/calendar/events/${id}`),
  updateCalendarEvent: (id: string, b: UpdateCalendarEventInput) =>
    request<CalendarEventEntry>('PATCH', `/v1/calendar/events/${id}`, b),
  deleteCalendarEvent: (id: string) =>
    request<void>('DELETE', `/v1/calendar/events/${id}`),
};

/** Standalone calendar event subtype literals (mirrors backend CHECK constraint). */
export type CalendarEventType =
  | 'embargo'
  | 'launch'
  | 'earnings'
  | 'meeting'
  | 'blackout'
  | 'milestone'
  | 'other';

/** Feed-item discriminator. Includes the post + report_due aggregate types. */
export type FeedItemType = 'post' | 'report_due' | CalendarEventType;

export type FeedItem = {
  id: string;
  type: FeedItemType;
  source_id: string;
  client_id: string | null;
  title: string;
  subtitle: string | null;
  occurs_at: string;
  ends_at: string | null;
  all_day: boolean;
  href: string | null;
  color: string | null;
  payload: Record<string, unknown>;
};

export type CalendarFeedResponse = {
  items: FeedItem[];
  available_types: FeedItemType[];
};

export type CalendarFeedParams = {
  client_id?: string;
  types?: string;
  from?: string;
  to?: string;
};

export type CalendarEventEntry = {
  id: string;
  workspace_id: string;
  client_id: string | null;
  event_type: CalendarEventType;
  title: string;
  description: string | null;
  occurs_at: string;
  ends_at: string | null;
  all_day: boolean;
  url: string | null;
  color: string | null;
  created_by_user_id: string | null;
  created_at: string;
  updated_at: string;
};

export type CreateCalendarEventInput = {
  client_id?: string;
  event_type: CalendarEventType;
  title: string;
  description?: string;
  occurs_at: string;
  ends_at?: string;
  all_day?: boolean;
  url?: string;
  color?: string;
};

export type UpdateCalendarEventInput = Partial<CreateCalendarEventInput>;

export type RegenerateVariantsResponse = {
  variants: Record<string, PlatformVariant>;
  warnings: Record<string, string[]>;
  prompt_version: string;
  post: OwnedPost;
};

function qs(params?: Record<string, string | number | undefined | null>): string {
  if (!params) return '';
  const parts: string[] = [];
  for (const [k, v] of Object.entries(params)) {
    if (v == null || v === '') continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

export type SocialPlatform =
  | 'x'
  | 'linkedin'
  | 'bluesky'
  | 'threads'
  | 'instagram'
  | 'facebook'
  | 'tiktok'
  | 'reddit'
  | 'substack'
  | 'youtube'
  | 'mastodon';

export type PostStatus =
  | 'draft'
  | 'internal_review'
  | 'client_review'
  | 'approved'
  | 'scheduled'
  | 'posted'
  | 'rejected'
  | 'archived';

export type PostTransition =
  | 'submit_for_internal_review'
  | 'request_client_approval'
  | 'approve'
  | 'schedule'
  | 'mark_posted'
  | 'reject'
  | 'archive'
  | 'reopen';

export type PlatformVariant = {
  content: string;
  char_count: number | null;
  edited_at: string | null;
};

export type OwnedPost = {
  id: string;
  workspace_id: string;
  client_id: string;
  title: string | null;
  primary_content_text: string | null;
  platform_variants: Record<string, PlatformVariant>;
  target_platforms: SocialPlatform[];
  scheduled_for: string | null;
  timezone: string;
  status: PostStatus;
  series_tag: string | null;
  drafted_by_user_id: string | null;
  submitted_for_review_at: string | null;
  approved_at: string | null;
  posted_at: string | null;
  asset_ids: string[];
  created_at: string;
  updated_at: string;
};

export type ListPostsParams = {
  client_id?: string;
  status?: PostStatus;
  series_tag?: string;
  platform?: SocialPlatform;
  from?: string;
  to?: string;
  limit?: number;
};

export type CreatePostInput = {
  client_id: string;
  title?: string;
  primary_content_text?: string;
  target_platforms?: SocialPlatform[];
  scheduled_for?: string;
  timezone?: string;
  series_tag?: string;
};

export type UpdatePostInput = Partial<{
  title: string;
  primary_content_text: string;
  platform_variants: Record<string, PlatformVariant>;
  target_platforms: SocialPlatform[];
  scheduled_for: string;
  timezone: string;
  series_tag: string;
  asset_ids: string[];
}>;

export type Member = {
  user_id: string;
  email: string;
  name: string;
  role: 'owner' | 'member' | 'viewer';
  member_since: string;
  last_login_at: string | null;
};

export type AdminDashboard = {
  daily_extractions: { day: string; count: number }[];
  daily_reports: { day: string; count: number }[];
  workspace_costs: {
    workspace_id: string;
    workspace_name: string;
    extractions: number;
    reports: number;
    cost_usd: number;
  }[];
  p95_extraction_ms: number | null;
  top_errors: { error_class: string; count: number }[];
};

export type Billing = {
  plan: 'trial' | 'solo' | 'agency' | 'studio' | 'enterprise';
  plan_limit_clients: number;
  plan_limit_reports_monthly: number;
  trial_ends_at: string | null;
  grandfathered_until: string | null;
  stripe_customer_id: string | null;
  stripe_subscription_id: string | null;
  stripe_configured: boolean;
};

/** Build URLs that the browser navigates to (PDF download follows the 302 redirect). */
export function previewUrl(reportId: string): string {
  return `/v1/reports/${reportId}/preview`;
}
export function pdfDownloadUrl(reportId: string): string {
  return `/v1/reports/${reportId}/pdf`;
}

export type ClientContext = {
  id: string;
  client_id: string;
  key_messages: string | null;
  do_not_pitch: string | null;
  competitive_set: string | null;
  important_dates: string | null;
  style_notes: string | null;
  notes_markdown: string | null;
  version: number;
  last_edited_by_user_id: string | null;
  updated_at: string;
};

export type ClientContextInput = Partial<{
  key_messages: string;
  do_not_pitch: string;
  competitive_set: string;
  important_dates: string;
  style_notes: string;
  notes_markdown: string;
}>;

export type SocialPlatformId =
  | 'x'
  | 'linkedin'
  | 'bluesky'
  | 'threads'
  | 'instagram'
  | 'facebook'
  | 'tiktok'
  | 'reddit'
  | 'substack'
  | 'youtube'
  | 'mastodon';

export type SocialMentionView = {
  id: string;
  source_url: string;
  platform: SocialPlatformId;
  extraction_status: 'queued' | 'running' | 'done' | 'failed';
  extraction_error: string | null;
  author_handle: string | null;
  author_display_name: string | null;
  author_avatar_url: string | null;
  author_profile_url: string | null;
  author_follower_count: number | null;
  author_verified: boolean;
  posted_at: string | null;
  content_text: string | null;
  summary: string | null;
  key_excerpt: string | null;
  sentiment: 'positive' | 'neutral' | 'negative' | 'mixed' | null;
  sentiment_rationale: string | null;
  subject_prominence: 'feature' | 'mention' | 'passing' | null;
  topics: string[];
  media_summary: string | null;
  media_urls: string[];
  likes_count: number | null;
  reposts_count: number | null;
  replies_count: number | null;
  views_count: number | null;
  estimated_reach: number | null;
  is_user_edited: boolean;
  edited_fields: string[];
};

export type ReportStatusCounts = {
  total: number;
  done: number;
  extracting: number;
  failed: number;
  articles: number;
  social: number;
};

export type ReportSummary = {
  id: string;
  title: string;
  period_start: string;
  period_end: string;
  status: 'draft' | 'processing' | 'ready' | 'failed';
  generated_at: string | null;
  created_at: string;
};

export type Report = {
  id: string;
  client_id: string;
  workspace_id: string;
  template_id: string;
  title: string;
  period_start: string;
  period_end: string;
  status: 'draft' | 'processing' | 'ready' | 'failed';
  executive_summary: string | null;
  executive_summary_edited?: boolean;
  pdf_url: string | null;
  share_token: string | null;
  failure_reason: string | null;
  generated_at: string | null;
  created_at: string;
  coverage_items: CoverageItemView[];
  social_mentions: SocialMentionView[];
  status_counts: ReportStatusCounts;
};

export type CoverageItemView = {
  id: string;
  source_url: string;
  extraction_status: 'queued' | 'running' | 'done' | 'failed';
  extraction_error: string | null;
  outlet: { id: string; name: string; tier: number } | null;
  headline: string | null;
  publish_date: string | null;
  lede: string | null;
  sentiment: 'positive' | 'neutral' | 'negative' | 'mixed' | null;
  screenshot_url: string | null;
  tier_at_extraction: number | null;
  estimated_reach: number | null;
  is_user_edited: boolean;
  edited_fields: string[];
};

export type EditedCoverage = {
  id: string;
  headline: string | null;
  subheadline: string | null;
  publish_date: string | null;
  lede: string | null;
  summary: string | null;
  key_quote: string | null;
  sentiment: 'positive' | 'neutral' | 'negative' | 'mixed' | null;
  sentiment_rationale: string | null;
  subject_prominence: 'feature' | 'mention' | 'passing' | null;
  topics: string[];
  is_user_edited: boolean;
  edited_fields: string[];
};

export async function uploadLogo(
  file: File,
  purpose: 'logo' | 'client_logo',
): Promise<string> {
  const presign = await api.presignUpload({ purpose, content_type: file.type });
  const put = await fetch(presign.url, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
  });
  if (!put.ok) throw new Error(`Upload failed: HTTP ${put.status}`);
  return presign.public_url;
}
