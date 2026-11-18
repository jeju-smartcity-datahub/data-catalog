import type { components } from './generated/api';
import { notifyAuthenticationExpired } from './auth-events';
import { safeRequiredMetadataMessage } from './requiredMetadataError';
import { safeDefinitionFailure, type DefinitionFailure } from './definitionDiagnostics';

export type AdminSession = components['schemas']['SessionResponse'];
export type MetadataDraft = components['schemas']['MetadataDraft'];
export type MetadataPage = components['schemas']['MetadataPage'];
export type MetadataOptions = components['schemas']['MetadataOptions'];
export type ReviewRequest = components['schemas']['ReviewRequest'];
export type Publication = components['schemas']['Publication'];
export type AuditPage = components['schemas']['AuditPage'];
export type FieldDefinition = components['schemas']['FieldDefinition'];
export type FieldSet = components['schemas']['FieldSet'];
export type Category = components['schemas']['CategoryDetails'];
export type Dictionary = components['schemas']['Dictionary'];
export type DictionaryEntry = components['schemas']['DictionaryEntry'];
export type EntryInput = components['schemas']['EntryInput'];
export type DefinitionImport = components['schemas']['DefinitionImport'];
export type AnalysisEvidence = components['schemas']['AnalysisEvidence'];

const connectionErrors: Record<string, string> = {
  ENDPOINT_REQUIRED: '메타데이터 API 주소를 입력하세요.',
  INVALID_ENDPOINT: 'IP 또는 도메인과 포트를 확인하세요.',
  ENDPOINT_PATH_INVALID: '/datasets 또는 /entities 앞까지의 기본 주소를 입력하세요.',
  SOURCE_NOT_APPROVED: '서버의 연결 허용 목록에 없는 주소입니다. 설치 담당자에게 주소·포트 등록을 요청하세요.',
  SYNC_ALREADY_ACTIVE: '이 허브에서 진행 중인 작업이 있습니다. 현재 작업이 끝난 뒤 다시 실행하세요.',
};
function connectionErrorCode(problem: unknown): string | undefined {
  if (!problem || typeof problem !== 'object' || !('code' in problem) || typeof problem.code !== 'string') return undefined;
  return Object.hasOwn(connectionErrors, problem.code) ? problem.code : undefined;
}

export class AdminApiError extends Error {
  public definitionFailure: DefinitionFailure | null;
  public code?: string;
  constructor(public status: number, public requestId?: string, problem?: unknown) {
    const code = [400, 409, 422].includes(status) ? connectionErrorCode(problem) : undefined;
    super((code && connectionErrors[code]) || safeRequiredMetadataMessage(status, problem) || (status === 0 ? '변경 요청의 서버 응답을 확인하지 못했습니다. 처리되었을 수 있으니 최신 상태를 확인한 뒤 다시 시도해 주세요.' : status === 401 ? '관리자 로그인이 필요하거나 세션이 만료되었습니다.' : status === 403 ? '이 작업을 수행할 권한이 없거나 보안 토큰이 만료되었습니다. 인증 상태를 다시 확인해 주세요.' : status === 404 ? '데이터를 찾을 수 없거나 접근할 수 없습니다.' : status === 412 ? '다른 담당자가 내용을 변경했습니다. 입력 내용은 유지됩니다. 최신 내용을 확인한 뒤 다시 작업해 주세요.' : status === 409 ? '현재 상태에서는 요청을 처리할 수 없습니다. 최신 상태를 확인해 주세요.' : status === 410 ? '다운로드 기한이 만료되었습니다. 화면을 새로고침하고 다시 추출해 주세요.' : status === 400 || status === 422 ? '입력값과 필수 항목을 확인해 주세요.' : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    this.name = 'AdminApiError';
    this.code = code;
    this.definitionFailure = status === 400 ? safeDefinitionFailure(problem) : null;
  }
}

export type Resource<T> = { data: T; etag: string | null };
export async function adminGet<T>(path: string, signal?: AbortSignal, options?: { notifyExpired?: boolean }): Promise<Resource<T>> {
  const response = await fetch(`/api/admin/v1${path}`, { credentials: 'same-origin', cache: 'no-store', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000) });
  return parse<T>(response, options?.notifyExpired ?? true);
}

export async function adminWrite<T>(path: string, method: 'POST' | 'PATCH' | 'PUT' | 'DELETE', session: AdminSession, body?: unknown, etag?: string | null, key?: string): Promise<Resource<T>> {
  if (!session.csrfHeaderName || !session.csrfToken) throw new Error('보안 토큰을 확인하지 못했습니다. 인증 상태를 다시 확인해 주세요.');
  const headers = new Headers({ [session.csrfHeaderName]: session.csrfToken });
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  if (etag) headers.set('If-Match', etag);
  if (method === 'POST') {
    if (!key) throw new Error('요청 식별자가 없습니다. 화면을 다시 열어 주세요.');
    headers.set('Idempotency-Key', key);
  }
  let response: Response;
  try { response = await fetch(`/api/admin/v1${path}`, { method, headers, credentials: 'same-origin', cache: 'no-store', body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) }); }
  catch { throw new AdminApiError(0); }
  return parse<T>(response);
}

async function parse<T>(response: Response, notifyExpired = true): Promise<Resource<T>> {
  if (!response.ok) {
    if (response.status === 401 && notifyExpired) notifyAuthenticationExpired();
    throw new AdminApiError(response.status, response.headers.get('X-Request-Id') || undefined, await boundedProblem(response));
  }
  return { data: response.status === 204 ? undefined as T : await response.json() as T, etag: response.headers.get('ETag') };
}

async function boundedProblem(response: Response): Promise<unknown> {
  if (![400, 409, 422].includes(response.status) || !response.body) return undefined;
  const limit = response.status === 400 ? 512 * 1024 : 4096;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); return undefined; }
      chunks.push(value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
  } catch { return undefined; }
  finally { reader.releaseLock(); }
}

export function errorMessage(error: unknown): string {
  if (error instanceof AdminApiError) return `${error.message}${error.requestId ? ` (요청 ID: ${error.requestId})` : ''}`;
  return error instanceof Error && error.message.startsWith('보안 토큰') ? error.message : '서버에 연결할 수 없거나 응답이 지연되었습니다. 잠시 후 다시 시도해 주세요.';
}

export function hasAuthority(session: AdminSession, authority: string): boolean {
  return session.authorities.includes('ROLE_PLATFORM_ADMIN') || session.authorities.includes(`ROLE_${authority}`);
}

export async function adminDownload(path: string, fileName: string): Promise<void> {
  const response = await fetch(`/api/admin/v1${path}`, { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(20000) });
  if (!response.ok) {
    if (response.status === 401) notifyAuthenticationExpired();
    throw new AdminApiError(response.status, response.headers.get('X-Request-Id') || undefined);
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a'); link.href = url; link.download = fileName; document.body.append(link); link.click(); link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function adminUpload<T>(path: string, file: File, session: AdminSession): Promise<Resource<T>> {
  if (!session.csrfHeaderName || !session.csrfToken) throw new Error('보안 토큰을 확인하지 못했습니다. 인증 상태를 다시 확인해 주세요.');
  const body = new FormData(); body.set('file', file);
  let response: Response;
  try { response = await fetch(`/api/admin/v1${path}`, { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { [session.csrfHeaderName]: session.csrfToken }, body, signal: AbortSignal.timeout(30000) }); }
  catch { throw new AdminApiError(0); }
  return parse<T>(response);
}
