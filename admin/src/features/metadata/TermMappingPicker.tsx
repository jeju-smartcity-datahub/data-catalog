import { useEffect, useId, useState } from 'react';
import { StandardsDetailTabs } from '../governance/StandardsDetailTabs';
import { Modal } from '../../components/Modal';
import { Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, errorMessage, type FieldDefinition } from '../../lib/admin';
import type { TermCandidatePage } from '../../lib/standard-api';
import styles from './Metadata.module.css';
import pickerStyles from './TermMappingPicker.module.css';

export function TermMappingPicker({ datasetId, organizationId, field, onClose, onApply }: { datasetId: string; organizationId: string; field: FieldDefinition; onClose: () => void; onApply: (id: string, evidence: string) => void }) {
  const tabsId = useId();
  const [mode, setMode] = useState<'search' | 'suggest'>('search');
  const [result, setResult] = useState<TermCandidatePage | null>(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [page, setPage] = useState(0);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const savedDataset = /^[0-9a-f-]{36}$/i.test(datasetId);
  useEffect(() => {
    const controller = new AbortController();
    const path = mode === 'search' ? `/dictionary-terms?organizationId=${encodeURIComponent(organizationId)}&dataType=${encodeURIComponent(field.dataType)}&q=${encodeURIComponent(query)}&page=${page}&size=20` : `/datasets/${datasetId}/draft/term-suggestions?fieldPath=${encodeURIComponent(field.fieldPath)}&page=${page}&size=20`;
    adminGet<TermCandidatePage>(path, controller.signal).then(({ data }) => { setResult(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [datasetId, organizationId, field.fieldPath, field.dataType, mode, query, page, retry]);
  const selected = result?.items.find(item => item.id === selectedId);
  const basis = result?.basis;
  const changed = basis && (basis.displayName !== field.displayName || basis.physicalName !== field.physicalName || basis.dataType !== field.dataType);
  function clear() { setResult(null); setSelectedId(''); setReason(''); setError(''); }
  function changeMode(next: 'search' | 'suggest') { if (next === mode) return; clear(); setPage(0); setMode(next); }
  return <Modal open title="데이터셋 필드 연결" wide onClose={onClose}><p className={styles.help}>승인된 같은 자료형의 용어를 {field.displayName || field.physicalName || '선택한 필드'}에 연결합니다. 선택 후 연결 적용을 눌러도 서버에 저장되지 않으며, 필드정보 저장 시 권한·승인 상태·자료형을 다시 검증합니다.</p>
    <div className={pickerStyles.target}><strong>연결 대상 필드</strong><span>{field.displayName} · <code>{field.fieldPath}</code> · {field.dataType}</span><span>{field.approvedTermId ? '기존 용어 연결 있음 · 적용 시 선택한 용어로 변경' : '연결 안 됨'}</span></div>
    <StandardsDetailTabs id={tabsId} label="연결 방식" value={mode} tabs={[{ value: 'search', label: '기본 연결' }, { value: 'suggest', label: '추천 연결', disabled: !savedDataset }]} onChange={value => changeMode(value as 'search' | 'suggest')} />
    <div role="tabpanel" id={`${tabsId}-panel-${mode}`} aria-labelledby={`${tabsId}-tab-${mode}`} tabIndex={0}>
    <div hidden id={`${tabsId}-panel-${mode === 'search' ? 'suggest' : 'search'}`} role="tabpanel" aria-labelledby={`${tabsId}-tab-${mode === 'search' ? 'suggest' : 'search'}`} />
    {mode === 'search' ? <form className={styles.toolbar} onSubmit={event => { event.preventDefault(); clear(); setPage(0); setQuery(search.trim()); setRetry(value => value + 1); }}><label className={styles.search}>승인 용어 검색<Input maxLength={200} value={search} onChange={event => setSearch(event.target.value)} placeholder="용어명, 코드 또는 물리명" /></label><Button>용어 검색</Button></form> : <p className={styles.help}>저장된 필드와 승인 용어의 표시명이 일치하면 100점, 코드·물리명이 일치하면 90점입니다. 동점은 용어명·코드·ID 순으로 정렬합니다. 점수는 규칙 적합도이며 의미 유사도·정확도·확률이 아닙니다. 미저장 새 필드는 기본 연결에서 검색하세요.</p>}
    {basis && <p className={pickerStyles.basis}>저장 기준: {basis.fieldPath} · {basis.displayName} · {basis.physicalName} · {basis.dataType} · 관리 버전값 {basis.lockVersion}</p>}
    {changed && <p className={pickerStyles.warning} role="status">현재 입력과 저장 기준이 다릅니다. 미저장 입력은 유지됩니다. 현재 입력 기준으로 찾으려면 기본 연결을 사용하세요.</p>}
    {error && <div className={styles.error} role="alert">{error}{mode === 'suggest' && <p>저장되지 않은 필드라면 기본 연결에서 직접 검색할 수 있습니다.</p>}<Button variant="secondary" onClick={() => { clear(); setRetry(value => value + 1); }}>다시 불러오기</Button></div>}
    {!error && !result && <p role="status">승인 용어를 불러오는 중입니다.</p>}
    {result && !error && <>{result.items.length ? <div className={styles.tableWrap}><table className={`${styles.table} ${pickerStyles.candidateTable}`}><caption className="sr-only">승인 용어 후보와 연결 근거</caption><thead><tr><th>용어</th><th>사전 · 버전</th><th>자료형</th><th>근거</th><th>선택</th></tr></thead><tbody>{result.items.map(item => <tr key={item.id}><td data-label="용어"><div>{item.name}<br /><code>{item.code}</code></div></td><td data-label="사전 · 버전"><div>{item.dictionaryName}<br />{item.versionLabel} · {item.scope === 'PUBLIC' ? '공공' : '기관'}</div></td><td data-label="자료형">{item.dataType}</td><td data-label="근거" className={pickerStyles.evidence}>{item.recommendationRank != null && <strong>{item.recommendationRank}순위 · {item.recommendationScore}점<br /></strong>}{item.evidence}{item.confidenceLabel && <p>{item.confidenceLabel}</p>}</td><td data-label="선택"><Button variant="secondary" disabled={item.dataType !== field.dataType} aria-pressed={selectedId === item.id} onClick={() => { setSelectedId(item.id); setReason(item.evidence); }}>{selectedId === item.id ? '선택됨' : '선택'}</Button></td></tr>)}</tbody></table></div> : <p className={styles.state}>{mode === 'suggest' ? '저장된 필드와 이름·식별자가 일치하는 승인 용어가 없습니다. 기본 연결에서 직접 검색할 수 있습니다.' : '조건과 자료형이 일치하는 승인 용어가 없습니다.'}</p>}
    <div className={styles.pagination}><Button variant="secondary" disabled={page === 0} onClick={() => { clear(); setPage(value => value - 1); }}>이전 용어</Button><span>{page + 1}페이지</span><Button variant="secondary" disabled={!result.hasNext} onClick={() => { clear(); setPage(value => value + 1); }}>다음 용어</Button></div></>}
    <form onSubmit={event => { event.preventDefault(); if (selected && selected.dataType === field.dataType && reason.trim()) onApply(selected.id, reason.trim()); }}>
      {selected && <div className={styles.readOnly}><strong>{selected.name}</strong><p>{selected.definition}</p></div>}
      <label className={styles.reason}><span>연결 근거<RequiredMark /></span><textarea className={styles.textarea} required maxLength={2000} value={reason} onChange={event => setReason(event.target.value)} placeholder="선택한 후보의 근거를 확인하고 필요한 설명을 보완하세요." /></label>
      <div className={styles.actions}><Button disabled={!selected || selected.dataType !== field.dataType || !reason.trim()}>연결 적용</Button></div>
    </form>
    </div>
  </Modal>;
}

