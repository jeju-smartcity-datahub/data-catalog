import { useEffect, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, type AdminSession, type Dictionary, type DictionaryEntry, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import styles from '../metadata/Metadata.module.css';

type Relationships = components['schemas']['EntryRelationships'];
type RelationshipForm = { englishName: string; abbreviation: string; synonyms: string; maxLength: string; precision: string; scale: string; format: string; unit: string; allowedValues: string; physicalName: string; domainEntryId: string; wordEntryIds: string[] };
const initialForm: RelationshipForm = { englishName: '', abbreviation: '', synonyms: '', maxLength: '', precision: '', scale: '', format: '', unit: '', allowedValues: '', physicalName: '', domainEntryId: '', wordEntryIds: [] };
const lines = (value: string) => value.split(/\r?\n/).map(item => item.trim()).filter(Boolean);
export function RelationshipEditor({ entry, dictionary, session, editable, onClose, onSaved }: { entry: DictionaryEntry; dictionary: Dictionary; session: AdminSession; editable: boolean; onClose: () => void; onSaved: () => void }) {
  const [resource, setResource] = useState<Resource<Relationships> | null>(null);
  const [form, setForm] = useState<RelationshipForm>(initialForm);
  const [baseline, setBaseline] = useState(JSON.stringify(initialForm));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const [picker, setPicker] = useState<'DOMAIN' | 'WORD' | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});
  const running = useRef(false);
  const dirty = JSON.stringify(form) !== baseline;
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Relationships>(`/dictionary-entries/${entry.id}/relationships`, controller.signal).then(async result => {
      const next = { englishName: result.data.word?.englishName || '', abbreviation: result.data.word?.abbreviation || '', synonyms: result.data.word?.synonyms?.join('\n') || '', maxLength: String(result.data.domain?.maxLength ?? ''), precision: String(result.data.domain?.precision ?? ''), scale: String(result.data.domain?.scale ?? ''), format: result.data.domain?.format || '', unit: result.data.domain?.unit || '', allowedValues: result.data.domain?.allowedValues?.join('\n') || '', physicalName: result.data.term?.physicalName || '', domainEntryId: result.data.term?.domainEntryId || '', wordEntryIds: result.data.term?.wordEntryIds || [] };
      setResource(result); setForm(next); setBaseline(JSON.stringify(next)); setError('');
      const ids = [next.domainEntryId, ...next.wordEntryIds].filter(Boolean);
      const refs = await Promise.all(ids.map(id => adminGet<DictionaryEntry>(`/dictionary-entries/${id}`, controller.signal)));
      setNames(Object.fromEntries(refs.map(ref => [ref.data.id, ref.data.name])));
    }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [entry.id, retry]);
  useEffect(() => { if (!dirty) return; const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent); }, [dirty]);
  function change(key: keyof RelationshipForm, value: string) { setForm(current => ({ ...current, [key]: value })); }
  async function save() {
    if (!resource?.etag || running.current) return; running.current = true; setBusy(true); setError('');
    const body = entry.kind === 'WORD' ? { englishName: form.englishName || undefined, abbreviation: form.abbreviation || undefined, synonyms: lines(form.synonyms) } : entry.kind === 'DOMAIN' ? { maxLength: form.maxLength ? Number(form.maxLength) : undefined, precision: form.precision ? Number(form.precision) : undefined, scale: form.scale ? Number(form.scale) : undefined, format: form.format || undefined, unit: form.unit || undefined, allowedValues: lines(form.allowedValues) } : { physicalName: form.physicalName, domainEntryId: form.domainEntryId, wordEntryIds: form.wordEntryIds };
    try { await adminWrite(`/dictionary-entries/${entry.id}/${entry.kind.toLowerCase()}`, 'PUT', session, body, resource.etag); onSaved(); } catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  return <><Modal open wide title={`${entry.name} · ${entry.kind === 'TERM' ? '용어 구성' : entry.kind === 'DOMAIN' ? '도메인 속성' : '단어 속성'}`} busy={busy} onClose={() => { if (!dirty || window.confirm('저장하지 않은 속성 변경을 버리시겠습니까?')) onClose(); }}>
    {error && <div className={styles.error} role="alert">{error}<Button variant="secondary" disabled={busy} onClick={() => { if (!dirty || window.confirm('입력 내용을 버리고 최신 속성을 불러오시겠습니까?')) { setResource(null); setRetry(value => value + 1); } }}>최신 속성 확인</Button></div>}
    {resource ? <form onSubmit={event => { event.preventDefault(); void save(); }}><div className={styles.formGrid}>
      {entry.kind === 'WORD' && <><label>영문명<Input maxLength={200} disabled={!editable || busy} value={form.englishName} onChange={event => change('englishName', event.target.value)} /></label><label>영문 약어<Input maxLength={100} disabled={!editable || busy} value={form.abbreviation} onChange={event => change('abbreviation', event.target.value)} /></label><label className={styles.full}>동의어 (한 줄에 하나, 최대 30개)<textarea className={styles.textarea} disabled={!editable || busy} value={form.synonyms} onChange={event => change('synonyms', event.target.value)} /></label></>}
      {entry.kind === 'DOMAIN' && <><label>최대 길이<Input type="number" min={1} max={1000000} disabled={!editable || busy} value={form.maxLength} onChange={event => change('maxLength', event.target.value)} /></label><label>정밀도<Input type="number" min={1} max={1000} disabled={!editable || busy} value={form.precision} onChange={event => change('precision', event.target.value)} /></label><label>소수 자릿수<Input type="number" min={0} max={1000} disabled={!editable || busy} value={form.scale} onChange={event => change('scale', event.target.value)} /></label><label>단위<Input maxLength={100} disabled={!editable || busy} value={form.unit} onChange={event => change('unit', event.target.value)} /></label><label className={styles.full}>표현 형식<Input maxLength={200} disabled={!editable || busy} value={form.format} onChange={event => change('format', event.target.value)} /></label><label className={styles.full}>허용값 (한 줄에 하나, 최대 1,000개)<textarea className={styles.textarea} disabled={!editable || busy} value={form.allowedValues} onChange={event => change('allowedValues', event.target.value)} /></label></>}
      {entry.kind === 'TERM' && <><label><span>물리명<RequiredMark /></span><Input required maxLength={200} disabled={!editable || busy} value={form.physicalName} onChange={event => change('physicalName', event.target.value)} /></label><div><p className={styles.help}>승인 도메인 (용어와 같은 자료형)<RequiredMark /></p><p>{names[form.domainEntryId] || form.domainEntryId || '선택하지 않음'}</p>{editable && <Button type="button" variant="secondary" disabled={busy} onClick={() => setPicker('DOMAIN')}>도메인 선택</Button>}</div><div className={styles.full}><h3>순서 있는 구성 단어<RequiredMark /></h3><p className={styles.help}>승인된 단어를 순서대로 선택합니다. 검토 요청 전 1~32개가 필요합니다.</p>{form.wordEntryIds.length ? <ol>{form.wordEntryIds.map((id, index) => <li key={`${id}-${index}`}><span>{names[id] || id}</span>{editable && <span className={styles.summary}><Button type="button" variant="secondary" disabled={busy || index === 0} onClick={() => setForm(current => { const next = [...current.wordEntryIds]; [next[index - 1], next[index]] = [next[index], next[index - 1]]; return { ...current, wordEntryIds: next }; })}>위로</Button><Button type="button" variant="secondary" disabled={busy} onClick={() => setForm(current => ({ ...current, wordEntryIds: current.wordEntryIds.filter((_, position) => position !== index) }))}>단어 제거</Button></span>}</li>)}</ol> : <p>구성 단어가 없습니다.</p>}{editable && <Button type="button" variant="secondary" disabled={busy || form.wordEntryIds.length >= 32} onClick={() => setPicker('WORD')}>단어 추가</Button>}</div></>}
    </div>{editable && <div className={styles.actions}><Button disabled={busy || !dirty || (entry.kind === 'TERM' && (!form.domainEntryId || !form.wordEntryIds.length)) || (entry.kind === 'WORD' && lines(form.synonyms).length > 30) || (entry.kind === 'DOMAIN' && lines(form.allowedValues).length > 1000)}>{busy ? '저장 중…' : '속성 저장'}</Button></div>}</form> : !error && <p role="status">사전 속성을 불러오고 있습니다.</p>}
  </Modal>{picker && <ApprovedEntryPicker kind={picker} organizationId={dictionary.organizationId} dataType={entry.dataType} onClose={() => setPicker(null)} onSelect={item => { setNames(current => ({ ...current, [item.id]: item.name })); setForm(current => picker === 'DOMAIN' ? { ...current, domainEntryId: item.id } : { ...current, wordEntryIds: [...current.wordEntryIds, item.id] }); setPicker(null); }} />}</>;
}

