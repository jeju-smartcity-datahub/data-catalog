import { adminRoutePath, adminUrl } from '../lib/paths';
import { useEffect, useState } from 'react';
import { Button, Input } from '../components/ui';
import { Modal } from '../components/Modal';
import { HeadingActionsContext } from '../components/HeadingActionsContext';
import { getDatasets, type DatasetPage } from '../lib/catalog';
import type { AdminSession } from '../lib/admin';
import { MetadataWorkspace } from '../features/metadata/MetadataWorkspace';
import { AdminGate } from '../components/AdminGate';
import { AdminAuthProvider } from '../components/AdminAuthProvider';
import { LoginWorkspace } from '../features/auth/LoginWorkspace';
import { SessionActions } from '../features/auth/SessionActions';
import { loginUrl } from '../lib/auth';
import { Classifications } from '../features/governance/Classifications';
import { Standards } from '../features/governance/Standards';
import { DcatWorkspace } from '../features/dcat/DcatWorkspace';
import { AnalyticsWorkspace } from '../features/operations/AnalyticsWorkspace';
import { AccessWorkspace } from '../features/operations/AccessWorkspace';
import { HubsWorkspace } from '../features/hubs/HubsWorkspace';
import styles from './App.module.css';

const publicCatalogUrl = import.meta.env.VITE_PUBLIC_CATALOG_URL || 'http://127.0.0.1:3000';
const sections = [
  { path: '/hubs', title: '허브 연결·수집 관리', icon: 'hubs' },
  { path: '/metadata', title: '메타데이터 관리', icon: 'metadata' },
  { path: '/classifications', title: '데이터 분류 관리', icon: 'classifications' },
  { path: '/standards', title: '표준 사전 관리', icon: 'standards' },
  { path: '/dcat', title: 'DCAT 제공 관리', icon: 'dcat' },
  { path: '/analytics', title: '분석·검색 이력', icon: 'analytics' },
  { path: '/access', title: '권한·작업 이력', icon: 'access' },
];
const descriptions: Record<string, string> = {
  '/login': '인증된 계정으로 담당 기관의 관리 업무를 이용합니다.',
  '/hubs': '허브 연결 설정과 수집 실행 상태를 확인하고 관리합니다.',
  '/metadata': '메타데이터와 필드 정보를 보완하고 검토를 거쳐 사용자 카탈로그에 게시합니다.',
  '/classifications': '데이터를 분야와 주제로 분류하고 검색과 등록 화면에 적용합니다.',
  '/standards': '공공 표준을 참고하고 기관의 표준용어·단어·도메인을 관리합니다.',
  '/dcat': '공개된 메타데이터를 외부 시스템이 이해할 수 있는 형식으로 제공합니다.',
  '/analytics': '실제 검색과 메타데이터 분석 처리 이력을 확인합니다.',
  '/access': '서버가 확인한 계정 권한과 기관별 작업 이력을 확인합니다.',
};

type CatalogState = { status: 'loading' } | { status: 'ready'; data: DatasetPage } | { status: 'error' };

export function App() {
  return <AdminAuthProvider><AdminApp /></AdminAuthProvider>;
}

