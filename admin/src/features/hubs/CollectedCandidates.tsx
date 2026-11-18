import { createRequestId } from '../../lib/requestId';
import { adminUrl } from '../../lib/paths';
import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button } from '../../components/ui';
import { AdminApiError, adminGet, adminWrite, errorMessage, type AdminSession } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import common from '../metadata/Metadata.module.css';
import styles from './Hubs.module.css';
import { candidateFieldLabel, candidateValue, mappingExclusions } from './candidateMapping';
import { BatchPublicationModal } from './PublicationControls';
import { canPublishCandidates } from './publicationPermissions';
import publication from './PublicationControls.module.css';
import { HubPagination } from './HubPagination';
import { HUB_PAGE_SIZE, pageWindow, selectionAfterPage } from './hubPageWindow';

type CandidatePage = components['schemas']['CandidatePage'];
type CandidateDetail = components['schemas']['CandidateDetail'];
type Application = components['schemas']['CandidateApplication'];
type PublicationHistory = components['schemas']['PublicationHistory'];
const states: Record<string, string> = { NEW: '신규', UPDATED: '허브 갱신', APPLIED: '메타데이터 반영' };
const historyTime = (value: string) => new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Seoul' }).format(new Date(value));
const PAGE_SIZE = HUB_PAGE_SIZE;

