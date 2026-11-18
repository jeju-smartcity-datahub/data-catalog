import { createContext, useContext } from 'react';
import type { AdminSession } from './admin';

export type AuthState = { status: 'loading' } | { status: 'ready'; session: AdminSession } | { status: 'anonymous'; reason: 'required' | 'expired' | 'signed-out' } | { status: 'error'; message: string };
type AdminAuth = { state: AuthState; refresh: (signal?: AbortSignal) => Promise<AdminSession | null>; clear: () => void };

export const AdminAuthContext = createContext<AdminAuth | null>(null);

export function useAdminAuth(): AdminAuth {
  const value = useContext(AdminAuthContext);
  if (!value) throw new Error('관리자 인증 상태를 제공하는 영역이 필요합니다.');
  return value;
}
