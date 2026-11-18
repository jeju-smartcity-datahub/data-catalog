import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { AdminApiError, adminGet, errorMessage, type AdminSession } from '../lib/admin';
import { AdminAuthContext, type AuthState } from '../lib/admin-auth';
import { onAuthenticationExpired } from '../lib/auth-events';

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });
  const revision = useRef(0);
  const authenticated = useRef(false);

  const loadSession = useCallback((signal?: AbortSignal): Promise<AdminSession | null> => {
    const request = ++revision.current;
    // This provider resolves its own session response; other protected requests notify it on 401.
    return adminGet<AdminSession>('/session', signal, { notifyExpired: false }).then(({ data }) => {
      if (!signal?.aborted && request === revision.current) {
        authenticated.current = true;
        setState({ status: 'ready', session: data });
      }
      return data;
    }).catch(error => {
      if (!signal?.aborted && request === revision.current) {
        if (error instanceof AdminApiError && error.status === 401) setState({ status: 'anonymous', reason: authenticated.current ? 'expired' : 'required' });
        else setState({ status: 'error', message: errorMessage(error) });
      }
      if (error instanceof AdminApiError && error.status === 401) return null;
      throw error;
    });
  }, []);

  const refresh = useCallback((signal?: AbortSignal) => {
    setState(current => current.status === 'ready' ? current : { status: 'loading' });
    return loadSession(signal);
  }, [loadSession]);

  const clear = useCallback(() => {
    revision.current++;
    authenticated.current = false;
    setState({ status: 'anonymous', reason: 'signed-out' });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadSession(controller.signal).catch(() => { /* The visible state contains the error. */ });
    const unsubscribe = onAuthenticationExpired(() => {
      revision.current++;
      setState({ status: 'anonymous', reason: authenticated.current ? 'expired' : 'required' });
    });
    const verifyWhenVisible = () => { if (document.visibilityState === 'visible') void loadSession(controller.signal).catch(() => {}); };
    document.addEventListener('visibilitychange', verifyWhenVisible);
    return () => { controller.abort(); unsubscribe(); document.removeEventListener('visibilitychange', verifyWhenVisible); };
  }, [loadSession]);

  return <AdminAuthContext value={{ state, refresh, clear }}>{children}</AdminAuthContext>;
}
