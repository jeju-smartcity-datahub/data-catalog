import { createRequestId } from '../../lib/requestId';
import { useEffect, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { AdminApiError, adminGet, adminUpload, adminWrite, errorMessage, type AdminSession, type AnalysisEvidence, type DefinitionImport, type MetadataDraft, type MetadataOptions, type MetadataPage, type Resource } from '../../lib/admin';
import type { DefinitionFailure } from '../../lib/definitionDiagnostics';
import { DefinitionConfidence, DefinitionDiagnostics } from './DefinitionAssessment';
import clearIcon from './assets/clear.svg';
import styles from './Metadata.module.css';
import layout from './MetadataPresentation.module.css';

const warningLabels: Record<string, string> = {
  FIELD_DESCRIPTION_REQUIRES_REVIEW: '설명이 비어 있는 필드는 담당자가 내용을 보완해야 합니다.',
  IMPORTED_STANDARD_MAPPING_REQUIRES_REVIEW: '가져온 표준용어 연결은 승인 상태와 적용 근거를 다시 확인하세요.',
};

export function DefinitionImportDialog({ session, options, onClose, onApplied }: { session: AdminSession; options: MetadataOptions; onClose: () => void; onApplied: (datasetId: string) => void }) {
  const [organizationId, setOrganizationId] = useState(options.organizations[0]?.id || '');
  const [datasetId, setDatasetId] = useState('');
  const [datasets, setDatasets] = useState<MetadataPage | null>(null);
  const [target, setTarget] = useState<Resource<MetadataDraft> | null>(null);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<DefinitionImport | null>(null);
  const [failure, setFailure] = useState<DefinitionFailure | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [evidence, setEvidence] = useState<AnalysisEvidence[] | null>(null);
  const [showEvidence, setShowEvidence] = useState(false);
  const [evidenceError, setEvidenceError] = useState('');
  const [targetError, setTargetError] = useState('');
  const [datasetsError, setDatasetsError] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const selectAll = useRef<HTMLInputElement>(null);
  const running = useRef(false);
  const keys = useRef(new Map<string, string>());
  const [reload, setReload] = useState(0);
  useEffect(() => {
    if (!organizationId) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ organizationId, state: 'DRAFT', q: query, page: String(page), size: '20' });
    adminGet<MetadataPage>(`/datasets?${params}`, controller.signal).then(({ data }) => { setDatasets(data); setDatasetsError(''); }).catch(error => { if (!controller.signal.aborted) setDatasetsError(errorMessage(error)); });
    return () => controller.abort();
  }, [organizationId, query, page, reload]);
  useEffect(() => { if (!datasetId) return; const controller = new AbortController(); adminGet<MetadataDraft>(`/datasets/${datasetId}/draft`, controller.signal).then(data => { setTarget(data); setTargetError(''); }).catch(error => { if (!controller.signal.aborted) setTargetError(errorMessage(error)); }); return () => controller.abort(); }, [datasetId, reload]);
  useEffect(() => { if (selectAll.current) selectAll.current.indeterminate = !!result && selected.length > 0 && selected.length < result.fields.length; }, [selected, result]);
  function close() { if (!busy && (!file || window.confirm('정의서의 적용을 마치지 않고 닫으시겠습니까?'))) onClose(); }
  function refreshTarget() { setTarget(null); setTargetError(''); setConfirmed(false); setReload(value => value + 1); }
  function chooseFile(next: File | undefined) {
    setError(''); setResult(null); setFailure(null); setSelected([]); setConfirmed(false); setEvidence(null); setEvidenceError(''); setShowEvidence(false);
    if (!next) { setFile(null); return; }
    if (next.size < 1 || next.size > 1024 * 1024 || !next.name.toLowerCase().endsWith('.json')) { setFile(null); setError('1MiB 이하의 UTF-8 JSON 정의서 파일을 선택해 주세요.'); return; }
    setFile(next);
  }
  async function upload() {
    if (!file || !target || running.current) return; running.current = true; setBusy(true); setError(''); setFailure(null);
    try { const params = new URLSearchParams({ organizationId, datasetId }); const response = await adminUpload<DefinitionImport>(`/definition-imports?${params}`, file, session); setResult(response.data); setSelected(response.data.fields.map(item => item.id)); setConfirmed(false); }
    catch (error) { setError(errorMessage(error)); if (error instanceof AdminApiError) setFailure(error.definitionFailure); } finally { running.current = false; setBusy(false); }
  }
  async function viewEvidence() {
    if (!result || running.current) return; running.current = true; setBusy(true); setShowEvidence(true); setEvidence(null); setEvidenceError('');
    try { const response = await adminGet<AnalysisEvidence[]>(`/definition-imports/${result.id}/evidence`); setEvidence(response.data); } catch (error) { setEvidenceError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  async function apply() {
    if (!result || !target?.etag || !confirmed || !selected.length || running.current) return; running.current = true; setBusy(true); setError('');
    const path = `/definition-imports/${result.id}/applications`; const body = { datasetId, selectedFieldIds: selected }; const fingerprint = JSON.stringify([path, body, target.etag]); let key = keys.current.get(fingerprint); if (!key) { key = createRequestId(); keys.current.set(fingerprint, key); }
    try { await adminWrite(path, 'POST', session, body, target.etag, key); onApplied(datasetId); } catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  return <><Modal open size="details" title="정의서 등록" className={layout.dialog} busy={busy} onClose={close} footerMode="replace" footer={<div className={layout.footer}><Button variant="secondary" disabled={busy} onClick={close}>취소</Button>{result ? <Button disabled={busy || !confirmed || !selected.length || !target?.etag || target.data.state !== 'DRAFT'} onClick={() => void apply()}>{busy && !showEvidence ? '적용 중…' : '선택한 필드 초안에 적용'}</Button> : <Button disabled={busy || !file || !target || target.data.state !== 'DRAFT'} onClick={() => void upload()}>{busy ? '검증 중…' : '파일 검증·필드 추출'}</Button>}</div>}>
    <p className={styles.help}>JSON 정의서에서 필드를 추출하고, 확인한 항목을 선택한 메타데이터 초안에 적용합니다. 원본 레코드를 추론하거나 자동으로 승인·게시하지 않습니다.</p>
    {error && <div className={styles.error} role="alert">{error}{datasetId && <Button variant="secondary" disabled={busy} onClick={refreshTarget}>적용 대상 최신 상태 확인</Button>}</div>}
    {failure && <><DefinitionDiagnostics diagnostics={failure.diagnostics} />{failure.analysisRunId && <p className={styles.help}>분석 이력 식별자: {failure.analysisRunId}. 분석 이력에서도 항목별 사유를 다시 확인할 수 있습니다.</p>}</>}
    <div className={layout.uploadGrid}>
      <label><span>관리 기관<RequiredMark /></span><select className={styles.select} value={organizationId} disabled={busy || !!result} onChange={event => { setOrganizationId(event.target.value); setDatasetId(''); setTarget(null); setDatasets(null); setPage(0); }}>{options.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label><span>대상 초안<RequiredMark /></span><select className={styles.select} required value={datasetId} disabled={busy || !!result || !datasets} onChange={event => { setDatasetId(event.target.value); setTarget(null); setTargetError(''); }}><option value="">초안을 선택하세요</option>{datasets?.items.map(item => <option key={item.id} value={item.id}>{item.title} (v{item.revisionNo})</option>)}</select></label>
    </div>
    {!datasets && !datasetsError && organizationId && <p role="status" className={layout.inlineStatus}>적용할 초안 목록을 불러오는 중입니다.</p>}
    {datasetsError && <div role="alert" className={styles.error}>{datasetsError}<Button variant="secondary" disabled={busy} onClick={() => { setDatasetsError(''); setReload(value => value + 1); }}>초안 목록 다시 불러오기</Button></div>}
    {datasetId && !target && !targetError && <p role="status" className={layout.inlineStatus}>대상 초안의 최신 버전을 확인하고 있습니다.</p>}
    {targetError && <div role="alert" className={styles.error}>{targetError}<Button variant="secondary" disabled={busy} onClick={refreshTarget}>적용 대상 다시 확인</Button></div>}
    {target && target.data.state !== 'DRAFT' && <p className={styles.error} role="alert">대상이 더 이상 편집 가능한 초안이 아닙니다. 등록 창을 닫고 새 수정 초안을 만든 후 다시 선택해 주세요.</p>}
    {!result && <details className={layout.importSearch}><summary>대상 초안 검색·페이지</summary><form onSubmit={event => { event.preventDefault(); setDatasets(null); setDatasetsError(''); setDatasetId(''); setTarget(null); setPage(0); setQuery(search.trim()); setReload(value => value + 1); }}><label>대상 초안 찾기<Input maxLength={200} value={search} disabled={busy} onChange={event => setSearch(event.target.value)} /></label><Button disabled={busy}>검색</Button>{datasets && <><Button type="button" variant="secondary" disabled={busy || page === 0} onClick={() => { setDatasets(null); setDatasetId(''); setTarget(null); setPage(value => value - 1); }}>이전 초안</Button><Button type="button" variant="secondary" disabled={busy || page + 1 >= datasets.page.totalPages} onClick={() => { setDatasets(null); setDatasetId(''); setTarget(null); setPage(value => value + 1); }}>다음 초안</Button></>}</form></details>}
    {datasets?.items.length === 0 && <p className={styles.help} role="status">해당 기관에 편집 가능한 초안이 없습니다. 메타데이터 목록의 초안 등록에서 기본정보를 먼저 등록해 주세요.</p>}
    {!result && <div className={styles.reason}><span>정의서 파일<RequiredMark /></span><div className={layout.fileChoice}><input ref={fileInput} className="sr-only" tabIndex={-1} aria-label="정의서 파일" type="file" accept=".json,application/json" disabled={busy} onChange={event => chooseFile(event.target.files?.[0])} /><Button variant="secondary" disabled={busy} onClick={() => fileInput.current?.click()}>파일 선택</Button><div className={layout.fileName}><span>{file?.name || '선택된 파일이 없습니다.'}</span>{file && <button type="button" className={layout.clearButton} aria-label="정의서 파일 선택 해제" disabled={busy} onClick={() => { chooseFile(undefined); if (fileInput.current) fileInput.current.value = ''; }}><img src={clearIcon} alt="" /></button>}</div></div><span>UTF-8 JSON · 최대 1MiB · 최대 500필드 · <code>{'{"fields": [필드 정의]}'}</code> 형식. 기존 정의서 JSON 내보내기 형식을 사용할 수 있습니다.</span></div>}
    {result && <><div className={layout.importResult}><section aria-labelledby="definition-preview"><h3 id="definition-preview">검증한 필드 미리보기</h3><section className={layout.preview} tabIndex={0} aria-label="서버에서 검증한 필드 정의 JSON"><pre>{JSON.stringify({ fields: result.fields.map(item => item.field) }, null, 2)}</pre></section><p className={styles.help}>서버가 검증한 필드 정의입니다. 데이터 레코드나 원본 파일 내용을 직접 표시하지 않습니다.</p></section><section aria-labelledby="definition-result"><h3 id="definition-result">변환 결과 확인</h3><dl className={layout.resultDefinition}><div><dt>대상 데이터셋</dt><dd>{target?.data.title}</dd></div><div><dt>선택 파일</dt><dd>{file?.name}</dd></div><div><dt>추출 필드</dt><dd><Badge tone="info">{result.fieldCount}개 필드</Badge></dd></div><div><dt>원본 크기</dt><dd>{result.sourceSize.toLocaleString('ko-KR')} bytes</dd></div><div><dt>파서</dt><dd>{result.parserVersion}</dd></div></dl><div className={styles.actions}><Button variant="secondary" disabled={busy} onClick={() => void viewEvidence()}>추출 근거 보기</Button></div></section></div>
      <DefinitionDiagnostics diagnostics={result.diagnostics} />
      {result.warnings.length > 0 && <div className={styles.message}><strong>담당자 검토 필요</strong><ul>{result.warnings.map((warning, index) => <li key={index}>{warningLabels[warning] || '추출 결과의 적용 조건을 확인하세요.'}</li>)}</ul></div>}
      <h3 className={styles.subheading}>적용할 필드 선택<RequiredMark /></h3><section className={`${styles.tableWrap} ${layout.tableScroll}`} tabIndex={0} aria-label="추출한 필드 선택 표"><table className={styles.table}><caption className="sr-only">추출한 필드 선택</caption><thead><tr><th scope="col"><label className={layout.selection}><input ref={selectAll} type="checkbox" checked={result.fields.length > 0 && selected.length === result.fields.length} disabled={busy || !result.fields.length} onChange={event => { setConfirmed(false); setSelected(event.target.checked ? result.fields.map(item => item.id) : []); }} />전체 선택</label></th><th scope="col">필드명 · 표시이름</th><th scope="col">자료형</th><th scope="col">설명</th><th scope="col">추출 위치</th><th scope="col">규칙 확인 점수</th></tr></thead><tbody>{result.fields.map(item => <tr key={item.id}><td><input type="checkbox" aria-label={`${item.field.physicalName} 선택`} checked={selected.includes(item.id)} disabled={busy} onChange={event => { setConfirmed(false); setSelected(current => event.target.checked ? [...current, item.id] : current.filter(id => id !== item.id)); }} /></td><td>{item.field.physicalName}<br />{item.field.displayName}</td><td>{item.field.dataType}</td><td>{item.field.description}</td><td>{item.sourcePointer}</td><td><DefinitionConfidence confidence={item.confidence} /></td></tr>)}</tbody></table></section>
      <p className={styles.help} role="status">선택된 {selected.length}개 필드로 대상 초안의 필드 전체를 교체합니다. 기존 필드와 표준용어 연결을 유지하려면 적용 전에 내용을 확인하세요.</p><label className={layout.selection}><input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} /><span> 선택 필드로 초안의 기존 필드 전체를 교체하는 것을 확인했습니다.<RequiredMark /></span></label>
    </>}
  </Modal>{showEvidence && <Modal open size="evidence" title="정의서 추출 근거" busy={busy} onClose={() => setShowEvidence(false)}><h3 className={layout.evidenceTitle}>{target?.data.title}</h3>{evidenceError ? <div role="alert" className={styles.error}>{evidenceError}<Button variant="secondary" onClick={() => void viewEvidence()}>추출 근거 다시 불러오기</Button></div> : !evidence ? <p role="status">추출 근거를 불러오는 중입니다.</p> : <><p className={layout.evidenceCount}>총 <strong>{evidence.length.toLocaleString('ko-KR')}</strong>개</p>{evidence.length === 0 ? <p role="status" className={styles.state}>기록된 추출 근거가 없습니다.</p> : <section className={`${styles.tableWrap} ${layout.tableScroll}`} tabIndex={0} aria-label="필드별 실제 파서 추출 근거 표"><table className={styles.table}><caption className="sr-only">필드별 실제 파서 추출 근거</caption><thead><tr><th scope="col">물리명 · 필드 경로</th><th scope="col">추출 근거</th><th scope="col">추출 항목</th><th scope="col">규칙 확인 점수</th></tr></thead><tbody>{evidence.map((item, index) => <tr key={index}><td>{item.field.physicalName}<p className={styles.help}>{item.fieldPath}</p></td><td><code>{item.sourcePointer}</code><p className={styles.help}>{item.explanation}</p></td><td>{item.field.displayName} · {item.field.dataType}<p className={styles.help}>{item.field.description || '설명 없음'}</p></td><td><DefinitionConfidence confidence={item.confidence} /></td></tr>)}</tbody></table></section>}</>}<p className={layout.sourceHash}>원본 해시: {result?.sourceHash}</p></Modal>}</>;
}