function ApprovedEntryPicker({ kind, organizationId, dataType, onClose, onSelect }: { kind: 'WORD' | 'DOMAIN'; organizationId?: string | null; dataType: string; onClose: () => void; onSelect: (entry: DictionaryEntry) => void }) {
  const [dictionaries, setDictionaries] = useState<Dictionary[] | null>(null);
  const [dictionaryId, setDictionaryId] = useState('');
  const [entries, setEntries] = useState<DictionaryEntry[] | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => { const controller = new AbortController(); adminGet<Dictionary[]>(`/dictionaries${organizationId ? `?organizationId=${encodeURIComponent(organizationId)}` : ''}`, controller.signal).then(({ data }) => setDictionaries(data)).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [organizationId, retry]);
  const selected = dictionaryId || dictionaries?.[0]?.id;
  useEffect(() => { if (!selected) return; const controller = new AbortController(); adminGet<DictionaryEntry[]>(`/dictionaries/${selected}/entries?kind=${kind}&page=${page}&size=20`, controller.signal).then(({ data }) => { setEntries(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [selected, kind, page, retry]);
  const approved = entries?.filter(item => item.state === 'APPROVED' && (kind === 'WORD' || item.dataType === dataType)) || [];
  return <Modal open wide title={kind === 'WORD' ? '승인 단어 선택' : '승인 도메인 선택'} onClose={onClose}>{error && <div className={styles.error} role="alert">{error}<Button variant="secondary" onClick={() => setRetry(value => value + 1)}>다시 시도</Button></div>}<label className={styles.reason}>사전 버전<select className={styles.select} value={selected || ''} onChange={event => { setEntries(null); setDictionaryId(event.target.value); setPage(0); }}><option value="" disabled>사전 선택</option>{dictionaries?.map(item => <option key={item.id} value={item.id}>{item.name} · {item.versionLabel}</option>)}</select></label>{approved.length ? <div className={styles.tableWrap}><table className={styles.table}><caption className="sr-only">연결 가능한 승인 항목</caption><thead><tr><th>명칭</th><th>코드</th><th>자료형</th><th>선택</th></tr></thead><tbody>{approved.map(item => <tr key={item.id}><td>{item.name}</td><td>{item.code}</td><td>{item.dataType}</td><td><Button variant="secondary" onClick={() => onSelect(item)}>선택</Button></td></tr>)}</tbody></table></div> : !error && <p className={styles.state} role={!dictionaries || (!!selected && !entries) ? 'status' : undefined}>{!dictionaries || (!!selected && !entries) ? '승인 항목을 불러오는 중입니다.' : !dictionaries.length ? '조회할 수 있는 사전 버전이 없습니다.' : '이 페이지에 연결할 수 있는 승인 항목이 없습니다.'}</p>}{entries && <div className={styles.pagination}><Button variant="secondary" disabled={page === 0} onClick={() => { setEntries(null); setPage(value => value - 1); }}>이전</Button><span>{page + 1}페이지</span><Button variant="secondary" disabled={entries.length < 20} onClick={() => { setEntries(null); setPage(value => value + 1); }}>다음</Button></div>}</Modal>;
}
