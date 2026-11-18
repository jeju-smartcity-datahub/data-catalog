import { useEffect, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type Dictionary, type FieldSet, type MetadataDraft, type MetadataOptions, type Resource } from '../../lib/admin';
import type { TermFieldUsagePage } from '../../lib/standard-api';
import styles from '../metadata/Metadata.module.css';
import detailStyles from './StandardsDetail.module.css';

const states: Record<string, string> = { DRAFT: '초안', IN_REVIEW: '검토 중', APPROVED: '승인', REJECTED: '반려' };
const visibility: Record<string, string> = { PUBLIC: '공개', ORGANIZATION: '기관 내부', PRIVATE: '비공개' };
type Usage = TermFieldUsagePage['items'][number];
type Prepared = { draft: MetadataDraft; resource: Resource<FieldSet> };
export function TermFieldUsages({ termId, termName, dictionary, session }: { termId: string; termName: string; dictionary: Dictionary; session: AdminSession }) {
  const [options, setOptions] = useState<MetadataOptions | null>(null);
  const [organization, setOrganization] = useState(dictionary.organizationId || session.organizationIds[0] || '');
  const [result, setResult] = useState<TermFieldUsagePage | null>(null);
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const [error, setError] = useState('');
  const [optionsError, setOptionsError] = useState('');
  const [notice, setNotice] = useState('');
  const [target, setTarget] = useState<Usage | null>(null);
  const [prepared, setPrepared] = useState<Prepared | null>(null);
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const canEdit = hasAuthority(session, 'ORG_EDITOR');
  useEffect(() => { const controller = new AbortController(); adminGet<MetadataOptions>('/metadata-options', controller.signal).then(({ data }) => { setOptions(data); setOptionsError(''); setOrganization(current => current || data.organizations[0]?.id || ''); }).catch(error => { if (!controller.signal.aborted) setOptionsError(errorMessage(error)); }); return () => controller.abort(); }, [retry]);
  useEffect(() => {
    if (!organization) return;
    const controller = new AbortController();
    adminGet<TermFieldUsagePage>(`/dictionary-entries/${termId}/field-usages?organizationId=${encodeURIComponent(organization)}&page=${page}&size=20`, controller.signal).then(({ data }) => { setResult(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [termId, organization, page, retry]);
  function refresh() { setResult(null); setError(''); setOptionsError(''); setRetry(value => value + 1); }
  async function prepare(item: Usage) {
    if (running.current) return; running.current = true; setBusy(true); setTarget(item); setPrepared(null); setActionError('');
    try {
      const [draft, resource] = await Promise.all([adminGet<MetadataDraft>(`/datasets/${item.datasetId}/draft`), adminGet<FieldSet>(`/datasets/${item.datasetId}/draft/fields`)]);
      if (draft.data.state !== 'DRAFT' || draft.data.ownerOrganizationId !== organization) { setActionError('이 기관의 편집 가능한 초안이 아닙니다. 승인·게시된 버전은 이 화면에서 변경할 수 없습니다.'); return; }
      if (!resource.etag || resource.etag !== draft.etag || resource.data.revisionId !== draft.data.revisionId || resource.data.lockVersion !== draft.data.lockVersion) { setActionError('조회 중 관리 버전이 변경되었습니다. 최신 상태를 다시 확인해 주세요.'); return; }
      const current = resource.data.fields.find(field => field.fieldPath === item.fieldPath);
      if (!current || current.approvedTermId !== termId) { setActionError('이 필드의 용어 연결이 이미 변경되었습니다. 연결 목록을 다시 조회해 주세요.'); return; }
      setPrepared({ draft: draft.data, resource });
    } catch (error) { setActionError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  async function unlink() {
    if (running.current || !prepared || !target) return; running.current = true; setBusy(true); setActionError('');
    try {
      const fields = prepared.resource.data.fields.map(field => field.fieldPath === target.fieldPath && field.approvedTermId === termId ? { ...field, approvedTermId: null, mappingEvidence: null } : field);
      await adminWrite(`/datasets/${target.datasetId}/draft/fields`, 'PUT', session, { fields }, prepared.resource.etag);
      setTarget(null); setPrepared(null); setNotice(`초안의 ${target.displayName} 필드에서 ${termName} 연결을 해제했습니다. 게시본은 변경되지 않았습니다.`); refresh();
    } catch (error) { setActionError(errorMessage(error)); setPrepared(null); } finally { running.current = false; setBusy(false); }
  }
  const loadError = optionsError || error;
  return <section aria-label="연결 데이터셋 필드"><p className={styles.help}>선택한 기관의 최신 관리 버전에 연결된 필드입니다. 분류·제공기관·공개범위는 해당 관리 버전의 정보이며, 최신 게시 이력은 별도로 표시합니다. 초안의 연결 해제는 게시본을 변경하지 않습니다.</p>
    {dictionary.scope === 'PUBLIC' ? <label className={styles.reason}>조회 기관<select className={styles.select} value={organization} onChange={event => { setOrganization(event.target.value); setResult(null); setError(''); setNotice(''); setPage(0); }}><option value="" disabled>기관 선택</option>{options?.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label> : options && <p className={styles.help}>조회 기관: {options.organizations.find(item => item.id === organization)?.name || '현재 기관'}</p>}
    {notice && <p role="status" className={styles.message}>{notice}</p>}
    {loadError && <div className={styles.error} role="alert">{loadError}<Button variant="secondary" onClick={refresh}>연결 목록 다시 조회</Button></div>}
    {!loadError && (!options || organization && !result) && <p role="status">연결된 필드를 불러오는 중입니다.</p>}
    {!loadError && options && !organization && <p className={styles.state}>조회할 수 있는 기관이 없습니다.</p>}
    {result && options && !loadError && <><h4 className={detailStyles.usageHeading}>연결 필드 목록 · 현재 페이지 {result.items.length}개</h4><p className={`${styles.help} ${detailStyles.mobileHint}`}>표를 좌우로 밀어 나머지 열을 확인하세요. 키보드는 표 영역에서 좌우 화살표를 사용할 수 있습니다.</p>{result.items.length ? <section className={detailStyles.usageScroll} aria-label="연결 필드 표 가로 스크롤" tabIndex={0}><table className={`${styles.table} ${detailStyles.usageTable}`}><caption className="sr-only">최신 관리 버전의 연결 데이터셋·필드 및 별도의 게시 이력</caption><thead><tr><th scope="col">데이터셋 / 필드</th><th scope="col">분류</th><th scope="col">제공기관</th><th scope="col">공개범위</th><th scope="col">관리 버전 / 상태</th><th scope="col">최신 게시 이력</th><th scope="col">관리 수정일</th><th scope="col">적용</th></tr></thead><tbody>{result.items.map(item => <tr key={`${item.datasetId}-${item.fieldPath}`}><td><strong>{item.title}</strong><small>{item.displayName} · {item.dataType}</small><code>{item.fieldPath}</code></td><td>{item.primaryCategoryName || '미분류'}</td><td><Badge>{item.providerOrganizationName}</Badge></td><td><Badge>{visibility[item.visibility] || item.visibility}</Badge></td><td>r{item.revisionNo}<small>{states[item.state] || item.state}</small></td><td>{item.latestPublication ? <><Badge>{item.latestPublication.current ? '게시 중' : item.latestPublication.state === 'WITHDRAWN' ? '철회됨' : '과거 게시'}</Badge><small>v{item.latestPublication.publicationNo} · {item.latestPublication.matchesManagementRevision ? '현재 관리 버전과 동일' : '별도 관리 버전'}</small></> : '게시 이력 없음'}</td><td><time dateTime={item.revisionUpdatedAt}>{new Date(item.revisionUpdatedAt).toLocaleString('ko-KR', { timeZone: 'UTC' })} UTC</time></td><td><Button variant="secondary" disabled={!canEdit || item.state !== 'DRAFT'} aria-label={`${item.title} ${item.displayName} 연결 해제`} onClick={() => void prepare(item)}>해제</Button>{(!canEdit || item.state !== 'DRAFT') && <small>{!canEdit ? '편집 권한 필요' : '초안만 해제 가능'}</small>}</td></tr>)}</tbody></table></section> : <p className={styles.state}>이 기관의 최신 관리 버전에 연결된 필드가 없습니다.</p>}<div className={styles.pagination}><Button variant="secondary" disabled={page === 0} onClick={() => { setResult(null); setPage(value => value - 1); }}>이전 연결</Button><span>{page + 1}페이지</span><Button variant="secondary" disabled={!result.hasNext} onClick={() => { setResult(null); setPage(value => value + 1); }}>다음 연결</Button></div></>}
    {target && <Modal open title="용어 연결 해제" busy={busy} onClose={() => { if (!busy) { setTarget(null); setPrepared(null); } }} footerMode="replace" footer={<><Button variant="secondary" disabled={busy} onClick={() => { setTarget(null); setPrepared(null); }}>취소</Button><Button variant="danger" disabled={busy || !prepared} onClick={() => void unlink()}>초안 연결 해제</Button></>}><p>{prepared?.draft.title || target.title}의 <strong>{target.displayName}</strong> (<code>{target.fieldPath}</code>) 필드에서 <strong>{termName}</strong> 연결을 해제하시겠습니까?</p><p className={styles.help}>확인한 초안의 이 연결과 근거만 제거합니다. 다른 필드와 게시본은 유지되며, 확인 이후 버전이 달라지면 저장하지 않습니다.</p>{busy && <p role="status">초안 상태를 확인하거나 변경 요청을 처리하고 있습니다.</p>}{actionError && <div className={styles.error} role="alert">{actionError}<Button variant="secondary" disabled={busy} onClick={() => void prepare(target)}>최신 상태 다시 확인</Button></div>}</Modal>}
  </section>;
}
