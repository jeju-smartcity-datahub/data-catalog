import { adminUrl } from '../../lib/paths';
import { loginUrl } from '../../lib/auth';
import { useContext, useEffect, useId, useState } from 'react';
import { createPortal } from 'react-dom';
import { HeadingActionsContext } from '../../components/HeadingActionsContext';
import { Badge, Button, Input } from '../../components/ui';
import { AdminApiError, adminGet, errorMessage, hasAuthority, type AdminSession, type MetadataOptions, type MetadataPage } from '../../lib/admin';
import { MetadataEditor } from './MetadataEditor';
import { DefinitionImportDialog } from './DefinitionImportDialog';
import { MetadataPagination } from './MetadataControls';
import stepArrow from './assets/step-arrow.svg';
import styles from './Metadata.module.css';
import layout from './MetadataPresentation.module.css';

type SessionState = { status: 'loading' } | { status: 'ready'; session: AdminSession } | { status: 'error'; message: string; authentication: boolean };
type ListState = { status: 'loading' } | { status: 'ready'; data: MetadataPage; options: MetadataOptions } | { status: 'error'; message: string };
export function MetadataWorkspace({ onSession }: { onSession: (session: AdminSession | null) => void }) {
  const searchId = useId();
  const [sessionState, setSessionState] = useState<SessionState>({ status: 'loading' });
  const [list, setList] = useState<ListState>({ status: 'loading' });
  const [retry, setRetry] = useState(0);
  const [query, setQuery] = useState({ q: '', state: '', page: 0 });
  const [q, setQ] = useState('');
  const [state, setState] = useState('');
  const [editor, setEditor] = useState<string | 'new' | null>(() => {
    const datasetId = new URLSearchParams(window.location.search).get('datasetId');
    return datasetId && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(datasetId) ? datasetId : null;
  });
  const [notice, setNotice] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const headingActions = useContext(HeadingActionsContext);

  useEffect(() => {
    const controller = new AbortController();
    adminGet<AdminSession>('/session', controller.signal).then(({ data }) => { setSessionState({ status: 'ready', session: data }); onSession(data); }).catch(error => { if (!controller.signal.aborted) { onSession(null); setSessionState({ status: 'error', message: errorMessage(error), authentication: error instanceof AdminApiError && error.status === 401 }); } });
    return () => controller.abort();
  }, [retry, onSession]);

  useEffect(() => {
    if (sessionState.status !== 'ready') return;
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(query.page), size: '10' });
    if (query.q) params.set('q', query.q);
    if (query.state) params.set('state', query.state);
    Promise.all([adminGet<MetadataPage>(`/datasets?${params}`, controller.signal), adminGet<MetadataOptions>('/metadata-options', controller.signal)]).then(([data, options]) => setList({ status: 'ready', data: data.data, options: options.data })).catch(error => { if (!controller.signal.aborted) setList({ status: 'error', message: errorMessage(error) }); });
    return () => controller.abort();
  }, [sessionState, query]);

  function refresh() { setList({ status: 'loading' }); setQuery(value => ({ ...value })); }
  function closeEditor() { setEditor(null); const url = new URL(window.location.href); url.searchParams.delete('datasetId'); window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`); }
  if (sessionState.status === 'loading') return <section className={styles.panel}><p className={styles.state} role="status">관리자 인증 상태를 확인하고 있습니다.</p></section>;
  if (sessionState.status === 'error') return <section className={styles.panel}><div className={styles.state} role="alert"><h2>{sessionState.authentication ? '관리자 로그인이 필요합니다' : '인증 상태를 확인하지 못했습니다'}</h2><p>{sessionState.authentication ? '관리자 계정으로 로그인해 주세요.' : sessionState.message}</p>{sessionState.authentication && <a href={loginUrl()}>관리자 로그인</a>}<Button variant="secondary" onClick={() => { setSessionState({ status: 'loading' }); setRetry(value => value + 1); }}>인증 상태 다시 확인</Button></div></section>;
  const session = sessionState.session;
  const actions = hasAuthority(session, 'ORG_EDITOR') ? <div className={layout.workspaceActions}><Button variant="secondary" disabled={list.status !== 'ready'} onClick={() => setEditor('new')}>초안 등록</Button><Button className={styles.buttonIcon} disabled={list.status !== 'ready'} onClick={() => setImportOpen(true)}><img src={adminUrl('/assets/add.svg')} alt="" />정의서 등록</Button></div> : null;
  return <>
    {headingActions ? createPortal(actions, headingActions) : actions}
    <ol className={layout.workflow} aria-label="메타데이터 게시 절차"><li><b>1</b>등록 담당자 보완</li><li><img src={stepArrow} alt="" /><b>2</b>검토 담당자 승인</li><li><img src={stepArrow} alt="" /><b>3</b>사용자에게 게시</li></ol>
    <form className={styles.toolbar} onSubmit={event => { event.preventDefault(); setList({ status: 'loading' }); setQuery({ q: q.trim(), state, page: 0 }); }}>
      <label>검토 상태<select className={styles.select} value={state} onChange={event => setState(event.target.value)}><option value="">전체 상태</option><option value="DRAFT">초안</option><option value="IN_REVIEW">검토 대기</option><option value="APPROVED">승인</option><option value="REJECTED">반려·철회</option></select></label>
      <label className={styles.search} htmlFor={searchId}>검색<div className={styles.searchInput}><img src={adminUrl('/assets/search.svg')} alt="" /><Input id={searchId} value={q} maxLength={200} onChange={event => setQ(event.target.value)} placeholder="데이터셋 이름 또는 설명" /></div></label>
      <Button>검색</Button><Button type="button" variant="secondary" onClick={() => { setQ(''); setState(''); setList({ status: 'loading' }); setQuery({ q: '', state: '', page: 0 }); }}>초기화</Button>
    </form>
    {notice && <p className={styles.message} role="status">{notice}</p>}
    <section className={styles.panel}>
      <div className={styles.panelHeading}><h2>메타데이터 목록 <strong aria-live="polite">{list.status === 'ready' ? list.data.page.totalElements.toLocaleString('ko-KR') : ''}</strong></h2><Button variant="secondary" disabled={list.status === 'loading'} onClick={refresh}>새로고침</Button></div>
      {list.status === 'loading' ? <p className={styles.state} role="status">목록을 불러오는 중입니다.</p> : list.status === 'error' ? <div className={styles.error} role="alert"><p>{list.message}</p><Button variant="secondary" onClick={refresh}>목록 다시 불러오기</Button></div> : <>
        {list.data.items.length === 0 ? <p className={styles.state} role="status">조건에 맞는 메타데이터가 없습니다.</p> : <section className={`${styles.tableWrap} ${layout.tableScroll}`} aria-label="접근 가능한 기관의 메타데이터 표" tabIndex={0}><table className={`${styles.table} ${layout.listTable}`}><caption className="sr-only">접근 가능한 기관의 메타데이터</caption><thead><tr><th scope="col">데이터셋 이름</th><th scope="col">분야</th><th scope="col">주제</th><th scope="col">제공기관</th><th scope="col">공개 범위</th><th scope="col">검토 상태</th><th scope="col">게시</th><th scope="col">최종 수정일</th></tr></thead><tbody>{list.data.items.map(item => <tr key={item.id}><td><button type="button" className={`${styles.rowLink} ${layout.titleLink}`} onClick={() => setEditor(item.id)}>{item.title}</button><span className={layout.rowVersion}>v{item.revisionNo}</span></td><td><Badge tone="neutral">{list.options.categories.find(category => category.id === item.primaryCategoryId)?.name || '미분류'}</Badge></td><td>{item.topicIds?.length ? item.topicIds.map(id => list.options.topics?.find(topic => topic.id === id)?.name || '주제 정보 없음').join(', ') : '-'}</td><td><span className={layout.organization}>{list.options.organizations.find(organization => organization.id === item.providerOrganizationId)?.name || '기관 정보 없음'}</span></td><td><span className={layout.visibility} data-visibility={item.visibility}>{item.visibility === 'PUBLIC' ? '공개' : item.visibility === 'ORGANIZATION' ? '기관 내부' : '비공개'}</span></td><td><Status state={item.state} /></td><td>{item.currentPublicationId ? <Badge tone="success">게시됨</Badge> : '미게시'}</td><td>{item.updatedAt ? <time dateTime={item.updatedAt}>{new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeZone: 'Asia/Seoul' }).format(new Date(item.updatedAt))}</time> : '-'}</td></tr>)}</tbody></table></section>}
        <MetadataPagination page={list.data.page.number} totalPages={list.data.page.totalPages} onChange={page => { setList({ status: 'loading' }); setQuery(value => ({ ...value, page })); }} />
      </>}
    </section>
    {editor && list.status === 'ready' && <MetadataEditor id={editor} session={session} options={list.options} onClose={closeEditor} onChanged={message => { closeEditor(); setNotice(message); refresh(); }} />}
    {importOpen && list.status === 'ready' && <DefinitionImportDialog session={session} options={list.options} onClose={() => setImportOpen(false)} onApplied={datasetId => { setImportOpen(false); setNotice('정의서의 선택 필드를 초안에 적용했습니다. 내용을 확인하고 검토를 요청하세요.'); setEditor(datasetId); }} />}
  </>;
}

function Status({ state }: { state: string }) {
  const labels: Record<string, string> = { DRAFT: '초안', IN_REVIEW: '검토 대기', APPROVED: '승인', REJECTED: '반려·철회' };
  return <Badge tone={state === 'IN_REVIEW' ? 'warning' : state === 'APPROVED' ? 'success' : state === 'REJECTED' ? 'danger' : 'neutral'}>{labels[state] || state}</Badge>;
}

