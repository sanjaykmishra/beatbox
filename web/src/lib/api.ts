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
  workspace: () => request<Workspace>('GET', '/v1/workspace'),
  updateWorkspace: (
    b: Partial<{ name: string; logo_url: string; primary_color: string; default_template_id: string }>,
  ) => request<Workspace>('PATCH', '/v1/workspace', b),
  listClients: () => request<ListResponse<Client>>('GET', '/v1/clients'),
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
  addCoverage: (reportId: string, urls: string[]) =>
    request<{ items: { id: string; source_url: string; extraction_status: string }[] }>(
      'POST',
      `/v1/reports/${reportId}/coverage`,
      { urls },
    ),
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
  pdf_url: string | null;
  share_token: string | null;
  generated_at: string | null;
  created_at: string;
  coverage_items: CoverageItemView[];
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
