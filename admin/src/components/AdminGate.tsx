import { useEffect, type ReactNode } from 'react';
import type { AdminSession } from '../lib/admin';
import { useAdminAuth } from '../lib/admin-auth';
import { loginUrl } from '../lib/auth';
import { Button } from './ui';
import styles from '../features/auth/Auth.module.css';

export function AdminGate({ onSession, children }: { onSession?: (session: AdminSession | null) => void; children: (session: AdminSession) => ReactNode }) {
  const { state, refresh } = useAdminAuth();
  useEffect(() => {
    onSession?.(state.status === 'ready' ? state.session : null);
  }, [state, onSession]);
  if (state.status === 'ready') return children(state.session);
  return <section className={styles.panel}><div className={styles.state}>{state.status === 'loading' ? <p role="status">관리자 인증 상태를 확인하고 있습니다.</p> : <>
    <h2>{state.status === 'anonymous' ? '관리자 로그인이 필요합니다' : '인증 상태를 확인하지 못했습니다'}</h2>
    <p role="alert">{state.status === 'anonymous' ? state.reason === 'expired' ? '세션이 만료되었거나 계정 권한이 변경되었습니다. 다시 로그인해 주세요.' : '담당 기관의 관리 업무를 사용하려면 관리자 계정으로 로그인해 주세요.' : state.message}</p>
    <div className={styles.actions}>{state.status === 'anonymous' && <a className={styles.primaryLink} href={loginUrl()}>로그인 화면으로 이동</a>}<Button variant="secondary" onClick={() => { void refresh().catch(() => {}); }}>인증 상태 다시 확인</Button></div>
  </>}</div></section>;
}
