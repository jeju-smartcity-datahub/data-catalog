import { createRequestId } from '../../lib/requestId';
import { adminUrl } from '../../lib/paths';
import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { adminDownload, adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type Dictionary, type DictionaryEntry, type EntryInput, type MetadataOptions, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { RelationshipEditor } from './RelationshipEditor';
import { StandardsDetail } from './StandardsDetail';
import { StandardsDetailTabs } from './StandardsDetailTabs';
import { TermFieldUsages } from './TermFieldUsages';
import standardStyles from './StandardsDetail.module.css';
import styles from '../metadata/Metadata.module.css';
import pageStyles from './Governance.module.css';

const kinds = [{ value: 'TERM', label: '표준 용어' }, { value: 'WORD', label: '표준 단어' }, { value: 'DOMAIN', label: '표준 도메인' }];
const stateNames: Record<string, string> = { DRAFT: '초안', IN_REVIEW: '승인 대기', APPROVED: '승인', REJECTED: '반려' };
export function Standards({ session }: { session: AdminSession }) {
  const [scope, setScope] = useState('PUBLIC');
  const [options, setOptions] = useState<MetadataOptions | null>(null);
  const [organization, setOrganization] = useState(session.organizationIds[0] || '');
  const [dictionaries, setDictionaries] = useState<Dictionary[] | null>(null);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [createDictionary, setCreateDictionary] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => { const controller = new AbortController(); adminGet<MetadataOptions>('/metadata-options', controller.signal).then(({ data }) => setOptions(data)).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, []);
  const activeOrganization = organization || options?.organizations[0]?.id || '';
  useEffect(() => {
    const controller = new AbortController();
    if (scope === 'ORGANIZATION' && !activeOrganization) return;
    adminGet<Dictionary[]>(`/dictionaries${scope === 'ORGANIZATION' ? `?organizationId=${encodeURIComponent(activeOrganization)}` : ''}`, controller.signal).then(({ data }) => { setDictionaries(data.filter(item => item.scope === scope)); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [scope, activeOrganization, refresh]);
  const dictionary = dictionaries?.find(item => item.id === selected) || dictionaries?.[0];
  return <>
    <fieldset className={`${styles.tabs} ${standardStyles.scopeSelector}`}><legend className="sr-only">사전 범위</legend><button type="button" aria-pressed={scope === 'PUBLIC'} onClick={() => { if (scope === 'PUBLIC') return; setDictionaries(null); setSelected(''); setScope('PUBLIC'); }}>공공 표준 사전</button><button type="button" aria-pressed={scope === 'ORGANIZATION'} onClick={() => { if (scope === 'ORGANIZATION') return; setDictionaries(null); setSelected(''); setScope('ORGANIZATION'); }}>우리 기관 사전</button></fieldset>
    {notice && <p className={styles.message} role="status">{notice}</p>}{error && <p className={styles.error} role="alert">{error}</p>}
    <div className={styles.toolbar}>
      {scope === 'ORGANIZATION' && <label>기관<select className={styles.select} value={activeOrganization} onChange={event => { setDictionaries(null); setSelected(''); setOrganization(event.target.value); }}>{options?.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}
      <label className={styles.search}>사전 및 버전<select className={styles.select} value={dictionary?.id || ''} onChange={event => setSelected(event.target.value)}><option value="" disabled>사전을 선택해 주세요</option>{dictionaries?.map(item => <option key={item.id} value={item.id}>{item.name} · {item.versionLabel}</option>)}</select></label>
      <Button variant="secondary" onClick={() => { setDictionaries(null); setRefresh(value => value + 1); }}>새로고침</Button>
      {scope === 'ORGANIZATION' && hasAuthority(session, 'ORG_EDITOR') && <Button disabled={!activeOrganization} onClick={() => setCreateDictionary(true)}>사전 버전 등록</Button>}
    </div>
    {dictionary ? <><section className={styles.panel}><h2>{dictionary.name} <Badge>{dictionary.versionLabel}</Badge></h2><p className={pageStyles.source}>{scope === 'PUBLIC' ? '공공 표준 원본은 읽기 전용입니다.' : '기관 표준은 초안을 등록하고 승인을 거쳐 사용합니다.'}</p>{dictionary.sourceUri && <p className={pageStyles.source}>출처: {safeSource(dictionary.sourceUri) ? <a href={dictionary.sourceUri} target="_blank" rel="noreferrer">공식 자료 보기 <img src={adminUrl('/assets/external-link.svg')} alt="새 창" /></a> : dictionary.sourceUri}</p>}{dictionary.sourceChecksum && <p className={pageStyles.source}>원본 검증값: {dictionary.sourceChecksum}</p>}</section><Entries key={dictionary.id} session={session} dictionary={dictionary} /></> : dictionaries ? <section className={styles.panel}><p className={styles.state}>{scope === 'PUBLIC' ? '확인된 공공 표준 원본이 아직 등록되지 않았습니다.' : '이 기관에 등록된 사전 버전이 없습니다.'}</p></section> : !error && <p className={styles.state} role="status">사전 버전을 불러오는 중입니다.</p>}
    {createDictionary && <CreateDictionary session={session} organizationId={activeOrganization} onClose={() => setCreateDictionary(false)} onCreated={item => { setCreateDictionary(false); setSelected(item.id); setNotice('기관 사전 버전을 등록했습니다. 표준 항목을 추가해 주세요.'); setDictionaries(null); setRefresh(value => value + 1); }} />}
  </>;
}

function Entries({ session, dictionary }: { session: AdminSession; dictionary: Dictionary }) {
  const selectionId = useId();
  const [kind, setKind] = useState('TERM');
  const [entries, setEntries] = useState<DictionaryEntry[] | null>(null);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [edit, setEdit] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  useEffect(() => { const controller = new AbortController(); adminGet<DictionaryEntry[]>(`/dictionaries/${dictionary.id}/entries?kind=${kind}&page=${page}&size=20${query ? `&q=${encodeURIComponent(query)}` : ''}${status ? `&state=${status}` : ''}`, controller.signal).then(({ data }) => { setEntries(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [dictionary.id, kind, page, query, status, refresh]);
  const [exporting, setExporting] = useState(false);
  const exportRunning = useRef(false);
  const exportKeys = useRef(new Map<string, string>());
  async function exportEntries(selectedOnly = false) {
    if (exportRunning.current) return; exportRunning.current = true; setExporting(true); setError('');
    const body = { kind, query: query || undefined, state: status || undefined, entryIds: selectedOnly ? [...selectedIds].sort((left, right) => left < right ? -1 : left > right ? 1 : 0) : undefined };
    const fingerprint = JSON.stringify(body); let key = exportKeys.current.get(fingerprint); if (!key) { key = createRequestId(); exportKeys.current.set(fingerprint, key); }
    try {
      const result = await adminWrite<components['schemas']['DictionaryExport']>(`/dictionary-versions/${dictionary.id}/exports`, 'POST', session, body, undefined, key);
      if (result.data.status !== 'SUCCEEDED') { setNotice('추출 요청이 접수되었습니다. 아직 다운로드할 수 없습니다.'); return; }
      await adminDownload(`/dictionary-exports/${result.data.id}/file`, result.data.fileName);
      exportKeys.current.delete(fingerprint);
      setNotice(`${result.data.entryCount}건의 표준사전 항목을 XLSX로 내려받았습니다. 다음 추출은 요청 시점의 최신 항목으로 생성합니다.`);
    } catch (error) { setError(errorMessage(error)); } finally { exportRunning.current = false; setExporting(false); }
  }
  const filtered = entries || [];
  function resetPage() { setEntries(null); setSelectedIds([]); setPage(0); }
  return <><fieldset className={pageStyles.kinds}><legend className="sr-only">표준 종류</legend>{kinds.map(item => <button className={pageStyles.kind} key={item.value} type="button" aria-pressed={kind === item.value} onClick={() => { if (kind === item.value) return; resetPage(); setKind(item.value); }}>{item.label}</button>)}</fieldset>
    <form className={styles.toolbar} onSubmit={event => { event.preventDefault(); resetPage(); setQuery(search.trim()); setRefresh(value => value + 1); }}><label>상태<select className={styles.select} value={status} onChange={event => { resetPage(); setStatus(event.target.value); }}><option value="">전체 상태</option>{Object.entries(stateNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label className={styles.search}>사전 전체 검색<Input maxLength={200} value={search} onChange={event => setSearch(event.target.value)} placeholder="명칭, 코드, 설명, 영문명 또는 물리명" /></label><Button>검색</Button><Button type="button" variant="secondary" onClick={() => { resetPage(); setSearch(''); setQuery(''); setStatus(''); setRefresh(value => value + 1); }}>검색 초기화</Button><Button type="button" variant="secondary" onClick={() => { setEntries(null); setSelectedIds([]); setRefresh(value => value + 1); }}>항목 새로고침</Button></form>
    {notice && <p className={styles.message} role="status">{notice}</p>}{error && <p className={styles.error} role="alert">{error}</p>}
    <section className={styles.panel}><div className={styles.panelHeading}><h2>{kinds.find(item => item.value === kind)?.label} 목록 {selectedIds.length > 0 && <Badge>선택 {selectedIds.length}</Badge>}</h2><div className={styles.buttonGroup}><Button variant="secondary" disabled={exporting || !selectedIds.length} onClick={() => void exportEntries(true)}>선택 항목 XLSX</Button><Button variant="secondary" disabled={exporting} onClick={() => void exportEntries()}>{exporting ? '추출 중…' : '검색 조건 전체 XLSX'}</Button>{dictionary.scope === 'ORGANIZATION' && hasAuthority(session, 'ORG_EDITOR') && <Button onClick={() => setEdit('new')}>신규 등록</Button>}</div></div>
      <p className={styles.help}>목록과 XLSX는 적용한 검색·상태 조건을 함께 사용합니다. 행 선택은 현재 페이지에 한하며 페이지·종류·조건 변경 시 초기화됩니다.</p>
      {entries ? <>{filtered.length ? <div className={styles.tableWrap}><table className={`${styles.table} ${standardStyles.entryTable}`}><caption className="sr-only">선택한 버전의 표준사전 항목</caption><thead><tr><th scope="col"><PageSelection count={selectedIds.length} total={entries.length} onChange={checked => setSelectedIds(checked ? entries.map(item => item.id) : [])} /></th><th scope="col">명칭</th><th scope="col">코드</th><th scope="col">설명</th><th scope="col">자료형</th><th scope="col">상태</th></tr></thead><tbody>{filtered.map(item => <tr key={item.id}><td><input id={`${selectionId}-${item.id}`} type="checkbox" checked={selectedIds.includes(item.id)} onChange={event => setSelectedIds(current => event.target.checked ? [...current, item.id] : current.filter(id => id !== item.id))} /><label className="sr-only" htmlFor={`${selectionId}-${item.id}`}>{item.name} 선택</label></td><td><button className={styles.rowLink} onClick={() => setEdit(item.id)}>{item.name}</button></td><td>{item.code}</td><td className={pageStyles.entryDescription}>{item.definition}</td><td>{item.dataType}</td><td><Badge>{stateNames[item.state] || item.state}</Badge></td></tr>)}</tbody></table></div> : <p className={styles.state}>이 페이지에 조건과 일치하는 항목이 없습니다.</p>}<div className={styles.pagination}><Button variant="secondary" disabled={page === 0} onClick={() => { setEntries(null); setSelectedIds([]); setPage(value => value - 1); }}>이전</Button><span>{page + 1}페이지 · {entries.length}건 조회</span><Button variant="secondary" disabled={entries.length < 20} onClick={() => { setEntries(null); setSelectedIds([]); setPage(value => value + 1); }}>다음</Button></div></> : !error && <p className={styles.state} role="status">표준 항목을 불러오는 중입니다.</p>}
    </section>
    {edit && <EntryEditor id={edit} initialKind={kind} dictionary={dictionary} session={session} onClose={() => setEdit(null)} onChanged={message => { setEdit(null); setNotice(message); setEntries(null); setSelectedIds([]); setRefresh(value => value + 1); }} />}
  </>;
}

function CreateDictionary({ session, organizationId, onClose, onCreated }: { session: AdminSession; organizationId: string; onClose: () => void; onCreated: (dictionary: Dictionary) => void }) {
  const [form, setForm] = useState({ name: '', code: '', versionLabel: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const running = useRef(false);
  const key = useRef(createRequestId());
  async function save() { if (running.current) return; running.current = true; setBusy(true); setError(''); try { const result = await adminWrite<Dictionary>('/dictionaries', 'POST', session, { ...form, organizationId }, undefined, key.current); onCreated(result.data); } catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); } }
  return <Modal open title="기관 사전 버전 등록" busy={busy} onClose={() => { if ((!form.name && !form.code && !form.versionLabel) || window.confirm('입력 중인 내용을 버리시겠습니까?')) onClose(); }}><form className={pageStyles.compactForm} onSubmit={event => { event.preventDefault(); void save(); }}>{error && <p className={styles.error} role="alert">{error}</p>}<label><span>사전명<RequiredMark /></span><Input required maxLength={200} value={form.name} disabled={busy} onChange={event => setForm(value => ({ ...value, name: event.target.value }))} /></label><label><span>사전 코드<RequiredMark /></span><Input required pattern="[A-Za-z0-9_-]{1,80}" maxLength={80} value={form.code} disabled={busy} onChange={event => setForm(value => ({ ...value, code: event.target.value }))} /></label><label><span>버전명<RequiredMark /></span><Input required maxLength={100} value={form.versionLabel} disabled={busy} onChange={event => setForm(value => ({ ...value, versionLabel: event.target.value }))} placeholder="예: 2026-01" /></label><Button disabled={busy}>{busy ? '등록 중…' : '등록'}</Button></form></Modal>;
}

function EntryEditor({ id, initialKind, dictionary, session, onClose, onChanged }: { id: string; initialKind: string; dictionary: Dictionary; session: AdminSession; onClose: () => void; onChanged: (message: string) => void }) {
  const isNew = id === 'new';
  const [resource, setResource] = useState<Resource<DictionaryEntry> | null>(null);
  const [form, setForm] = useState<EntryInput>({ kind: initialKind, code: '', name: '', definition: '', dataType: 'STRING' });
  const [baseline, setBaseline] = useState(JSON.stringify(form));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState<'submit' | 'APPROVE' | 'REJECT' | null>(null);
  const [relationshipsOpen, setRelationshipsOpen] = useState(false);
  const [termReady, setTermReady] = useState(false);
  const [detailTab, setDetailTab] = useState<'basic' | 'usages'>('basic');
  const detailTabsId = useId();
  const hasTermTabs = resource?.data.kind === 'TERM' && resource.data.state === 'APPROVED';
  const running = useRef(false);
  const keys = useRef(new Map<string, string>());
  const dirty = baseline !== JSON.stringify(form);
  const editable = dictionary.scope === 'ORGANIZATION' && hasAuthority(session, 'ORG_EDITOR') && (isNew || resource?.data.state === 'DRAFT');
  useEffect(() => { if (isNew) return; const controller = new AbortController(); adminGet<DictionaryEntry>(`/dictionary-entries/${id}`, controller.signal).then(async result => { setResource(result); const { kind, code, name, definition, dataType } = result.data; const value = { kind, code, name, definition, dataType }; setForm(value); setBaseline(JSON.stringify(value)); setError(''); if (kind === 'TERM') { const relationships = await adminGet<components['schemas']['EntryRelationships']>(`/dictionary-entries/${id}/relationships`, controller.signal); setTermReady(!!relationships.data.term?.domainEntryId && !!relationships.data.term.wordEntryIds.length); } else setTermReady(true); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [id, isNew, refresh]);
  useEffect(() => { if (!dirty) return; const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent); }, [dirty]);
  async function mutate(action: 'save' | 'submit' | 'APPROVE' | 'REJECT') {
    if (running.current) return; running.current = true; setBusy(true); setError('');
    const path = action === 'save' ? isNew ? `/dictionaries/${dictionary.id}/entries` : `/dictionary-entries/${id}` : `/dictionary-entries/${id}/${action === 'submit' ? 'review-requests' : 'decisions'}`;
    const body = action === 'save' ? form : action === 'submit' ? undefined : { decision: action, reason: reason.trim() };
    const fingerprint = JSON.stringify([path, body, resource?.etag]); let key = keys.current.get(fingerprint); if (!key) { key = createRequestId(); keys.current.set(fingerprint, key); }
    try { if (!isNew && !resource?.etag) throw new Error('버전을 확인하지 못했습니다.'); await adminWrite(path, action === 'save' && !isNew ? 'PATCH' : 'POST', session, body, resource?.etag, key); onChanged(action === 'save' ? '표준사전 초안을 저장했습니다.' : action === 'submit' ? '표준사전 항목의 검토를 요청했습니다.' : action === 'APPROVE' ? '표준사전 항목을 승인했습니다.' : '표준사전 항목을 반려했습니다.'); }
    catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  return <Modal open title={isNew ? '기관 사전 신규 등록' : '표준사전 항목 상세'} wide={isNew || editable || detailTab === 'usages'} size={!isNew && !editable && detailTab === 'basic' ? 'details' : undefined} busy={busy} onClose={() => { if (!dirty || window.confirm('저장하지 않은 변경사항을 버리시겠습니까?')) onClose(); }}>
    {hasTermTabs && <><div className={standardStyles.heading}><h3>{resource.data.name}</h3><Badge>{stateNames[resource.data.state]}</Badge></div><p className={standardStyles.description}>{resource.data.definition}</p><StandardsDetailTabs id={detailTabsId} label="용어 상세 보기" value={detailTab} tabs={[{ value: 'basic', label: '기본 정보' }, { value: 'usages', label: '연결 데이터셋' }]} onChange={value => setDetailTab(value as 'basic' | 'usages')} /></>}
    {hasTermTabs && <div role="tabpanel" id={`${detailTabsId}-panel-usages`} aria-labelledby={`${detailTabsId}-tab-usages`} hidden={detailTab !== 'usages'} tabIndex={0}>{detailTab === 'usages' && <TermFieldUsages termId={id} termName={resource.data.name} dictionary={dictionary} session={session} />}</div>}
    <div hidden={detailTab !== 'basic'} role={hasTermTabs ? 'tabpanel' : undefined} id={`${detailTabsId}-panel-basic`} aria-labelledby={hasTermTabs ? `${detailTabsId}-tab-basic` : undefined} tabIndex={hasTermTabs ? 0 : undefined}>
    {error && <div className={styles.error} role="alert">{error}{!isNew && <Button variant="secondary" disabled={busy} onClick={() => { if (!dirty || window.confirm('입력 내용을 버리고 최신 상태를 확인하시겠습니까?')) { setResource(null); setConfirm(null); setRefresh(value => value + 1); } }}>최신 상태 확인</Button>}</div>}
    {isNew || resource ? <>{!editable && resource ? <StandardsDetail key={`${resource.data.id}-${refresh}`} entry={resource.data} dictionary={dictionary} hideHeading={hasTermTabs} /> : <><div className={styles.summary}><Badge>{isNew ? '초안' : stateNames[resource!.data.state] || resource!.data.state}</Badge><span>{dictionary.name} · {dictionary.versionLabel}</span></div><form onSubmit={event => { event.preventDefault(); void mutate('save'); }}><div className={styles.formGrid}>
      <label><span>분류<RequiredMark /></span><select className={styles.select} value={form.kind} disabled={!editable || !isNew || busy} onChange={event => setForm(value => ({ ...value, kind: event.target.value }))}>{kinds.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
      <label><span>자료형<RequiredMark /></span><select className={styles.select} value={form.dataType} disabled={!editable || busy} onChange={event => setForm(value => ({ ...value, dataType: event.target.value }))}>{['STRING', 'INTEGER', 'NUMBER', 'BOOLEAN', 'DATE', 'DATETIME', 'OBJECT', 'ARRAY'].map(type => <option key={type}>{type}</option>)}</select></label>
      <label><span>명칭<RequiredMark /></span><Input required maxLength={200} value={form.name} disabled={!editable || busy} onChange={event => setForm(value => ({ ...value, name: event.target.value }))} /></label><label><span>코드<RequiredMark /></span><Input required pattern="[A-Za-z0-9_-]{1,100}" maxLength={100} value={form.code} disabled={!editable || !isNew || busy} onChange={event => setForm(value => ({ ...value, code: event.target.value }))} /></label>
      <label className={styles.full}><span>설명<RequiredMark /></span><textarea className={styles.textarea} required maxLength={4000} value={form.definition} disabled={!editable || busy} onChange={event => setForm(value => ({ ...value, definition: event.target.value }))} /></label>
    </div>{editable && <div className={styles.actions}><Button disabled={busy || (!isNew && !dirty)}>초안 저장</Button>{!isNew && <Button variant="secondary" type="button" disabled={busy || dirty || !termReady} onClick={() => setConfirm('submit')}>검토 요청</Button>}</div>}</form></>}
    {resource && editable && <div className={styles.readOnly}><h3>{form.kind === 'TERM' ? '용어 구성' : form.kind === 'DOMAIN' ? '도메인 속성' : '단어 속성'}</h3><p className={styles.help}>{form.kind === 'TERM' ? '검토 요청 전에 승인된 도메인과 구성 단어를 연결해 주세요.' : '종류별 상세 속성을 확인하거나 초안에 추가할 수 있습니다.'}</p><Button type="button" variant="secondary" disabled={busy || dirty} onClick={() => setRelationshipsOpen(true)}>속성 및 관계 편집</Button>{dirty && <p className={styles.help}>기본정보를 먼저 저장한 뒤 속성을 편집해 주세요.</p>}</div>}
    {dictionary.scope === 'ORGANIZATION' && resource?.data.state === 'IN_REVIEW' && hasAuthority(session, 'ORG_REVIEWER') && <div className={styles.actions}><Button disabled={busy || dirty} onClick={() => setConfirm('APPROVE')}>승인</Button><Button variant="danger" disabled={busy || dirty} onClick={() => setConfirm('REJECT')}>반려</Button></div>}
    {resource?.data.decisionReason && <p className={styles.help}>검토 의견: {resource.data.decisionReason}</p>}
    {resource?.data.state === 'REJECTED' && <p className={styles.help}>반려된 항목은 보존됩니다. 새 사전 버전에 보완한 항목을 등록해 주세요.</p>}
    {confirm && <form className={styles.readOnly} onSubmit={event => { event.preventDefault(); void mutate(confirm); }}><h3>{confirm === 'submit' ? '검토 요청 확인' : confirm === 'APPROVE' ? '승인 확인' : '반려 사유'}</h3>{confirm !== 'submit' && <label className={styles.reason}><span>검토 의견{confirm === 'REJECT' ? <RequiredMark /> : ' (선택)'}</span><textarea className={styles.textarea} required={confirm === 'REJECT'} maxLength={2000} value={reason} onChange={event => setReason(event.target.value)} disabled={busy} /></label>}<div className={styles.actions}><Button disabled={busy || (confirm === 'REJECT' && !reason.trim())}>{busy ? '처리 중…' : '확인'}</Button><Button type="button" variant="secondary" disabled={busy} onClick={() => setConfirm(null)}>취소</Button></div></form>}
    </> : !error && <p role="status">표준사전 항목을 불러오고 있습니다.</p>}
    {relationshipsOpen && resource && <RelationshipEditor entry={resource.data} dictionary={dictionary} session={session} editable={editable} onClose={() => setRelationshipsOpen(false)} onSaved={() => { setRelationshipsOpen(false); setResource(null); setRefresh(value => value + 1); }} />}
    </div>
  </Modal>;
}

function safeSource(source: string): boolean { try { const url = new URL(source); return url.protocol === 'https:' || url.protocol === 'http:'; } catch { return false; } }

function PageSelection({ count, total, onChange }: { count: number; total: number; onChange: (checked: boolean) => void }) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => { if (input.current) input.current.indeterminate = count > 0 && count < total; }, [count, total]);
  return <><input id={id} ref={input} type="checkbox" checked={total > 0 && count === total} onChange={event => onChange(event.target.checked)} /><label className="sr-only" htmlFor={id}>현재 페이지 전체 선택</label></>;
}
