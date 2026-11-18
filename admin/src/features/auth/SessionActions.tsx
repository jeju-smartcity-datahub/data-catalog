import { useState } from 'react';
import { Badge, Button } from '../../components/ui';
import { useAdminAuth } from '../../lib/admin-auth';
import { AuthApiError, loginUrl, logout } from '../../lib/auth';
import { adminUrl } from '../../lib/paths';
import styles from '../../app/App.module.css';

export function SessionActions() {
  const { state, refresh, clear } = useAdminAuth();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState('');

  function signedOut() { clear(); window.location.replace(`${adminUrl('/login')}?reason=logged-out`); }

  async function signOut() {
    if (state.status !== 'ready' || pending) return;
    setPending(true); setMessage('');
    try { await logout(state.session); signedOut(); }
    catch (error) {
      try {
        const session = await refresh();
        if (!session) { signedOut(); return; }
        setMessage(error instanceof AuthApiError && error.status === 403 ? '보안 정보를 갱신했습니다. 로그아웃을 다시 눌러 주세요.' : '로그아웃 응답을 확인하지 못했습니다. 다시 시도해 주세요.');
      } catch { setMessage('로그아웃 결과를 확인하지 못했습니다. 인증 상태를 다시 확인해 주세요.'); }
    } finally { setPending(false); }
  }

  return <div className={styles.sessionActions}>
    <div className={styles.sessionControls}><Badge>{state.status === 'ready' ? '인증된 관리자' : state.status === 'loading' ? '인증 확인 중' : '로그인 필요'}</Badge>
      {state.status === 'ready' ? <Button variant="secondary" disabled={pending} onClick={() => { void signOut(); }}>{pending ? '로그아웃 중…' : '로그아웃'}</Button> : state.status !== 'loading' && <a href={loginUrl()}>관리자 로그인</a>}
    </div>
    {message && <p role="alert" className={styles.sessionError}>{message}</p>}
    {state.status === 'error' && <Button variant="secondary" onClick={() => { void refresh().catch(() => {}); }}>세션 연결 다시 확인</Button>}
  </div>;
}