export function CollectedCandidates({ connectionId, session, refresh, canEdit, onApplied }: { connectionId: string; session: AdminSession; refresh: number; canEdit: boolean; onApplied: (message?: string) => void }) {
  const candidatesId = useId();
  const requestedPage = useRef(0);
  const displayedPage = useRef(0);
  const [paging, setPaging] = useState(false);
  const [result, setResult] = useState<CandidatePage | null>(null);
  const [error, setError] = useState('');
  const [candidateRetry, setCandidateRetry] = useState(0);
  const [selected, setSelected] = useState('');
  const [applied, setApplied] = useState<Application | null>(null);
  const [appliedExclusions, setAppliedExclusions] = useState<string[]>([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [publishing, setPublishing] = useState<string[] | null>(null);
  const [history, setHistory] = useState<PublicationHistory | null>(null);
  const [historyError, setHistoryError] = useState('');
  const [publishedRevision, setPublishedRevision] = useState(0);
  const [historyRetry, setHistoryRetry] = useState(0);
  const historyVersion = useRef<string | null>(null);
  const selectAll = useRef<HTMLInputElement>(null);
  const canPublish = canPublishCandidates(session);
  useEffect(() => {
    const controller = new AbortController();
    const page = requestedPage.current;
    adminGet<CandidatePage>(`/connections/${connectionId}/candidates?page=${page}&size=${PAGE_SIZE}`, controller.signal)
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        const bounded = pageWindow(data.totalElements, page);
        if (bounded.page !== page) { requestedPage.current = bounded.page; setPaging(true); setCandidateRetry(value => value + 1); return; }
        const previousPage = displayedPage.current;
        displayedPage.current = data.page; requestedPage.current = data.page;
        setChecked(values => selectionAfterPage(values, previousPage, data.page, data.items.map(item => item.id)));
        setResult(data); setPaging(false); setError('');
      }).catch(error => {
        if (!controller.signal.aborted) { requestedPage.current = displayedPage.current; setPaging(false); setError(errorMessage(error)); }
      });
    return () => controller.abort();
  }, [connectionId, refresh, publishedRevision, candidateRetry]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      let delay = 15000;
      try {
        const { data } = await adminGet<PublicationHistory>(`/connections/${connectionId}/publication-results`, controller.signal);
        if (controller.signal.aborted) return;
        const version = JSON.stringify(data.items.map(item => [item.candidateId, item.status, item.occurredAt]));
        if (historyVersion.current !== null && historyVersion.current !== version) setPublishedRevision(value => value + 1);
        historyVersion.current = version; setHistory(data); setHistoryError('');
        if (data.pendingCount > 0 || data.runningCount > 0) delay = 5000;
      } catch (error) {
        if (controller.signal.aborted) return;
        setHistoryError(errorMessage(error));
        if (error instanceof AdminApiError && [401, 403].includes(error.status)) return;
      }
      if (!controller.signal.aborted) timer = setTimeout(() => void poll(), delay);
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [connectionId, refresh, historyRetry]);
  const checkedOnPage = result?.items.filter(item => checked.includes(item.id)).length || 0;
  const changingPage = paging;
  useEffect(() => { if (selectAll.current) selectAll.current.indeterminate = checkedOnPage > 0 && checkedOnPage < (result?.items.length || 0); }, [checkedOnPage, result]);
  function changePage(value: number) { requestedPage.current = value; setError(''); setPaging(true); setCandidateRetry(retry => retry + 1); }
  return <section id={`collected-data-${connectionId}`} className={styles.panel} aria-labelledby={`${candidatesId}-heading`}><div className={styles.heading}><div><h2 id={`${candidatesId}-heading`}>수집한 데이터</h2><p>항목을 선택해 게시하거나 변경 내용을 확인합니다.</p></div></div><div className={styles.history}>
    {error && <div className={common.error} role="alert">{error}<Button variant="secondary" onClick={() => { setPaging(true); setCandidateRetry(value => value + 1); }}>목록 다시 조회</Button></div>}
    {changingPage && <p role="status">수집 데이터를 불러오고 있습니다.</p>}
    {historyError && <div className={common.error} role="alert">게시 이력을 불러오지 못했습니다. {historyError}<Button variant="secondary" onClick={() => setHistoryRetry(value => value + 1)}>게시 상태 다시 조회</Button></div>}
    {history && <p className={common.message} role="status">자동 게시 준비 {history.pendingCount ?? 0}건 · 게시 처리 중 {history.runningCount ?? 0}건 · 최근 게시 결과: 성공 {history.items.filter(item => item.status === 'PUBLISHED').length}건, 제외 {history.items.filter(item => item.status === 'SKIPPED').length}건, 실패 {history.items.filter(item => item.status === 'FAILED').length}건</p>}
    {applied && <p className={common.message} role="status">메타데이터 초안에 적용했습니다. {appliedExclusions.length > 0 && <>별도 확인: {appliedExclusions.join(', ')}. </>}<a href={adminUrl(`/metadata?datasetId=${encodeURIComponent(applied.datasetId)}`)}>메타데이터에서 확인</a></p>}
    {result ? result.items.length ? <><div className={publication.toolbar}><output>{checked.length}건 선택 · 현재 목록 {result.items.length}건</output>{canPublish ? <Button type="button" disabled={changingPage || !checked.length} onClick={() => setPublishing([...checked])}>선택 게시</Button> : <span>일괄 게시에는 편집·검토·게시 권한이 필요합니다.</span>}</div><section className={common.tableWrap} aria-label="수집 후보 표" tabIndex={0}><table className={`${common.table} ${publication.table}`}><caption className="sr-only">현재 허브의 수집 데이터</caption><thead><tr><th scope="col"><label className={publication.selection}><input ref={selectAll} type="checkbox" aria-label="현재 목록 전체 선택" disabled={changingPage || !canPublish} checked={result.items.length > 0 && checkedOnPage === result.items.length} onChange={event => setChecked(values => event.target.checked ? [...new Set([...values, ...result.items.map(item => item.id)])] : values.filter(id => !result.items.some(item => item.id === id)))} /></label></th><th scope="col">제목</th><th scope="col">허브 식별자</th><th scope="col">상태</th><th scope="col">검토</th></tr></thead><tbody>{result.items.map(item => {
      const latest = history?.items.find(value => value.candidateId === item.id);
      return <tr key={item.id}><td><label className={publication.selection}><input type="checkbox" aria-label={`${item.title} 선택`} disabled={changingPage || !canPublish} checked={checked.includes(item.id)} onChange={event => setChecked(value => event.target.checked ? [...value, item.id] : value.filter(id => id !== item.id))} /></label></td><td>{item.title}</td><td>{item.externalId}</td><td><Badge>{states[item.state] || item.state}</Badge>{latest && <><p className={publication.note}>최근 {latest.mode === 'AUTO' ? '자동 게시' : '선택 게시'}: {latest.status === 'PUBLISHED' ? '성공' : latest.status === 'FAILED' ? '실패' : '제외'} · <time dateTime={latest.occurredAt}>{historyTime(latest.occurredAt)}</time></p><p className={publication.note}>{latest.message}</p></>}</td><td><Button variant="secondary" disabled={changingPage} onClick={() => setSelected(item.id)}>변경 비교</Button></td></tr>;
    })}</tbody></table></section><HubPagination label="수집 데이터" total={result.totalElements} page={result.page} onChange={changePage} disabled={changingPage} /></> : <p className={common.state}>수집된 데이터가 없습니다.</p> : !error && <p role="status">수집 데이터를 불러오고 있습니다.</p>}
  </div>{selected && <CandidateComparison id={selected} session={session} canEdit={canEdit} onClose={() => setSelected('')} onApplied={(application, excluded) => { setApplied(application); setAppliedExclusions(excluded); setSelected(''); onApplied(); }} />}{publishing && <BatchPublicationModal connectionId={connectionId} candidateIds={publishing} session={session} onClose={() => setPublishing(null)} onPublished={message => { setPublishing(null); setChecked([]); setApplied(null); onApplied(message); }} />}</section>;
}

