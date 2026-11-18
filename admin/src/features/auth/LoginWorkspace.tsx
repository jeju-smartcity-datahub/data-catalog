import { useCallback, useEffect, useRef, useState, type SubmitEvent } from 'react';
import { Button, Input } from '../../components/ui';
import { useAdminAuth } from '../../lib/admin-auth';
import { AuthApiError, getAuthBootstrap, login, safeReturnTo, type AuthBootstrap } from '../../lib/auth';
import { adminUrl } from '../../lib/paths';
import styles from './Auth.module.css';

type BootstrapState = { status: 'loading' } | { status: 'ready'; data: AuthBootstrap } | { status: 'error' };

export function LoginWorkspace() {
  const { state: auth, refresh } = useAdminAuth();
  const [bootstrap, setBootstrap] = useState<BootstrapState>({ status: 'loading' });
  const [message, setMessage] = useState('');
  const [username, setUsername] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [checking, setChecking] = useState(false);
  const [needsSessionCheck, setNeedsSessionCheck] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const inFlight = useRef(false);
  const passwordInput = useRef<HTMLInputElement>(null);
  const alertRef = useRef<HTMLParagraphElement>(null);
  const query = new URLSearchParams(window.location.search);
  const returnTo = safeReturnTo(query.get('returnTo'));
  const secondsRemaining = Math.max(0, Math.ceil((retryAt - now) / 1000));
  const busy = submitting || checking;

  const loadBootstrap = useCallback((signal?: AbortSignal) => {
    return getAuthBootstrap(signal).then(data => {
      if (!signal?.aborted) setBootstrap({ status: 'ready', data });
    }).catch(() => {
      if (!signal?.aborted) setBootstrap({ status: 'error' });
    });
  }, []);

  async function reloadBootstrap() {
    setBootstrap({ status: 'loading' });
    await loadBootstrap();
  }

  useEffect(() => {
    const controller = new AbortController();
    void loadBootstrap(controller.signal);
    return () => controller.abort();
  }, [loadBootstrap]);

  useEffect(() => {
    if (!retryAt) return;
    const timer = window.setInterval(() => {
      const time = Date.now(); setNow(time);
      if (time >= retryAt) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [retryAt]);

  useEffect(() => { if (message) alertRef.current?.focus(); }, [message]);

  async function verifySession() {
    setChecking(true);
    try {
      const session = await refresh();
      if (session) { window.location.replace(returnTo); return; }
      setNeedsSessionCheck(false);
      setMessage('인증된 세션을 확인하지 못했습니다. 다시 로그인해 주세요.');
      await reloadBootstrap();
    } catch {
      setNeedsSessionCheck(true);
      setMessage('로그인 결과를 확인하지 못했습니다. 인증 상태를 다시 확인해 주세요.');
    } finally { setChecking(false); }
  }

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || secondsRemaining > 0 || bootstrap.status !== 'ready' || !bootstrap.data.localLoginEnabled) return;
    const fields = new FormData(event.currentTarget);
    const submittedUsername = String(fields.get('username') || '').trim();
    const password = String(fields.get('password') || '');
    if (!submittedUsername || !password) { setMessage('아이디와 비밀번호를 입력해 주세요.'); return; }
    inFlight.current = true; setSubmitting(true); setMessage('');
    try {
      await login(bootstrap.data, { username: submittedUsername, password });
      if (passwordInput.current) passwordInput.current.value = '';
      await verifySession();
    } catch (error) {
      if (error instanceof AuthApiError && error.status === 0) {
        // A lost response can follow a successful login. Only the server session decides success.
        await verifySession();
      } else {
        setMessage(error instanceof AuthApiError ? error.message : '로그인을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.');
        if (error instanceof AuthApiError && error.status === 429) {
          setNow(error.receivedAt); setRetryAt(error.receivedAt + error.retryAfterSeconds * 1000);
        }
        // A failed attempt may invalidate the anonymous session or its CSRF token.
        await reloadBootstrap();
      }
    } finally {
      if (passwordInput.current) passwordInput.current.value = '';
      inFlight.current = false; setSubmitting(false);
    }
  }

  if (auth.status === 'ready' && !busy) return <section className={styles.panel}><h2>로그인되어 있습니다</h2><p>인증된 계정으로 관리 업무를 계속할 수 있습니다.</p><div className={styles.actions}><a className={styles.primaryLink} href={returnTo}>관리 화면으로 이동</a></div></section>;

  return <section className={styles.panel} aria-labelledby="local-login-title">
    <div className={styles.title}><h2 id="local-login-title">직접 로그인</h2></div>
    <p className={styles.intro}>관리자 계정으로 로그인하면 허용된 기관의 관리 업무를 이용할 수 있습니다.</p>
    {query.get('reason') === 'logged-out' && <p className={styles.notice} role="status">로그아웃했습니다.</p>}
    {message && <p className={styles.error} role="alert" tabIndex={-1} ref={alertRef}>{message}</p>}
    {bootstrap.status === 'loading' ? <p className={styles.status} role="status">로그인 설정을 확인하고 있습니다.</p> : bootstrap.status === 'error' ? <div className={styles.state}><p role="alert">로그인 설정을 불러오지 못했습니다. 서버 연결을 확인한 후 다시 시도해 주세요.</p><Button variant="secondary" onClick={() => { void reloadBootstrap(); }}>로그인 설정 다시 확인</Button></div> : !bootstrap.data.localLoginEnabled ? <div className={styles.state}><h3>직접 로그인을 사용할 수 없습니다</h3><p>직접 로그인이 비활성화되어 있습니다. 시스템 관리자에게 문의해 주세요.</p><a href={adminUrl('/datasets')}>공개 데이터 확인으로 이동</a></div> : !bootstrap.data.csrfHeaderName || !bootstrap.data.csrfToken ? <div className={styles.state}><p role="alert">로그인에 필요한 보안 정보를 받지 못했습니다.</p><Button variant="secondary" onClick={() => { void reloadBootstrap(); }}>로그인 설정 다시 확인</Button></div> : needsSessionCheck ? <div className={styles.actions}><Button disabled={checking} onClick={() => { void verifySession(); }}>{checking ? '인증 상태 확인 중…' : '인증 상태 다시 확인'}</Button></div> : <form className={styles.form} onSubmit={submit} aria-busy={busy}>
      <fieldset disabled={busy || auth.status === 'loading'}>
        <legend className="sr-only">관리자 로그인 정보</legend>
        <label htmlFor="login-username">아이디</label><Input id="login-username" name="username" value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" autoCapitalize="none" spellCheck={false} minLength={3} maxLength={80} required />
        <label htmlFor="login-password">비밀번호</label><Input ref={passwordInput} id="login-password" name="password" type="password" autoComplete="current-password" maxLength={128} required />
        <Button type="submit" disabled={secondsRemaining > 0}>{checking ? '인증 상태 확인 중…' : submitting ? '로그인 중…' : '로그인'}</Button>
      </fieldset>
      {secondsRemaining > 0 && <p className={styles.notice} role="status">{secondsRemaining}초 후 다시 로그인할 수 있습니다.</p>}
      {busy && <p className={styles.status} role="status">{checking ? '서버에서 인증된 세션을 확인하고 있습니다.' : '로그인 요청을 처리하고 있습니다.'}</p>}
      <p className={styles.help}>계정이 없거나 권한 확인이 필요하면 관리자에게 문의해 주세요.</p>
    </form>}
  </section>;
}
