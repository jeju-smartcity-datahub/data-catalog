import { AdminApiError, errorMessage } from '../../lib/admin';

export type HubFailure = { message: string; code?: string; requestId?: string };
export function hubFailure(reason: unknown): HubFailure {
  return reason instanceof AdminApiError ? { message: reason.message, code: reason.code, requestId: reason.requestId } : { message: errorMessage(reason) };
}
