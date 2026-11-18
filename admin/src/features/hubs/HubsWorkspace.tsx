import { createRequestId } from '../../lib/requestId';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { AdminApiError, adminDownload, adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type MetadataOptions, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import common from '../metadata/Metadata.module.css';
import styles from './Hubs.module.css';
import cardHub from './assets/card-hub.svg';
import cardData from './assets/card-data.svg';
import cardWaiting from './assets/card-waiting.svg';
import cardCheck from './assets/card-check.svg';
import hubDetail from './assets/hub-detail.svg';
import flowArrow from './assets/flow-arrow.svg';
import infoIcon from './assets/info.svg';
import clearIcon from './assets/clear.svg';
import { CollectedCandidates } from './CollectedCandidates';
import { HubCollectionSchedule } from './HubCollectionSchedule';
import { ConnectionPublicationSettings } from './PublicationControls';
import { endpointError, endpointValue, splitEndpoint, type Protocol } from './endpointInput';
import { activeRun, runLabel } from './runStatus';
import { RunProgress } from './RunProgress';
import { useHubRuns } from './useHubRuns';
import { HubAlert } from './HubAlert';
import { hubFailure, type HubFailure } from './hubError';
import { HubPagination } from './HubPagination';
import { HUB_PAGE_SIZE, pageRows } from './hubPageWindow';

type Connection = components['schemas']['ConnectionView'];
type ConnectionInput = components['schemas']['ConnectionInput'];
type Run = components['schemas']['RunView'];
type ItemPage = components['schemas']['ItemPage'];
type Event = components['schemas']['ConnectionEvent'];
type Schedule = components['schemas']['ScheduleView'];
const hubTabs = [{ key: 'data', label: '수집 데이터' }, { key: 'runs', label: '수집 이력' }, { key: 'settings', label: '설정' }, { key: 'events', label: '설정 이력' }] as const;
type HubTab = typeof hubTabs[number]['key'];
const adapterLabels: Record<string, string> = { KETI_DATACORE: 'KETI 데이터셋', NGSI_LD: 'NGSI-LD 엔티티' };
const date = (value?: string | null) => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '—';
const actionLabels: Record<string, string> = { CONNECTION_CREATED: '연결 등록', CONNECTION_UPDATED: '설정 수정', CONNECTION_DISABLED: '연결 비활성화', CHECK_REQUESTED: '연결 검사 요청', SYNC_REQUESTED: '수집 요청', CANDIDATE_APPLIED: '수집 후보 초안 적용', SCHEDULE_UPDATED: '정기 수집 설정', SCHEDULE_RUN_REQUESTED: '정기 수집 요청', PUBLICATION_POLICY_UPDATED: '게시 설정 수정', CANDIDATE_AUTO_PUBLISHED: '수집 데이터 자동 게시', CANDIDATE_BULK_PUBLISHED: '수집 데이터 선택 게시' };

export function HubsWorkspace({ session }: { session: AdminSession }) {
  const workspaceId = useId();
  const [organizationId, setOrganizationId] = useState(session.organizationIds[0] || '');
  const [organizations, setOrganizations] = useState<MetadataOptions['organizations'] | null>(null);
  const [organizationError, setOrganizationError] = useState('');
  const [connections, setConnections] = useState<Connection[] | null>(null);
  const [selected, setSelected] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [edit, setEdit] = useState<{ id?: string } | null>(null);
  const canEdit = hasAuthority(session, 'ORG_EDITOR');
  useEffect(() => {
    const controller = new AbortController();
    adminGet<MetadataOptions>('/metadata-options', controller.signal).then(({ data }) => {
      setOrganizations(data.organizations); setOrganizationError('');
      setOrganizationId(current => data.organizations.some(item => item.id === current) ? current : data.organizations[0]?.id || '');
    }).catch(error => { if (!controller.signal.aborted) setOrganizationError(errorMessage(error)); });
    return () => controller.abort();
  }, [session, refresh]);
  useEffect(() => {
    if (!organizationId) return;
    const controller = new AbortController();
    adminGet<Connection[]>(`/connections?organizationId=${encodeURIComponent(organizationId)}`, controller.signal)
      .then(({ data }) => { setConnections(data); setError(''); })
      .catch(error => { if (!controller.signal.aborted) { setError(errorMessage(error)); } });
    return () => controller.abort();
  }, [organizationId, refresh]);
  const current = connections?.find(item => item.id === selected) || connections?.[0];
  function reload() { setRefresh(value => value + 1); }
  return <>
    <div className={common.toolbar}>
      <label className={styles.organization}>담당 기관<select className={common.select} disabled={!organizations} value={organizationId} onChange={event => { setOrganizationId(event.target.value); setConnections(null); setSelected(''); setError(''); }}><option value="" disabled>기관을 선택하세요</option>{organizations?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <Button variant="secondary" onClick={reload}>새로고침</Button>
      {canEdit && <Button disabled={!organizationId} onClick={() => setEdit({})}>연결 등록</Button>}
    </div>
    {organizationError && <p className={common.error} role="alert">{organizationError}</p>}
    {!organizationId && (organizations ? <p className={common.message}>{hasAuthority(session, 'PLATFORM_ADMIN') ? '등록된 활성 기관이 없습니다. 기관을 등록한 뒤 새로고침해 주세요.' : '연결을 관리할 기관 권한이 없습니다. 시스템 관리자에게 문의해 주세요.'}</p> : !organizationError && <p role="status">담당 기관을 확인하고 있습니다.</p>)}
    {error && <p className={common.error} role="alert">{error}</p>}{notice && <p className={common.message} role="status">{notice}</p>}
    {organizationId && connections ? <>
      <dl className={styles.stats} aria-label="현재 조회된 연결 현황">
        <Stat label="등록된 연결" value={connections.length} help="담당 기관 · 최대 200건 조회" icon={cardHub} />
        <Stat label="활성 연결" value={connections.filter(item => item.enabled).length} help="서버에 저장된 활성 설정" icon={cardData} />
        <Stat label="주소 설정 대기" value={connections.filter(item => !item.endpointConfigured).length} help="승인된 연결 주소 등록 필요" icon={cardWaiting} />
        <Stat label="연동 기능 미등록" value={connections.filter(item => !item.adapterAvailable).length} help="서버에 등록되지 않은 연동 방식" icon={cardCheck} completeIcon />
      </dl>
      {connections.length ? <div className={styles.columns}>
        <section className={styles.panel} aria-labelledby={`${workspaceId}-connections`}><div className={styles.heading}><h2 id={`${workspaceId}-connections`}>연결 대상 데이터허브</h2><span>{connections.length}개</span></div><ul className={styles.connectionList}>{connections.map(item => <li key={item.id}><button type="button" aria-pressed={current?.id === item.id} onClick={() => setSelected(item.id)}><span className={styles.cardTitle}>{item.name}<span className={styles.pending}>{item.adapterAvailable ? item.enabled ? '활성' : '비활성' : '연동 기능 미등록'}</span></span><span>{item.endpointConfigured ? '연결 주소 등록됨' : '연결 주소 미등록'}</span><span>설정 변경 {date(item.updatedAt)}</span></button></li>)}</ul></section>
        {current && <ConnectionDetail key={current.id} connection={current} session={session} refresh={refresh} canEdit={canEdit} onEdit={() => setEdit({ id: current.id })} onChanged={message => { setNotice(message); setRefresh(value => value + 1); }} />}
      </div> : <section className={common.panel}><div className={common.state}><h2>등록된 허브 연결이 없습니다</h2><p>허브 API 주소와 인증 방식으로 연결을 등록하세요. 연결 주소 없이 비활성 설정을 먼저 저장할 수 있습니다.</p></div></section>}
    </> : organizationId && !error && <p className={common.state} role="status">허브 연결을 불러오는 중입니다.</p>}
    {edit && <ConnectionEditor key={edit.id || 'new'} id={edit.id} organizationId={organizationId} session={session} onClose={() => setEdit(null)} onSaved={message => { setEdit(null); setNotice(message); reload(); }} />}
  </>;
}

function Stat({ label, value, help, icon, completeIcon = false }: { label: string; value: number; help: string; icon: string; completeIcon?: boolean }) {
  return <div className={styles.stat}><dt>{label}<span className={`${styles.statIcon} ${completeIcon ? styles.completeIcon : ''}`}><img src={icon} alt="" /></span></dt><dd><strong>{value}</strong> 개<small>{help}</small></dd></div>;
}

function ConnectionDetail({ connection, session, refresh, canEdit, onEdit, onChanged }: { connection: Connection; session: AdminSession; refresh: number; canEdit: boolean; onEdit: () => void; onChanged: (message: string) => void }) {
  const detailId = useId();
  const [tab, setTab] = useState<HubTab>('data');
  const tabsRef = useRef<HTMLDivElement>(null);
  const [runPage, setRunPage] = useState(0);
  const [eventPage, setEventPage] = useState(0);
  const [requestFailure, setRequestFailure] = useState<(HubFailure & { title: string }) | null>(null);
  const { runs, error: runsError, reload: reloadRuns, accept, completed, observedAt } = useHubRuns(connection.id, refresh);
  const [events, setEvents] = useState<Event[] | null>(null);
  const [schedule, setSchedule] = useState<Resource<Schedule> | null>(null);
  const [scheduleError, setScheduleError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<'checks' | 'sync-runs' | null>(null);
  const [details, setDetails] = useState<Run | null>(null);
  const running = useRef(false);
  const request = useRef<{ kind: string; key: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Event[]>(`/connections/${connection.id}/audit-events`, controller.signal)
      .then(audit => { setEvents(audit.data); setError(''); })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    adminGet<Schedule>(`/connections/${connection.id}/schedule`, controller.signal)
      .then(result => { setSchedule(result); setScheduleError(''); })
      .catch(error => { if (!controller.signal.aborted) { setScheduleError(errorMessage(error)); } });
    return () => controller.abort();
  }, [connection.id, refresh, completed]);
  const active = runs?.some(activeRun) === true;
  const currentRun = runs?.find(activeRun) || runs?.[0];
  const workerReady = schedule?.data.workerEnabled === true;
  const runUnavailable = !connection.adapterAvailable || !connection.enabled;
  const runHelpId = `${detailId}-${runUnavailable ? 'availability' : 'worker'}`;
  const runView = pageRows(runs || [], runPage);
  const eventView = pageRows(events || [], eventPage);
  if (runPage !== runView.page) setRunPage(runView.page);
  if (eventPage !== eventView.page) setEventPage(eventView.page);
  function changeTabWithKey(event: KeyboardEvent<HTMLButtonElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const buttons = Array.from(tabsRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]') || []);
    const current = buttons.indexOf(event.currentTarget);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next]?.focus(); buttons[next]?.click();
  }
  function showCollected() {
    setTab('data');
    requestAnimationFrame(() => { const button = tabsRef.current?.querySelector<HTMLButtonElement>('[data-hub-tab="data"]'); button?.focus({ preventScroll: true }); button?.scrollIntoView({ block: 'start' }); });
  }
  async function execute(kind: 'checks' | 'sync-runs') {
    if (running.current || !workerReady || runUnavailable || active) return;
    running.current = true; setBusy(kind); setRequestFailure(null);
    if (request.current?.kind !== kind) request.current = { kind, key: createRequestId() };
    try {
      const { data } = await adminWrite<Run>(`/connections/${connection.id}/${kind}`, 'POST', session, undefined, undefined, request.current.key);
      request.current = null; accept(data);
    } catch (error) { setRequestFailure({ ...hubFailure(error), title: kind === 'checks' ? '연결 검사 요청 실패' : '수집 요청 실패' }); reloadRuns(); }
    finally { running.current = false; setBusy(null); }
  }
  return <div className={styles.detailColumn}>
    <section className={styles.panel} aria-labelledby={`${detailId}-heading`}><div className={styles.heading}><h2 id={`${detailId}-heading`}>카탈로그 연결 설정</h2>{canEdit && <Button variant="secondary" disabled={!!busy || active} onClick={onEdit}>설정 수정</Button>}</div>
      <div className={styles.detailBody}><div className={styles.identity}><span className={styles.hubIcon}><img src={hubDetail} alt="" /></span><div><h3>{connection.name}</h3><p>{adapterLabels[connection.adapterCode] || connection.adapterCode}</p></div><span className={styles.pending}>{connection.adapterAvailable ? connection.enabled ? '활성' : '비활성' : '연동 기능 미등록'}</span></div>
        <dl className={styles.properties}><div><dt>메타데이터 API 주소</dt><dd>{connection.metadataEndpoint || (connection.endpointConfigured ? '등록됨' : '미등록')}</dd></div><div><dt>데이터 조회 API 주소</dt><dd>{connection.recordsEndpoint || '미등록'}</dd></div><div><dt>인증 정보</dt><dd>{connection.credentialConfigured ? '서버 비밀 참조 설정됨' : '비밀 참조 미설정'}</dd></div><div><dt>수집 방식</dt><dd>담당자 요청 또는 설정된 주기에 실행</dd></div><div><dt>실행 제한</dt><dd>응답 대기 {connection.timeoutSeconds}초 · 최대 {connection.maxAttempts}회 시도 · {connection.maxPages}페이지</dd></div></dl>
        <div className={styles.flow}><strong id={`${detailId}-flow`}>메타데이터 수집과 검토</strong><ol aria-labelledby={`${detailId}-flow`}><li><span>데이터허브</span></li><li><img src={flowArrow} alt="" /><span>수집·필수 정보 확인</span></li><li><img src={flowArrow} alt="" /><span>선택 게시 또는 자동 게시</span></li></ol><p>허브의 메타데이터를 수집해 카탈로그에 게시합니다. 게시 방식과 수집 주기는 설정 탭에서 변경합니다.</p></div>
        {!connection.adapterAvailable ? <p id={`${detailId}-availability`} className={styles.info}><img src={infoIcon} alt="" />서버에 해당 연동 기능이 등록되어 있지 않아 연결 검사와 수집을 실행할 수 없습니다. 지원되는 연동 방식을 선택해 연결을 등록하세요.</p> : !connection.enabled && <p id={`${detailId}-availability`} className={styles.info}><img src={infoIcon} alt="" />연결 설정을 활성화한 뒤 지원하는 기능을 실행할 수 있습니다.</p>}
        {!workerReady && <p id={`${detailId}-worker`} className={styles.info}>{scheduleError || (schedule ? '서버 수집 작업자가 꺼져 있습니다. 설치 담당자에게 작업자 설정 활성화를 요청하세요.' : '서버 수집 작업자 상태를 확인하고 있습니다.')}</p>}
        <p className={common.help}>연결 검사 완료 후 메타데이터를 수집하세요. 처리 상태와 결과는 자동으로 갱신됩니다.</p>
        {canEdit && <div className={styles.actions}><Button variant="secondary" aria-describedby={runUnavailable || !workerReady ? runHelpId : undefined} disabled={!!busy || active || !runs || !!runsError || runUnavailable || !workerReady || !connection.capabilities.includes('TEST_CONNECTION')} onClick={() => void execute('checks')}>{busy === 'checks' ? '검사 요청 중…' : '연결 검사'}</Button><Button aria-describedby={runUnavailable || !workerReady ? runHelpId : undefined} disabled={!!busy || active || !runs || !!runsError || runUnavailable || !workerReady || !connection.capabilities.includes('DISCOVER_DATASETS')} onClick={() => void execute('sync-runs')}>{busy === 'sync-runs' ? '수집 요청 중…' : '메타데이터 수집'}</Button></div>}
        {requestFailure && <HubAlert title={requestFailure.title} failure={requestFailure} action="현재 작업 상태와 연결 설정을 확인한 뒤 다시 실행하세요." />}
        {currentRun && <RunProgress run={currentRun} observedAt={observedAt} onShowCollected={showCollected} />}
        {runsError && <HubAlert title="작업 상태 조회 실패" failure={runsError} action="마지막 조회 결과를 표시합니다. 상태 다시 조회를 눌러 확인하세요."><Button variant="secondary" onClick={reloadRuns}>상태 다시 조회</Button></HubAlert>}
      </div>
    </section>
    <div className={styles.workspaceTabs}>
      <div ref={tabsRef} className={styles.tabs} role="tablist" aria-label="허브 관리 항목">{hubTabs.map(item => <button type="button" key={item.key} id={`${detailId}-tab-${item.key}`} data-hub-tab={item.key} role="tab" aria-selected={tab === item.key} aria-controls={`${detailId}-panel-${item.key}`} tabIndex={tab === item.key ? 0 : -1} onClick={() => setTab(item.key)} onKeyDown={changeTabWithKey}>{item.label}</button>)}</div>
      <div id={`${detailId}-panel-data`} className={styles.tabPanel} role="tabpanel" aria-labelledby={`${detailId}-tab-data`} hidden={tab !== 'data'} tabIndex={0}>
        <CollectedCandidates connectionId={connection.id} session={session} refresh={refresh + completed} canEdit={canEdit} onApplied={message => onChanged(message || '수집 후보를 메타데이터 초안에 적용했습니다.')} />
      </div>
      <div id={`${detailId}-panel-runs`} className={styles.tabPanel} role="tabpanel" aria-labelledby={`${detailId}-tab-runs`} hidden={tab !== 'runs'} tabIndex={0}>
        <section className={styles.panel}><div className={styles.heading}><div><h2>최근 수집 이력</h2><p>선택한 연결의 최근 작업 최대 100건 · {HUB_PAGE_SIZE}건씩 표시</p></div></div><div className={styles.history}>
          {runs ? runs.length ? <><section className={common.tableWrap} aria-label="수집과 연결 검사 이력 표" tabIndex={0}><table className={common.table}><caption className="sr-only">선택한 허브의 수집과 연결 검사 이력</caption><thead><tr><th scope="col">요청 시각</th><th scope="col">작업</th><th scope="col">상태</th><th scope="col">시도</th><th scope="col">상세</th></tr></thead><tbody>{runView.items.map(run => <tr key={run.id}><td>{date(run.createdAt)}</td><td>{run.kind === 'CHECK' ? '연결 검사' : '메타데이터 수집'}</td><td><Badge tone={['FAILED', 'PARTIAL_FAILED'].includes(run.status) ? 'danger' : 'info'}>{runLabel(run)}</Badge></td><td>{run.attempt}회</td><td><Button variant="secondary" onClick={() => setDetails(run)}>결과 보기</Button></td></tr>)}</tbody></table></section><HubPagination label="수집 이력" total={runs.length} page={runView.page} onChange={setRunPage} /></> : <p className={common.state}>아직 요청한 수집 작업이 없습니다.</p> : !runsError && <p role="status">수집 이력을 확인하고 있습니다.</p>}
        </div></section>
      </div>
      <div id={`${detailId}-panel-settings`} className={`${styles.tabPanel} ${styles.settingsPanel}`} role="tabpanel" aria-labelledby={`${detailId}-tab-settings`} hidden={tab !== 'settings'} tabIndex={0}>
        <ConnectionPublicationSettings connectionId={connection.id} session={session} refresh={refresh} onChanged={onChanged} />
        {schedule && <HubCollectionSchedule key={connection.id} connection={connection} resource={schedule} session={session} onChanged={onChanged} />}
        {scheduleError && <HubAlert title="수집 설정 조회 실패" failure={{ message: scheduleError }} action="상단 새로고침을 눌러 저장된 설정을 다시 확인하세요." />}
      </div>
      <div id={`${detailId}-panel-events`} className={styles.tabPanel} role="tabpanel" aria-labelledby={`${detailId}-tab-events`} hidden={tab !== 'events'} tabIndex={0}>
        <section className={styles.panel}><div className={styles.heading}><div><h2>최근 설정·요청 이력</h2><p>선택한 연결의 최근 이력 최대 200건 · {HUB_PAGE_SIZE}건씩 표시</p></div></div><div className={styles.history}>
          {error && <HubAlert title="설정 이력 조회 실패" failure={{ message: error }} action="상단 새로고침을 눌러 다시 조회하세요." />}
          {events ? events.length ? <><section className={common.tableWrap} aria-label="설정·요청 이력 표" tabIndex={0}><table className={common.table}><caption className="sr-only">선택한 허브의 설정·요청 이력 최대 200건</caption><thead><tr><th scope="col">시각</th><th scope="col">작업</th><th scope="col">설정 버전</th><th scope="col">요청 ID</th></tr></thead><tbody>{eventView.items.map(event => <tr key={event.id}><td>{date(event.occurredAt)}</td><td>{actionLabels[event.action] || event.action}</td><td>{event.configurationVersion}</td><td className={styles.trace}>{event.requestId}</td></tr>)}</tbody></table></section><HubPagination label="설정 이력" total={events.length} page={eventView.page} onChange={setEventPage} /></> : <p className={common.state}>저장된 설정 이력이 없습니다.</p> : !error && <p role="status">설정 이력을 확인하고 있습니다.</p>}
        </div></section>
      </div>
    </div>
    {details && <RunDetails run={runs?.find(run => run.id === details.id) || details} observedAt={observedAt} onClose={() => setDetails(null)} />}
  </div>;
}

function ConnectionEditor({ id, organizationId, session, onClose, onSaved }: { id?: string; organizationId: string; session: AdminSession; onClose: () => void; onSaved: (message: string) => void }) {
  const formId = useId();
  const endpointInput = useRef<HTMLInputElement>(null);
  const [resource, setResource] = useState<Resource<Connection> | null>(null);
  const [form, setForm] = useState({ name: '', adapterCode: 'KETI_DATACORE', endpoint: '', secretReference: '', enabled: false, timeoutSeconds: 10, maxAttempts: 3, maxPages: 100 });
  const [protocol, setProtocol] = useState<Protocol>('https');
  const [addressError, setAddressError] = useState('');
  const [addressDiagnostic, setAddressDiagnostic] = useState<{ code?: string; requestId?: string } | null>(null);
  const [baseline, setBaseline] = useState(JSON.stringify(form));
  const [clearSecret, setClearSecret] = useState(false);
  const [error, setError] = useState<HubFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const running = useRef(false);
  const key = useRef(createRequestId());
  const dirty = JSON.stringify(form) !== baseline || clearSecret;
  const adapterAvailable = Object.hasOwn(adapterLabels, form.adapterCode)
    && (!resource || form.adapterCode !== resource.data.adapterCode || resource.data.adapterAvailable);
  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    adminGet<Connection>(`/connections/${id}`, controller.signal).then(result => {
      setResource(result); const item = result.data;
      const value = { name: item.name, adapterCode: item.adapterCode, endpoint: '', secretReference: '', enabled: item.enabled, timeoutSeconds: item.timeoutSeconds, maxAttempts: item.maxAttempts, maxPages: item.maxPages };
      setForm(value); setProtocol(splitEndpoint(item.metadataEndpoint || '').protocol); setBaseline(JSON.stringify(value)); setError(null);
    }).catch(error => { if (!controller.signal.aborted) setError(hubFailure(error)); });
    return () => controller.abort();
  }, [id]);
  useEffect(() => { if (!dirty) return; const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', protect); return () => window.removeEventListener('beforeunload', protect); }, [dirty]);
  async function save(disable = false) {
    if (running.current || uncertain) return;
    const endpoint = endpointValue(protocol, form.endpoint);
    const problem = disable ? '' : endpointError(endpoint, form.adapterCode, form.enabled && adapterAvailable && !resource?.data.endpointConfigured);
    setAddressError(problem); setAddressDiagnostic(null);
    if (problem) { endpointInput.current?.focus(); return; }
    running.current = true; setBusy(true); setError(null);
    try {
      if (id && !resource?.etag) throw new Error('버전을 확인하지 못했습니다.');
      const body: ConnectionInput = { ...form, enabled: form.enabled && adapterAvailable, organizationId, name: form.name.trim(), endpoint: endpoint || undefined, secretReference: clearSecret ? '' : form.secretReference || undefined };
      await adminWrite<Connection>(id ? `/connections/${id}` : '/connections', disable ? 'DELETE' : id ? 'PATCH' : 'POST', session, disable ? undefined : body, resource?.etag, key.current);
      onSaved(disable ? '연결을 비활성화했습니다. 기존 이력은 보존됩니다.' : '연결 설정을 저장했습니다. 실제 연동 여부는 연결 검사 결과로 확인하세요.');
    } catch (error) {
      if (error instanceof AdminApiError && ['ENDPOINT_REQUIRED', 'INVALID_ENDPOINT', 'ENDPOINT_PATH_INVALID', 'SOURCE_NOT_APPROVED'].includes(error.code || '')) {
        setAddressError(error.code === 'ENDPOINT_PATH_INVALID' ? `/${form.adapterCode === 'NGSI_LD' ? 'entities' : 'datasets'} 앞까지의 기본 주소를 입력하세요.` : error.message);
        setAddressDiagnostic({ code: error.code, requestId: error.requestId }); requestAnimationFrame(() => endpointInput.current?.focus());
      } else if (!id && (!(error instanceof AdminApiError) || error.status === 0)) { setUncertain(true); setError({ message: '등록 응답을 확인하지 못했습니다. 닫은 뒤 목록을 새로고침하여 저장 여부를 확인하세요.' }); }
      else setError(hubFailure(error));
    } finally { running.current = false; setBusy(false); }
  }
  function close() { if (!busy && (uncertain || !dirty || window.confirm('저장하지 않은 변경사항을 버리시겠습니까?'))) onClose(); }
  return <Modal open size="medium" className={styles.editorDialog} title={id ? '연결 설정 수정' : '연결 설정 등록'} busy={busy} onClose={close} footerMode="replace" footer={<>
    {id && resource?.data.enabled && <Button type="button" variant="danger" disabled={busy || dirty} onClick={() => { if (window.confirm('연결을 비활성화하시겠습니까? 수집 이력은 유지됩니다.')) void save(true); }}>비활성화</Button>}
    <Button type="button" variant="secondary" disabled={busy} data-initial-focus="true" onClick={close}>취소</Button>
    <Button type="submit" form={formId} disabled={busy || uncertain || !dirty || (!!id && !resource)}>{busy ? '저장 중…' : '저장'}</Button>
  </>}>
    {error && <HubAlert title="연결 설정 저장·조회 실패" failure={error} action="입력 내용과 현재 연결 상태를 확인하세요." />}
    {!id || resource ? <form id={formId} className={styles.form} onSubmit={event => { event.preventDefault(); void save(); }}>
      {resource && <h3 className={styles.editorIdentity}>{resource.data.name}</h3>}
      <div className={styles.field}><label htmlFor={`${formId}-name`}>연결 이름<RequiredMark /></label><Input id={`${formId}-name`} required maxLength={200} value={form.name} disabled={busy} onChange={event => setForm(value => ({ ...value, name: event.target.value }))} /></div>
      <div className={styles.field}><label htmlFor={`${formId}-adapter`}>연동 식별 코드<RequiredMark /></label><select id={`${formId}-adapter`} className={common.select} aria-describedby={`${formId}-adapter-help`} required value={form.adapterCode} disabled={busy || !!id} onChange={event => setForm(value => ({ ...value, adapterCode: event.target.value }))}>{!Object.hasOwn(adapterLabels, form.adapterCode) && <option value={form.adapterCode}>{form.adapterCode} · 지원 여부 확인</option>}<option value="KETI_DATACORE">KETI 데이터셋 (KETI_DATACORE)</option><option value="NGSI_LD">NGSI-LD 엔티티 (NGSI_LD)</option></select><small id={`${formId}-adapter-help`}>{form.adapterCode === 'NGSI_LD' ? 'NGSI-LD 엔티티 API를 선택했습니다. /entities 앞의 승인된 기본 주소와 경로를 입력하세요. 원격 컨텍스트를 내려받지 않으며 지원되는 속성·관계·컨텍스트만 후보로 수집합니다.' : form.adapterCode === 'KETI_DATACORE' ? '데이터셋 목록·상세와 연결된 데이터 모델의 필드정보를 수집합니다. /datasets 앞까지의 API 주소를 입력하세요.' : '서버에서 지원하는 연동 방식을 확인하세요.'} 허브의 API 주소·인증 방식에 맞춰 선택하세요.{id && ' 기존 수집 이력을 보존하기 위해 방식은 유지합니다. 다른 방식은 새 연결로 등록하세요.'}</small></div>
      <div className={styles.field}><label htmlFor={`${formId}-endpoint`}>메타데이터 API 주소{form.enabled && adapterAvailable && !resource?.data.endpointConfigured && <RequiredMark />}</label>
        <div className={styles.endpointRow}><label className="sr-only" htmlFor={`${formId}-protocol`}>연결 프로토콜</label><select id={`${formId}-protocol`} className={common.select} value={protocol} disabled={busy} onChange={event => { setProtocol(event.target.value as Protocol); setAddressError(''); }}><option value="https">HTTPS</option><option value="http">HTTP</option></select>
          <div className={styles.clearInput}><Input id={`${formId}-endpoint`} ref={endpointInput} aria-describedby={`${formId}-endpoint-help ${addressError ? `${formId}-endpoint-error` : ''}`.trim()} aria-invalid={!!addressError} aria-required={form.enabled && adapterAvailable && !resource?.data.endpointConfigured} type="text" inputMode="url" maxLength={1000} placeholder={resource?.data.endpointConfigured ? '비워 두면 기존 주소 유지' : 'IP 또는 도메인:포트/기본 경로'} disabled={busy} value={form.endpoint} onChange={event => { const value = splitEndpoint(event.target.value, protocol); setProtocol(value.protocol); setForm(form => ({ ...form, endpoint: value.address })); setAddressError(''); setAddressDiagnostic(null); }} />{form.endpoint && <button type="button" disabled={busy} aria-label="입력한 연결 주소 지우기" onClick={() => { setForm(value => ({ ...value, endpoint: '' })); setAddressError(''); endpointInput.current?.focus(); }}><img src={clearIcon} alt="" /></button>}</div>
        </div>
        <small id={`${formId}-endpoint-help`}>/{form.adapterCode === 'NGSI_LD' ? 'entities' : 'datasets'} 앞까지 입력하세요. 전체 URL을 붙여넣으면 프로토콜이 자동으로 선택됩니다.{id && ' 주소를 비워 두면 기존 설정을 유지합니다.'}</small>
        {form.endpoint ? <small>저장할 주소: {endpointValue(protocol, form.endpoint)}</small> : resource?.data.metadataEndpoint && <small>현재 주소: {resource.data.metadataEndpoint}</small>}
        {addressError && <HubAlert id={`${formId}-endpoint-error`} title="메타데이터 API 주소를 확인하세요" failure={{ message: addressError, ...addressDiagnostic }} />}
      </div>
      <div className={styles.field}><label htmlFor={`${formId}-secret`}>서버 비밀 참조</label><Input id={`${formId}-secret`} aria-describedby={`${formId}-secret-help`} type="password" autoComplete="new-password" pattern="[a-zA-Z0-9/_-]*" maxLength={200} disabled={busy || clearSecret} value={form.secretReference} placeholder={resource?.data.credentialConfigured ? '설정됨 · 비워 두면 기존 참조 유지' : '인증이 없으면 비워 두세요'} onChange={event => setForm(value => ({ ...value, secretReference: event.target.value }))} /><small id={`${formId}-secret-help`}>인증이 없는 허브는 비워 두세요. 인증이 필요하면 서버에 등록한 참조명만 입력합니다.</small></div>
      {resource?.data.credentialConfigured && <label className={styles.check}><input type="checkbox" checked={clearSecret} disabled={busy} onChange={event => setClearSecret(event.target.checked)} />기존 비밀 참조 해제</label>}
      <div className={styles.formRow}><label><span>응답 대기 시간(초)<RequiredMark /></span><Input type="number" required min={1} max={30} disabled={busy} value={form.timeoutSeconds} onChange={event => setForm(value => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label><label><span>최대 시도 횟수<RequiredMark /></span><Input type="number" required min={1} max={5} disabled={busy} value={form.maxAttempts} onChange={event => setForm(value => ({ ...value, maxAttempts: Number(event.target.value) }))} /></label><label><span>최대 페이지 수<RequiredMark /></span><Input type="number" required min={1} max={100} disabled={busy} value={form.maxPages} onChange={event => setForm(value => ({ ...value, maxPages: Number(event.target.value) }))} /></label></div>
      <label className={styles.check}><input type="checkbox" aria-describedby={!adapterAvailable ? `${formId}-enabled-help` : undefined} checked={form.enabled && adapterAvailable} disabled={busy || !adapterAvailable} onChange={event => setForm(value => ({ ...value, enabled: event.target.checked }))} />연결 활성화</label>{!adapterAvailable && <p id={`${formId}-enabled-help`} className={common.help}>서버에 해당 연동 기능이 등록되어 있지 않습니다. 현재 설정은 비활성 상태로 저장합니다.</p>}
    </form> : !error && <p role="status">연결 설정을 확인하고 있습니다.</p>}
  </Modal>;
}

function RunDetails({ run, observedAt, onClose }: { run: Run; observedAt: number; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ItemPage | null>(null);
  const [error, setError] = useState('');
  const [downloadFormat, setDownloadFormat] = useState<'json' | 'csv' | null>(null);
  const [downloadError, setDownloadError] = useState('');
  const downloading = useRef(false);
  async function download(format: 'json' | 'csv') {
    if (downloading.current) return;
    downloading.current = true; setDownloadFormat(format); setDownloadError('');
    try { await adminDownload(`/sync-runs/${run.id}/report?format=${format}`, `collection-result.${format}`); }
    catch (error) { setDownloadError(error instanceof AdminApiError && error.status === 413 ? '보고서의 지원 크기를 넘었습니다. 수집 범위와 작업 구분을 확인하세요.' : errorMessage(error)); }
    finally { downloading.current = false; setDownloadFormat(null); }
  }
  useEffect(() => { const controller = new AbortController(); adminGet<ItemPage>(`/sync-runs/${run.id}/items?page=${page}&size=${HUB_PAGE_SIZE}`, controller.signal).then(({ data }) => { setResult(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [run.id, run.status, run.processedCount, run.progressAt, page]);
  return <Modal open wide title={run.kind === 'CHECK' ? '연결 검사 상세' : '수집 작업 상세'} onClose={onClose}><RunProgress run={run} observedAt={observedAt} />{error && <p className={common.error} role="alert">{error}</p>}
    <div className={common.actions}><Button variant="secondary" disabled={!!downloadFormat} onClick={() => void download('json')}>{downloadFormat === 'json' ? 'JSON 준비 중…' : 'JSON 보고서 다운로드'}</Button><Button variant="secondary" disabled={!!downloadFormat} onClick={() => void download('csv')}>{downloadFormat === 'csv' ? 'CSV 준비 중…' : 'CSV 보고서 다운로드'}</Button></div>
    {downloadError && <p className={common.error} role="alert">{downloadError}</p>}
    {result ? result.items.length ? <><section className={common.tableWrap} aria-label="수집 항목 처리 결과 표" tabIndex={0}><table className={common.table}><caption className="sr-only">수집한 허브 데이터와 처리 결과</caption><thead><tr><th scope="col">허브 데이터 식별자</th><th scope="col">결과</th><th scope="col">시도</th><th scope="col">오류</th></tr></thead><tbody>{result.items.map(item => <tr key={item.externalId}><td>{item.externalId}</td><td>{item.status === 'CANDIDATE' ? '검토 후보 저장' : item.status === 'UNCHANGED' ? '변경 없음' : '실패'}</td><td>{item.attempt}회</td><td>{item.errorCode || '—'}</td></tr>)}</tbody></table></section><div className={common.pagination}><Button variant="secondary" disabled={!page} onClick={() => { setResult(null); setPage(value => value - 1); }}>이전</Button><span>{page + 1}페이지 · 총 {result.totalElements}건</span><Button variant="secondary" disabled={(page + 1) * HUB_PAGE_SIZE >= result.totalElements} onClick={() => { setResult(null); setPage(value => value + 1); }}>다음</Button></div></> : <p className={common.state}>기록된 수집 항목이 없습니다.</p> : !error && <p role="status">항목을 불러오는 중입니다.</p>}
  </Modal>;
}
