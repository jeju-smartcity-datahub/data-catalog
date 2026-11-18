import type { AdminSession } from './admin';
import type { components } from './generated/api';
import { adminRoutePath, adminUrl } from './paths';

export type AuthBootstrap = components['schemas']['AuthBootstrapResponse'];
type LoginCredentials = components['schemas']['LocalLoginRequest'];

const returnRoutes = new Set(['/', '/datasets', '/metadata', '/hubs', '/classifications', '/standards', '/dcat', '/analytics', '/access']);

export function safeReturnTo(value: string | null): string {
  const fallback = adminUrl('/metadata');
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\') || Array.from(value).some(character => { const code = character.codePointAt(0) ?? 0; return code < 32 || code === 127; })) return fallback;
  try {
    const url = new URL(value, window.location.origin);
    const route = adminRoutePath(url.pathname);
    if (url.origin !== window.location.origin || !returnRoutes.has(route)) return fallback;
    return `${adminUrl(route)}${url.search}${url.hash}`;
  } catch { return fallback; }
}

export function loginUrl(returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`): string {
  return `${adminUrl('/login')}?${new URLSearchParams({ returnTo: safeReturnTo(returnTo) })}`;
}

export class AuthApiError extends Error {
  readonly receivedAt = Date.now();
  constructor(public status: number, public retryAfterSeconds = 0) {
    super(status === 401 ? '아이디 또는 비밀번호를 확인해 주세요.' : status === 400 ? '아이디와 비밀번호의 입력 형식을 확인해 주세요.' : status === 429 ? '로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.' : status === 403 ? '보안 정보가 만료되었습니다. 다시 로그인해 주세요.' : '인증 서버에 연결할 수 없거나 응답이 지연되었습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'AuthApiError';
  }
}

function retryAfterSeconds(value: string | null): number {
  if (!value) return 0;
  const seconds = /^\d+$/.test(value) ? Number(value) : (Date.parse(value) - Date.now()) / 1000;
  return Number.isFinite(seconds) ? Math.max(0, Math.ceil(seconds)) : 0;
}

async function authRequest(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(`/api/admin/v1/auth${path}`, { ...init, credentials: 'same-origin', cache: 'no-store', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000) });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new AuthApiError(0);
  }
  if (!response.ok) throw new AuthApiError(response.status, retryAfterSeconds(response.headers.get('Retry-After')));
  return response;
}

export async function getAuthBootstrap(signal?: AbortSignal): Promise<AuthBootstrap> {
  const response = await authRequest('', {}, signal);
  return response.json() as Promise<AuthBootstrap>;
}

export async function login(bootstrap: AuthBootstrap, credentials: LoginCredentials): Promise<void> {
  if (!bootstrap.localLoginEnabled || !bootstrap.csrfHeaderName || !bootstrap.csrfToken) throw new AuthApiError(403);
  await authRequest('/login', { method: 'POST', headers: { 'Content-Type': 'application/json', [bootstrap.csrfHeaderName]: bootstrap.csrfToken }, body: JSON.stringify(credentials) });
}

export async function logout(session: AdminSession): Promise<void> {
  if (!session.csrfHeaderName || !session.csrfToken) throw new AuthApiError(403);
  await authRequest('/logout', { method: 'POST', headers: { [session.csrfHeaderName]: session.csrfToken } });
}
