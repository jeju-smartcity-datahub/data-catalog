import { adminUrl } from '../../lib/paths';
import { useEffect, useId, useState } from 'react';
import { Badge, Button, Input } from '../../components/ui';
import { adminGet, errorMessage, type AdminSession } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { AdminGate } from '../../components/AdminGate';
import { dateTime } from './dateTime';
import styles from './Operations.module.css';
import { AccountManagement, OrganizationManagement } from './IdentityManagement';
type AuditPage = components['schemas']['AuditEventPage'];
export function AccessWorkspace({ onSession }: { onSession: (session: AdminSession | null) => void }) {
  return <AdminGate onSession={onSession}>{session => <AccessContent session={session} />}</AdminGate>;
}
function AccessContent({ session }: { session: AdminSession }) {
  const [tab, setTab] = useState('audit');
  const platformAdmin = session.authorities.includes('ROLE_PLATFORM_ADMIN');
  return <><nav className={styles.tabs} aria-label="권한 및 이력"><button aria-pressed={tab === 'permissions'} onClick={() => setTab('permissions')}>현재 계정 권한</button><button aria-pressed={tab === 'audit'} onClick={() => setTab('audit')}>작업 이력</button>{platformAdmin && <><button aria-pressed={tab === 'accounts'} onClick={() => setTab('accounts')}>계정 관리</button><button aria-pressed={tab === 'organizations'} onClick={() => setTab('organizations')}>기관 관리</button></>}</nav>
    {tab === 'permissions' ? <section className={styles.panel}><h2>서버가 확인한 계정 범위</h2><p>현재 로그인 계정에 부여된 역할과 기관 범위입니다.</p><dl className={styles.details}><dt>사용자 식별자</dt><dd>{session.userId}</dd><dt>기관 식별자</dt><dd>{session.organizationIds.join(', ') || '기관 범위 없음'}</dd><dt>부여된 역할</dt><dd>{session.authorities.map(role => <Badge key={role}>{role}</Badge>)}</dd></dl></section> : tab === 'accounts' && platformAdmin ? <AccountManagement session={session} /> : tab === 'organizations' && platformAdmin ? <OrganizationManagement session={session} /> : <AuditList />}</>;
}
function AuditList() {
  const searchId = useId();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState({ q: '', page: 0 });
  const [state, setState] = useState<{ data: AuditPage } | { error: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<AuditPage>(`/audit-events?${new URLSearchParams({ q: query.q, page: String(query.page), size: '20' })}`, controller.signal)
      .then(({ data }) => setState({ data })).catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); });
    return () => controller.abort();
  }, [query]);
  return <><form className={styles.toolbar} onSubmit={event => { event.preventDefault(); setState(null); setQuery({ q, page: 0 }); }}><label className={styles.search} htmlFor={searchId}>작업 검색<div className={styles.searchBox}><img src={adminUrl('/assets/search.svg')} alt="" /><Input id={searchId} value={q} maxLength={200} placeholder="작업 코드 · 대상 유형 · 대상 ID" onChange={event => setQ(event.target.value)} /></div></label><Button>검색</Button><Button type="button" variant="secondary" onClick={() => { setQ(''); setState(null); setQuery({ q: '', page: 0 }); }}>초기화</Button></form>
    <section className={styles.panel}><h2>작업 이력</h2><p>감사 권한이 있는 기관의 변경 내역을 조회합니다. 민감한 변경 본문은 요약 목록에 포함하지 않습니다.</p>
      {!state ? <p className={styles.state} role="status">작업 이력을 불러오는 중입니다.</p> : 'error' in state ? <div className={styles.state} role="alert"><p>{state.error}</p><Button variant="secondary" onClick={() => { setState(null); setQuery(value => ({ ...value })); }}>다시 시도</Button></div> : <><p>총 {state.data.totalElements.toLocaleString('ko-KR')}건</p>{state.data.items.length ? <section className={styles.tableWrap} aria-label="작업 이력 표" tabIndex={0}><table><caption className="sr-only">기관 범위의 감사 기록</caption><thead><tr><th scope="col">처리 시각</th><th scope="col">행위자</th><th scope="col">작업</th><th scope="col">대상</th><th scope="col">결과 · 요청 ID</th></tr></thead><tbody>{state.data.items.map(event => <tr key={event.id}><td>{dateTime(event.occurredAt)}</td><td className={styles.identifier}>{event.actorUserId}</td><td>{event.action}</td><td className={styles.identifier}>{event.targetType}<br />{event.targetId}</td><td className={styles.identifier}>{event.result}<br />{event.requestId}</td></tr>)}</tbody></table></section> : <p className={styles.state}>조건에 맞는 작업 이력이 없습니다.</p>}<div className={styles.pagination}><Button variant="secondary" disabled={query.page === 0} onClick={() => { setState(null); setQuery(value => ({ ...value, page: value.page - 1 })); }}>이전</Button><span>{query.page + 1} / {Math.max(1, state.data.totalPages)}</span><Button variant="secondary" disabled={query.page + 1 >= state.data.totalPages} onClick={() => { setState(null); setQuery(value => ({ ...value, page: value.page + 1 })); }}>다음</Button></div></>}
    </section></>;
}