function CandidateComparison({ id, session, canEdit, onClose, onApplied }: { id: string; session: AdminSession; canEdit: boolean; onClose: () => void; onApplied: (application: Application, excluded: string[]) => void }) {
  const [detail, setDetail] = useState<CandidateDetail | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const running = useRef(false);
  const key = useRef(createRequestId());
  useEffect(() => { const controller = new AbortController(); adminGet<CandidateDetail>(`/candidates/${id}`, controller.signal).then(({ data }) => { setDetail(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [id, refresh]);
  async function apply() {
    if (!detail || running.current) return;
    running.current = true; setBusy(true); setError('');
    try {
      const { data } = await adminWrite<Application>(`/candidates/${id}/applications`, 'POST', session, { targetEtag: detail.targetEtag || null }, detail.etag, key.current);
      onApplied(data, mappingExclusions(detail.metadata).map(item => item.label));
    } catch (error) { setError(errorMessage(error)); }
    finally { running.current = false; setBusy(false); }
  }
  return <Modal open wide busy={busy} title="수집 후보 변경 비교" onClose={onClose}>
    {error && <p className={common.error} role="alert">{error}</p>}
    {detail ? <><h3 className={common.subheading}>{detail.candidate.title}</h3><p className={common.help}>{detail.candidate.datasetId ? '현재 메타데이터와 마지막으로 적용한 허브 제공값을 비교합니다. 허브에서 바뀌지 않은 필드는 현재 수동 편집값을 유지합니다.' : '아래 버튼은 비공개 초안만 생성합니다. 바로 게시하려면 수집 목록에서 선택 게시를 사용하세요.'}</p>
      {detail.conflict && <p className={common.error} role="alert">허브 변경과 수동 편집이 충돌하는 필드가 있습니다. 메타데이터에서 해당 필드를 허브 제공값으로 수정하거나 이전 허브 제공값으로 되돌린 뒤 비교를 다시 확인하세요.</p>}
      {detail.targetState === 'IN_REVIEW' && <p className={common.message}>검토 중인 대상입니다. 검토를 철회한 뒤 변경을 적용할 수 있습니다.</p>}
      <section className={common.tableWrap} aria-label="허브 변경 비교 표" tabIndex={0}><table className={`${common.table} ${styles.comparison}`}><caption className="sr-only">메타데이터와 필드 정의의 허브 변경 및 수동 편집 비교</caption><thead><tr><th scope="col">필드</th><th scope="col">이전 허브 제공값</th><th scope="col">현재 메타데이터</th><th scope="col">새 허브 제공값</th><th scope="col">처리</th></tr></thead><tbody>{detail.changes.map(change => <tr key={change.field}><th scope="row">{candidateFieldLabel(change.field)}</th><td><CandidateValue value={change.previousSourceValue} /></td><td><CandidateValue value={change.currentValue} /></td><td><CandidateValue value={change.sourceValue} /></td><td>{change.action === 'CONFLICT' ? '충돌 확인 필요' : change.action === 'APPLY_SOURCE' ? '허브 제공값 적용' : '현재값 유지'}</td></tr>)}</tbody></table></section>
      <MappingExclusions metadata={detail.metadata} />
      <div className={styles.actions}>{detail.candidate.datasetId && <a href={adminUrl(`/metadata?datasetId=${encodeURIComponent(detail.candidate.datasetId)}`)}>대상 메타데이터 확인</a>}<Button variant="secondary" disabled={busy} onClick={() => { setDetail(null); key.current = createRequestId(); setRefresh(value => value + 1); }}>비교 다시 확인</Button>{canEdit && <Button disabled={busy || detail.conflict || detail.targetState === 'IN_REVIEW' || detail.candidate.state === 'APPLIED'} onClick={() => void apply()}>{busy ? '적용 중…' : detail.candidate.datasetId ? '허브 변경을 초안에 적용' : '비공개 초안 생성'}</Button>}</div>
    </> : !error && <p role="status">허브와 메타데이터를 비교하고 있습니다.</p>}
  </Modal>;
}

function CandidateValue({ value }: { value: unknown }) {
  const display = candidateValue(value);
  return display.summary ? <details><summary>{display.summary}</summary><div className={styles.trace}>{display.text}</div></details> : <>{display.text}</>;
}

function MappingExclusions({ metadata }: { metadata?: Record<string, unknown> }) {
  const excluded = mappingExclusions(metadata);
  if (!excluded.length) return null;
  return <section className={common.message} aria-label="자동 적용 제외 안내">
    <h4>일반 필드 자동 적용 제외 ({excluded.length}건)</h4>
    <p>형식이 맞지 않아 자동 입력하지 못한 항목입니다. 원문을 확인하고 메타데이터에서 수정하세요.</p>
    <ul>{excluded.map(item => <li key={item.sourceKey}><strong>{item.label}</strong>: {item.reasons.join(' ')}<details><summary>{item.label} 원문 전체 보기</summary><CandidateValue value={item.value} /></details></li>)}</ul>
  </section>;
}