function AdminApp() {
  const path = adminRoutePath(window.location.pathname);
  const current = sections.find(section => section.path === path);
  const title = path === '/' || path === '/datasets' ? '공개 데이터 확인' : path === '/login' ? '관리자 로그인' : path === '/diagnostics' ? '개발환경 진단' : current?.title || '페이지를 찾을 수 없습니다';
  const [state, setState] = useState<CatalogState>({ status: 'loading' });
  const [request, setRequest] = useState(0);
  const [modalOpen, setModalOpen] = useState(false);
  const [organizationId, setOrganizationId] = useState('');
  const [accessResult, setAccessResult] = useState('');
  const [checking, setChecking] = useState(false);
  const [, setAdminSession] = useState<AdminSession | null>(null);
  const [mobileNavigation, setMobileNavigation] = useState(false);
  const [navigationExpanded, setNavigationExpanded] = useState(false);
  const [headingActions, setHeadingActions] = useState<HTMLDivElement | null>(null);
  useEffect(() => {
    const query = window.matchMedia('(max-width: 720px)');
    const update = () => setMobileNavigation(query.matches);
    update(); query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  useEffect(() => { document.title = `${title} · 데이터 카탈로그 관리`; }, [title]);
  useEffect(() => {
    if (path !== '/' && path !== '/datasets') return;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), 5000);
    getDatasets(controller.signal).then(data => setState({ status: 'ready', data })).catch(() => {
      if (!controller.signal.aborted || controller.signal.reason?.name === 'TimeoutError') setState({ status: 'error' });
    }).finally(() => clearTimeout(timeout));
    return () => { clearTimeout(timeout); controller.abort(); };
  }, [request, path]);

  function refresh() { setState({ status: 'loading' }); setRequest(value => value + 1); }
  async function checkAccess(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); setChecking(true); setAccessResult('확인 중입니다.');
    try {
      const response = await fetch(`/api/admin/v1/organizations/${encodeURIComponent(organizationId)}/access`, { credentials: 'same-origin', signal: AbortSignal.timeout(5000) });
      setAccessResult(response.status === 401 ? '로그인이 필요합니다. (401)' : response.status === 403 ? '이 기관에 접근할 권한이 없습니다. (403)' : response.ok ? '서버가 접근을 허용했습니다.' : `요청을 처리하지 못했습니다. (${response.status})`);
    } catch { setAccessResult('서버에 연결할 수 없습니다.'); }
    finally { setChecking(false); }
  }

  return <HeadingActionsContext value={headingActions}><div className={styles.app}>
    <a className="skip-link" href="#main">본문으로 건너뛰기</a>
    <aside className={styles.sidebar}>
      <a className={styles.brand} href={adminUrl('/')}>데이터 카탈로그</a>
      {mobileNavigation && <button type="button" className={styles.navigationToggle} aria-expanded={navigationExpanded} aria-controls="admin-navigation" onClick={() => setNavigationExpanded(value => !value)}>관리 메뉴 {navigationExpanded ? '접기' : '펼치기'}</button>}
      <nav id="admin-navigation" className={styles.navigation} aria-label="관리 메뉴" hidden={mobileNavigation && !navigationExpanded}>
        <a href={adminUrl('/datasets')} className={path === '/' || path === '/datasets' ? styles.active : ''} aria-current={path === '/' || path === '/datasets' ? 'page' : undefined}>공개 데이터 확인</a>
        {sections.map(section => <a href={adminUrl(section.path)} key={section.path} className={path === section.path ? styles.active : ''} aria-current={path === section.path ? 'page' : undefined}><img src={adminUrl(`/assets/menu-${section.icon}.svg`)} alt="" />{section.title}</a>)}
      </nav>
      <div className={styles.sidebarBottom}><p>데이터허브를 연결하는 카탈로그</p><p>연결 · 설명 · 검토 · 게시<br />지역의 데이터를 함께 연결합니다.</p>
        <a href={publicCatalogUrl} target="_blank" rel="noreferrer">사용자 카탈로그 보기 <img src={adminUrl('/assets/external-link.svg')} alt="새 창" /></a>
        {import.meta.env.DEV && <a href={adminUrl('/diagnostics')}>개발환경 진단</a>}
      </div>
    </aside>
    <div className={styles.body}><header className={styles.header}><p>카탈로그 관리 <img className={styles.breadcrumbIcon} src={adminUrl('/assets/breadcrumb-chevron.svg')} alt="" /> <strong>{title}</strong></p><SessionActions /></header>
      <main id="main" className={styles.main} tabIndex={-1}>
        <div className={styles.heading}><div><h1>{title}</h1><p>{descriptions[path] || (path === '/diagnostics' ? 'API 응답과 공통 UI 동작을 확인합니다.' : current ? '검토와 게시를 통해 신뢰할 수 있는 데이터를 제공합니다.' : '사용자에게 공개된 데이터와 제공 기관을 확인합니다.')}</p></div><div id="page-heading-actions" className={styles.headingActions} ref={setHeadingActions} /></div>
        {path === '/login' ? <LoginWorkspace /> : path === '/metadata' ? <AdminGate>{() => <MetadataWorkspace onSession={setAdminSession} />}</AdminGate> : path === '/hubs' ? <AdminGate onSession={setAdminSession}>{session => <HubsWorkspace session={session} />}</AdminGate> : path === '/classifications' ? <AdminGate onSession={setAdminSession}>{session => <Classifications session={session} />}</AdminGate> : path === '/standards' ? <AdminGate onSession={setAdminSession}>{session => <Standards session={session} />}</AdminGate> : path === '/dcat' ? <DcatWorkspace /> : path === '/analytics' ? <AnalyticsWorkspace onSession={setAdminSession} /> : path === '/access' ? <AccessWorkspace onSession={setAdminSession} /> : path === '/' || path === '/datasets' ? <>
          <section className={styles.panel}><div className={styles.panelHeading}><div><h2>게시된 데이터셋</h2><p>공개 카탈로그에 게시된 데이터만 표시합니다.</p></div><Button variant="secondary" disabled={state.status === 'loading'} onClick={refresh}>새로고침</Button></div>
            {state.status === 'loading' ? <p className={styles.state} role="status">데이터를 불러오는 중입니다.</p> : state.status === 'error' ? <div className={styles.state} role="alert"><h3>데이터를 불러오지 못했습니다</h3><p>잠시 후 새로고침해 주세요.</p></div> : <>
              <p className={styles.total}>전체 <strong>{state.data.page.totalElements.toLocaleString('ko-KR')}</strong>건 · 최근 게시 데이터 최대 10건</p>
              {state.data.items.length === 0 ? <p className={styles.state}>아직 공개된 데이터가 없습니다.</p> : <div className={styles.tableWrap}><table><caption className={styles.srOnly}>공개 데이터셋 목록</caption><thead><tr><th scope="col">데이터셋</th><th scope="col">제공기관</th><th scope="col">분류</th><th scope="col">게시일</th></tr></thead><tbody>{state.data.items.map(dataset => <tr key={dataset.id}><td>{dataset.title}</td><td>{dataset.organization.name}</td><td>{dataset.category?.name || '미분류'}</td><td>{new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul' }).format(new Date(dataset.publishedAt))}</td></tr>)}</tbody></table></div>}
            </>}
          </section>
          <section className={styles.notice}><h2>관리자 로그인</h2><p>데이터 등록·수정과 기관별 관리 기능은 관리자 인증이 필요합니다.</p><a href={loginUrl()}>로그인 화면으로 이동</a></section>
        </> : path === '/diagnostics' && import.meta.env.DEV ? <section className={styles.panel}>
          <h2>인가 응답 확인</h2><p>이 화면은 개발 모드에서만 사용할 수 있습니다. 사용자 역할을 브라우저에서 만들거나 저장하지 않습니다.</p>
          <form onSubmit={checkAccess} className={styles.form}><label htmlFor="organization">기관 ID (UUID)</label><Input id="organization" value={organizationId} onChange={event => setOrganizationId(event.target.value)} required pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" /><Button disabled={checking}>접근 권한 확인</Button><p role="status">{accessResult}</p></form>
          <Button variant="secondary" onClick={() => setModalOpen(true)}>공통 모달 확인</Button><Modal open={modalOpen} title="공통 모달 확인" onClose={() => setModalOpen(false)}><p>Tab 키로 초점을 이동하고 Escape 키로 닫을 수 있습니다.</p></Modal>
        </section> : <section className={styles.panel}><h2>페이지를 찾을 수 없습니다</h2><a href={adminUrl('/datasets')}>공개 데이터 확인으로 이동</a></section>}
      </main>
    </div>
  </div></HeadingActionsContext>;
}
