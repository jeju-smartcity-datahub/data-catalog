import { adminUrl } from '../../lib/paths';
import { useEffect, useState } from 'react';
import { Badge, Button, Input } from '../../components/ui';
import { adminGet, errorMessage, hasAuthority, type AdminSession, type MetadataOptions } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { AdminGate } from '../../components/AdminGate';
import { dateTime } from './dateTime';
import { AnalysisEvidenceDialog } from './AnalysisEvidenceDialog';
import { SearchEventDialog } from './SearchEventDialog';
import { AnalyticsReportActions, type ReportRequest } from './AnalyticsReportActions';
import styles from './Operations.module.css';

type SearchEvent = components['schemas']['SearchEvent'];
type Statistics = components['schemas']['SearchStatistics'];
type AnalysisRun = components['schemas']['AnalysisRun'];
type AnalysisStatistics = components['schemas']['AnalysisStatistics'];
type Period = { from: string; to: string };
const today = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(new Date());
const kindLabels: Record<string, string> = { DEFINITION_IMPORT: '정의서 가져오기', DEFINITION_EXPORT: '정의서 내보내기', FIELD_MAPPING: '필드 표준 매핑' };
function period(from: string, to: string): Period | null {
  const first = new Date(`${from}T00:00:00+09:00`), last = new Date(`${to}T00:00:00+09:00`);
  const length = (last.getTime() - first.getTime()) / 86400000 + 1;
  if (!Number.isFinite(length) || length < 1 || length > 31) return null;
  last.setTime(last.getTime() + 86400000);
  return { from: first.toISOString(), to: last.toISOString() };
}
function DateFields({ from, to, setFrom, setTo }: { from: string; to: string; setFrom: (value: string) => void; setTo: (value: string) => void }) {
  return <><label>시작일<Input type="date" required value={from} max={to} onChange={event => setFrom(event.target.value)} /></label><label>종료일<Input type="date" required value={to} min={from} onChange={event => setTo(event.target.value)} /></label></>;
}
function Metric({ label, value, note, icon, tone = '' }: { label: string; value: string; note: string; icon: string; tone?: string }) {
  return <div className={styles.metric}><dt className={styles.metricHeading}><span>{label}</span><span className={`${styles.metricIcon} ${tone ? styles[tone] : ''}`}><img src={adminUrl(`/assets/${icon}.svg`)} alt="" /></span></dt><dd>{value}<span className={styles.metricNote}>{note}</span></dd></div>;
}
function Pagination({ page, next, onChange, label }: { page: number; next: boolean; onChange: (page: number) => void; label: string }) {
  return <nav className={styles.pagination} aria-label={`${label} 페이지`}><Button variant="secondary" aria-label={`${label} 이전 페이지`} disabled={page === 0} onClick={() => onChange(page - 1)}>이전</Button><span aria-current="page">{page + 1}페이지</span><Button variant="secondary" aria-label={`${label} 다음 페이지`} disabled={!next} onClick={() => onChange(page + 1)}>다음</Button></nav>;
}
function RetryState({ error, onRetry }: { error: string; onRetry: () => void }) {
  return <div className={styles.state} role="alert"><p>{error}</p><Button variant="secondary" onClick={onRetry}>다시 시도</Button></div>;
}
export function AnalyticsWorkspace({ onSession }: { onSession: (session: AdminSession | null) => void }) {
  return <AdminGate onSession={onSession}>{session => <AnalyticsContent session={session} />}</AdminGate>;
}
function AnalyticsContent({ session }: { session: AdminSession }) {
  const [tab, setTab] = useState('search');
  const tabs = [{ id: 'analysis', label: '메타데이터 분석 이력' }, { id: 'search', label: '검색 이력·통계' }];
  return <><div className={styles.tabs} role="tablist" aria-label="분석 및 검색 이력">{tabs.map((item, index) => <button key={item.id} id={`analytics-tab-${item.id}`} role="tab" aria-selected={tab === item.id} aria-controls={`analytics-panel-${item.id}`} tabIndex={tab === item.id ? 0 : -1} onClick={() => setTab(item.id)} onKeyDown={event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : (index + 1) % tabs.length;
    setTab(tabs[next].id); document.getElementById(`analytics-tab-${tabs[next].id}`)?.focus();
  }}>{item.label}</button>)}</div><div role="tabpanel" id={`analytics-panel-${tab}`} aria-labelledby={`analytics-tab-${tab}`}>{tab === 'search' ? hasAuthority(session, 'PLATFORM_ADMIN') ? <SearchHistory session={session} /> : <section className={styles.panel}><p className={styles.state}>검색 이력 조회에는 플랫폼 관리 권한이 필요합니다.</p></section> : hasAuthority(session, 'ORG_AUDITOR') ? <AnalysisHistory session={session} /> : <section className={styles.panel}><p className={styles.state}>분석 이력 조회에는 감사 권한이 필요합니다.</p></section>}</div><div hidden role="tabpanel" id={`analytics-panel-${tab === 'search' ? 'analysis' : 'search'}`} aria-labelledby={`analytics-tab-${tab === 'search' ? 'analysis' : 'search'}`} /></>;
}
function SearchHistory({ session }: { session: AdminSession }) {
  const [selected, setSelected] = useState<SearchEvent | null>(null);
  const [from, setFrom] = useState(today), [to, setTo] = useState(today);
  const [hasQuery, setHasQuery] = useState(''), [zeroResults, setZeroResults] = useState('');
  const [validation, setValidation] = useState('');
  const [query, setQuery] = useState(() => ({ ...period(today(), today())!, hasQuery: '', zeroResults: '', page: 0 }));
  const [state, setState] = useState<{ rows: SearchEvent[]; statistics: Statistics } | { error: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    const parameters = new URLSearchParams({ from: query.from, to: query.to });
    if (query.hasQuery) parameters.set('hasQuery', query.hasQuery);
    if (query.zeroResults) parameters.set('zeroResults', query.zeroResults);
    Promise.all([adminGet<SearchEvent[]>(`/search-history?${parameters}&page=${query.page}&size=20`, controller.signal), adminGet<Statistics>(`/analytics/search-statistics?${parameters}`, controller.signal)])
      .then(([rows, statistics]) => setState({ rows: rows.data, statistics: statistics.data })).catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); });
    return () => controller.abort();
  }, [query]);
  const report: ReportRequest = { kind: 'SEARCH', from: query.from, to: query.to, hasQuery: query.hasQuery ? query.hasQuery === 'true' : undefined, zeroResults: query.zeroResults ? query.zeroResults === 'true' : undefined };
  return <><AnalyticsReportActions key={JSON.stringify(report)} session={session} request={report} disabled={!state || 'error' in state} />
    <form className={styles.toolbar} onSubmit={event => { event.preventDefault(); const range = period(from, to); if (!range) { setValidation('조회 기간을 1일 이상 31일 이하로 설정하세요.'); return; } setValidation(''); setState(null); setQuery({ ...range, hasQuery, zeroResults, page: 0 }); }}><DateFields {...{ from, to, setFrom, setTo }} /><label>검색어 포함<select value={hasQuery} onChange={event => setHasQuery(event.target.value)}><option value="">전체</option><option value="true">포함</option><option value="false">미포함</option></select></label><label>검색 결과<select value={zeroResults} onChange={event => setZeroResults(event.target.value)}><option value="">전체</option><option value="true">결과 없음</option><option value="false">결과 있음</option></select></label><Button>조회</Button><p className={styles.filterHelp}>한국 시간 기준 · 최대 31일</p>{validation && <p className={styles.validation} role="alert">{validation}</p>}</form>
    {!state ? <section className={styles.panel}><p role="status">검색 이력을 불러오는 중입니다.</p></section> : 'error' in state ? <section className={styles.panel}><RetryState error={state.error} onRetry={() => { setState(null); setQuery(value => ({ ...value })); }} /></section> : <>
      <dl className={styles.stats} aria-label="검색 통계"><Metric label="검색 실행 건수" value={`${state.statistics.searchCount.toLocaleString('ko-KR')}건`} note="선택 조건의 실제 공개 검색" icon="analytics-search" /><Metric label="결과 없는 검색" value={`${state.statistics.zeroResultCount.toLocaleString('ko-KR')}건`} note="검색 결과 0건" icon="analytics-noresult" /><Metric label="결과 없음 비율" value={`${state.statistics.searchCount ? (state.statistics.zeroResultCount / state.statistics.searchCount * 100).toFixed(1) : '0.0'}%`} note="선택 조건의 검색 기준" icon="analytics-percent" /><Metric label="질의 원문" value="미보관" note="정제된 분류 표현만 수집합니다" icon="analytics-unique" tone="purple" /></dl>
      <section className={styles.panel}><h2>검색 이력</h2><p>개인정보를 제외한 검색 표현·표시된 공개 결과·선택형 평가를 상세 보기에서 확인합니다. 이용자 ID·IP·검색어 원문·자유서술은 보관하지 않습니다. 프로젝트 보유기간은 5년입니다.</p>{state.rows.length ? <section className={styles.tableWrap} aria-label="검색 이력 표" tabIndex={0}><table><caption className="sr-only">검색 이력과 결과 수</caption><thead><tr><th scope="col">검색 시각</th><th scope="col">검색어 포함</th><th scope="col">공개 필터</th><th scope="col">결과</th><th scope="col">처리 시간</th><th scope="col">상세</th></tr></thead><tbody>{state.rows.map(row => <tr key={row.id}><td>{dateTime(row.occurredAt)}</td><td>{row.hasQuery ? '포함' : '미포함'}</td><td>{Object.keys(row.normalizedFilters).filter(key => key.endsWith('Id')).length}개 조건</td><td>{row.resultCount}건</td><td>{row.durationMs}ms</td><td><Button variant="secondary" onClick={() => setSelected(row)} aria-label={`${dateTime(row.occurredAt)} 검색 이력 상세 보기`}>상세 보기</Button></td></tr>)}</tbody></table></section> : <p className={styles.state}>선택한 조건에 검색 이력이 없습니다.</p>}<Pagination label="검색 이력" page={query.page} next={(query.page + 1) * 20 < state.statistics.searchCount} onChange={page => { setState(null); setQuery(value => ({ ...value, page })); }} /></section></>}
    {selected && <SearchEventDialog event={selected} onClose={() => setSelected(null)} />}
  </>;
}
function AnalysisHistory({ session }: { session: AdminSession }) {
  const [selectedRun, setSelectedRun] = useState<AnalysisRun | null>(null);
  const [options, setOptions] = useState<MetadataOptions | null>(null), [optionsError, setOptionsError] = useState(''), [optionsAttempt, setOptionsAttempt] = useState(0);
  const [organization, setOrganization] = useState(session.organizationIds[0] || '');
  const [from, setFrom] = useState(today), [to, setTo] = useState(today), [kind, setKind] = useState(''), [status, setStatus] = useState(''), [text, setText] = useState('');
  const [validation, setValidation] = useState('');
  const [query, setQuery] = useState(() => ({ ...period(today(), today())!, organization, kind: '', status: '', text: '', page: 0 }));
  const [state, setState] = useState<{ rows: AnalysisRun[]; statistics: AnalysisStatistics } | { error: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<MetadataOptions>('/metadata-options', controller.signal).then(({ data }) => { setOptions(data); setOptionsError(''); }).catch(error => { if (!controller.signal.aborted) setOptionsError(errorMessage(error)); });
    return () => controller.abort();
  }, [optionsAttempt]);
  useEffect(() => {
    if (!query.organization) return;
    const controller = new AbortController();
    const parameters = new URLSearchParams({ organizationId: query.organization, from: query.from, to: query.to });
    if (query.kind) parameters.set('kind', query.kind);
    if (query.status) parameters.set('status', query.status);
    if (query.text) parameters.set('query', query.text);
    Promise.all([adminGet<AnalysisRun[]>(`/analysis-runs?${parameters}&page=${query.page}&size=20`, controller.signal), adminGet<AnalysisStatistics>(`/analytics/analysis-statistics?${parameters}`, controller.signal)])
      .then(([rows, statistics]) => setState({ rows: rows.data, statistics: statistics.data })).catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); });
    return () => controller.abort();
  }, [query]);
  const report: ReportRequest = { kind: 'ANALYSIS', organizationId: query.organization, from: query.from, to: query.to, analysisKind: (query.kind || undefined) as ReportRequest['analysisKind'], status: (query.status || undefined) as ReportRequest['status'], query: query.text || undefined };
  return <><AnalyticsReportActions key={JSON.stringify(report)} session={session} request={report} disabled={!query.organization || !state || 'error' in state} />
    <form className={styles.toolbar} onSubmit={event => { event.preventDefault(); const range = period(from, to); if (!range) { setValidation('조회 기간을 1일 이상 31일 이하로 설정하세요.'); return; } setValidation(''); setState(null); setQuery({ ...range, organization, kind, status, text: text.trim(), page: 0 }); }}><label>기관<select required value={organization} onChange={event => setOrganization(event.target.value)}><option value="">기관 선택</option>{options?.organizations.map(option => <option key={option.id} value={option.id}>{option.name}</option>)}</select></label><DateFields {...{ from, to, setFrom, setTo }} /><label>처리 종류<select value={kind} onChange={event => setKind(event.target.value)}><option value="">전체</option>{Object.entries(kindLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label><label>처리 상태<select value={status} onChange={event => setStatus(event.target.value)}><option value="">전체</option><option value="SUCCEEDED">완료</option><option value="FAILED">실패</option></select></label><label className={styles.search}>처리 식별자<Input value={text} maxLength={100} pattern={'[A-Za-z0-9._:\\-]*'} title="영문, 숫자, 마침표, 밑줄, 콜론, 하이픈" placeholder="실행 ID · 처리기 버전 · 원본 해시" onChange={event => setText(event.target.value)} /></label><Button disabled={!organization}>조회</Button><p className={styles.filterHelp}>한국 시간 기준 · 최대 31일</p>{validation && <p className={styles.validation} role="alert">{validation}</p>}</form>
    {optionsError && <RetryState error={optionsError} onRetry={() => setOptionsAttempt(value => value + 1)} />}
    {state && !('error' in state) && <dl className={styles.stats} aria-label="분석 통계"><Metric label="전체 처리 건수" value={`${state.statistics.runCount.toLocaleString('ko-KR')}건`} note="선택 조건의 실제 처리" icon="analytics-search" /><Metric label="처리 완료" value={`${state.statistics.succeededCount.toLocaleString('ko-KR')}건`} note={`처리 필드 ${state.statistics.fieldCountSum.toLocaleString('ko-KR')}개`} icon="analytics-complete" tone="green" /><Metric label="처리 실패" value={`${state.statistics.failedCount.toLocaleString('ko-KR')}건`} note="저장된 정의서 파싱 실패" icon="analytics-warning" tone="red" /><Metric label="확인 근거" value={`${state.statistics.evidenceCount.toLocaleString('ko-KR')}개`} note="처리 시점의 필드 근거" icon="analytics-evidence" tone="purple" /></dl>}
    <section className={styles.panel}><h2>메타데이터 분석 이력</h2><p>정의서 가져오기·내보내기와 필드 표준 매핑의 실제 처리 결과와 근거를 확인합니다.</p>{!query.organization ? <p className={styles.state}>조회할 기관을 선택하세요.</p> : !state ? <p className={styles.state} role="status">분석 이력을 불러오는 중입니다.</p> : 'error' in state ? <RetryState error={state.error} onRetry={() => { setState(null); setQuery(value => ({ ...value })); }} /> : <>{state.rows.length ? <section className={styles.tableWrap} aria-label="분석 이력 표" tabIndex={0}><table><caption className="sr-only">정의서와 필드 처리 이력</caption><thead><tr><th scope="col">처리 시각</th><th scope="col">종류</th><th scope="col">대상 버전</th><th scope="col">필드 수</th><th scope="col">상태</th><th scope="col">확인 근거</th></tr></thead><tbody>{state.rows.map(row => <tr key={row.id}><td>{dateTime(row.startedAt)}</td><td>{kindLabels[row.kind] || row.kind}</td><td className={styles.identifier}>{row.datasetRevisionId || '초안 적용 전'}</td><td>{row.fieldCount}</td><td><Badge tone={row.status === 'FAILED' ? 'danger' : 'success'}>{row.status === 'FAILED' ? '실패' : '완료'}</Badge>{row.errorCode && <span className={styles.failureReason}>정의서 형식 확인 필요</span>}</td><td><Button variant="secondary" onClick={() => setSelectedRun(row)} aria-label={`${dateTime(row.startedAt)} 확인 근거 보기`}>근거 보기</Button></td></tr>)}</tbody></table></section> : <p className={styles.state}>선택한 조건에 처리 이력이 없습니다.</p>}<Pagination label="분석 이력" page={query.page} next={(query.page + 1) * 20 < state.statistics.runCount} onChange={page => { setState(null); setQuery(value => ({ ...value, page })); }} /></>}</section>{selectedRun && <AnalysisEvidenceDialog run={selectedRun} onClose={() => setSelectedRun(null)} />}</>;
}
