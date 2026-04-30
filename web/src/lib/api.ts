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
