import { createRequestId } from '../../lib/requestId';
import { adminUrl } from '../../lib/paths';
import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import styles from '../metadata/Metadata.module.css';
import pageStyles from './Governance.module.css';
import classificationStyles from './Classification.module.css';
import { DiscoveryTaxonomies } from './DiscoveryTaxonomies';

type Category = components['schemas']['CategoryDetails'];
type CategoryDatasetPage = components['schemas']['CategoryDatasetPage'];

export function Classifications({ session }: { session: AdminSession }) {
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [active, setActive] = useState('');
  const [edit, setEdit] = useState<{ id?: string; parentId: string | null } | null>(null);
  const [usage, setUsage] = useState<Category | null>(null);
  const [notice, setNotice] = useState('');
  const noticeRef = useRef<HTMLParagraphElement>(null);
  const canEdit = hasAuthority(session, 'PLATFORM_ADMIN');
  useEffect(() => { const controller = new AbortController(); adminGet<Category[]>('/categories', controller.signal).then(({ data }) => { setCategories(data); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [refresh]);
  useEffect(() => { if (notice) noticeRef.current?.focus(); }, [notice]);
  const roots = categories?.filter(item => !item.parentId) || [];
  const current = roots.find(item => item.id === selected) || roots[0];
  const topics = categories?.filter(item => item.parentId === current?.id && (!search || `${item.name} ${item.code}`.toLowerCase().includes(search.toLowerCase())) && (!active || String(item.active) === active)) || [];
  return <>
    <div className={styles.toolbar}><label>상태<select className={styles.select} value={active} onChange={event => setActive(event.target.value)}><option value="">전체 상태</option><option value="true">활성</option><option value="false">비활성</option></select></label><label className={styles.search}>주제 검색<Input value={search} maxLength={200} onChange={event => setSearch(event.target.value)} placeholder="선택한 분야의 주제명 또는 코드" /></label><Button variant="secondary" onClick={() => { setCategories(null); setRefresh(value => value + 1); }}>새로고침</Button></div>
    {notice && <p ref={noticeRef} tabIndex={-1} className={styles.message} role="status">{notice}</p>}{error && <p className={styles.error} role="alert">{error}</p>}
    {categories ? <div className={`${pageStyles.columns} ${classificationStyles.columns}`}>
      <section className={styles.panel}><div className={styles.panelHeading}><h2>분야 {roots.length}</h2>{canEdit && <Button className={pageStyles.small} onClick={() => setEdit({ parentId: null })}>분야 추가</Button>}</div>{roots.length ? <ul className={pageStyles.tree}>{roots.map(item => <li key={item.id}><button type="button" aria-pressed={current?.id === item.id} onClick={() => setSelected(item.id)}>{item.name}<span>{item.active ? '활성' : '비활성'} · {categories.filter(child => child.parentId === item.id).length}개 주제</span></button></li>)}</ul> : <p>등록된 분야가 없습니다.</p>}</section>
      {current && <div><section className={styles.panel}><div className={styles.panelHeading}><h2>{current.name} <Badge>{current.active ? '활성' : '비활성'}</Badge></h2><div className={pageStyles.actions}><Button variant="secondary" onClick={() => setUsage(current)}>분야 사용 현황</Button>{canEdit && <Button variant="secondary" onClick={() => setEdit({ id: current.id, parentId: null })}>분야 수정</Button>}</div></div><p>참조 합계 {current.usageCount.toLocaleString('ko-KR')}건</p><p className={styles.help}>조회 가능한 메타데이터 버전의 직접 참조와 공통 하위 주제를 합한 수입니다.</p></section><section className={styles.panel}><div className={styles.panelHeading}><h2>하위 주제 {topics.length}</h2>{canEdit && <Button disabled={!current.active} onClick={() => setEdit({ parentId: current.id })}>주제 추가</Button>}</div>{topics.length ? <section className={styles.tableWrap} aria-label="하위 주제 표" tabIndex={0}><table className={styles.table}><caption className="sr-only">분야에 속한 주제 목록</caption><thead><tr><th scope="col">주제명</th><th scope="col">코드</th><th scope="col">표시 순서</th><th scope="col">상태</th><th scope="col">참조 수</th>{canEdit && <th scope="col">관리</th>}</tr></thead><tbody>{topics.map(item => <tr key={item.id}><td><button type="button" className={styles.rowLink} onClick={() => setUsage(item)} aria-label={`${item.name} 사용 현황`}>{item.name}</button></td><td>{item.code}</td><td>{item.ordinal}</td><td><Badge>{item.active ? '활성' : '비활성'}</Badge></td><td>{item.usageCount.toLocaleString('ko-KR')}</td>{canEdit && <td><Button className={pageStyles.small} variant="secondary" onClick={() => setEdit({ id: item.id, parentId: item.parentId || null })} aria-label={`${item.name} 수정`}>수정</Button></td>}</tr>)}</tbody></table></section> : <p className={styles.state}>조건에 맞는 하위 주제가 없습니다.</p>}</section></div>}
    </div> : !error && <p className={styles.state} role="status">분류를 불러오는 중입니다.</p>}
    {edit && <CategoryEditor session={session} target={edit} parentName={roots.find(item => item.id === edit.parentId)?.name} onClose={() => setEdit(null)} onSaved={message => { setEdit(null); setNotice(message); setCategories(null); setRefresh(value => value + 1); }} />}
    {usage && <CategoryUsage target={usage} parentName={roots.find(item => item.id === usage.parentId)?.name} onClose={() => setUsage(null)} />}
    {canEdit && <DiscoveryTaxonomies session={session} />}
  </>;
}

function CategoryEditor({ session, target, parentName, onClose, onSaved }: { session: AdminSession; target: { id?: string; parentId: string | null }; parentName?: string; onClose: () => void; onSaved: (message: string) => void }) {
  const [resource, setResource] = useState<Resource<Category> | null>(null);
  const [form, setForm] = useState({ code: '', name: '', description: '', active: true, ordinal: '0' });
  const [baseline, setBaseline] = useState(JSON.stringify(form));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const running = useRef(false);
  const key = useRef(createRequestId());
  const formId = useId();
  const nameId = useId();
  const usageHintId = useId();
  const descriptionHintId = useId();
  const descriptionId = useId();
  const ordinalId = useId();
  const ordinalHintId = useId();
  const introductionId = useId();
  const errorRef = useRef<HTMLDivElement>(null);
  const dirty = JSON.stringify(form) !== baseline;
  useEffect(() => { if (!target.id) return; const controller = new AbortController(); adminGet<Category>(`/categories/${target.id}/usage`, controller.signal).then(result => { setResource(result); const value = { code: result.data.code, name: result.data.name, description: result.data.description, active: result.data.active, ordinal: String(result.data.ordinal) }; setForm(value); setBaseline(JSON.stringify(value)); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, [target.id, refresh]);
  useEffect(() => { if (error && !deleting) errorRef.current?.focus(); }, [error, deleting]);
  useEffect(() => { if (!dirty) return; const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent); }, [dirty]);
  async function save(remove = false) { if (running.current) return; running.current = true; setBusy(true); setError(''); try { if (target.id && !resource?.etag) throw new Error('버전을 확인하지 못했습니다.'); await adminWrite(target.id ? `/categories/${target.id}` : '/categories', remove ? 'DELETE' : target.id ? 'PATCH' : 'POST', session, remove ? undefined : { ...form, name: form.name.trim(), ordinal: Number(form.ordinal), parentId: target.parentId }, resource?.etag, key.current); onSaved(remove ? '미사용 분류를 삭제했습니다.' : '분류를 저장했습니다.'); } catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); } }
  const close = () => { if (!busy && (!dirty || window.confirm('저장하지 않은 변경사항을 버리시겠습니까?'))) onClose(); };
  const ordinalInput = <div className={classificationStyles.field}><label htmlFor={ordinalId}><span>표시 순서<RequiredMark /></span></label><div className={classificationStyles.clearable}><Input id={ordinalId} type="number" min={0} step={1} required disabled={busy} aria-describedby={ordinalHintId} value={form.ordinal} onChange={event => setForm(value => ({ ...value, ordinal: event.target.value }))} /><button type="button" aria-label="표시 순서 지우기" disabled={busy || form.ordinal === ''} onClick={() => { setForm(value => ({ ...value, ordinal: '' })); document.getElementById(ordinalId)?.focus(); }}><img src={adminUrl('/assets/classification-clear.svg')} alt="" width={12.5} height={12.5} /></button></div><span id={ordinalHintId} className="sr-only">0 이상의 정수를 입력하세요. 필수 항목입니다.</span></div>;
  const statusInput = <label>상태<select className={styles.select} disabled={busy} aria-describedby={resource ? usageHintId : undefined} value={String(form.active)} onChange={event => setForm(value => ({ ...value, active: event.target.value === 'true' }))}><option value="true">활성</option><option value="false" disabled={!!resource?.data.usageCount}>비활성</option></select></label>;
  return <><Modal open title={`${target.parentId ? '주제' : '분야'} ${target.id ? '수정' : '추가'}`} className={classificationStyles.editorModal} busy={busy} onClose={close} closeIconSrc="/assets/classification-close.svg" descriptionId={!target.id || resource ? introductionId : undefined} footerMode="replace"
    footer={<><Button type="button" variant="secondary" data-initial-focus="true" disabled={busy} onClick={close}>취소</Button>{(!target.id || resource) && <Button type="submit" form={formId} disabled={busy || (!!target.id && !dirty)}>{busy ? '처리 중…' : '저장'}</Button>}</>}>
    {error && <div ref={errorRef} tabIndex={-1} className={styles.error} role="alert">{error}{target.id && <Button variant="secondary" onClick={() => { if (!dirty || window.confirm('입력 내용을 버리고 최신 내용을 불러오시겠습니까?')) { setResource(null); setRefresh(value => value + 1); } }}>최신 내용 확인</Button>}</div>}
    {busy && <p className="sr-only" role="status">분류 변경을 처리하고 있습니다.</p>}
    {!target.id || resource ? <form id={formId} className={classificationStyles.editor} onSubmit={event => { event.preventDefault(); void save(); }}>
      <p id={introductionId} className={classificationStyles.introduction}>{target.parentId ? `${parentName || '선택한 분야'} 분야의 하위 주제를 관리합니다.` : '데이터 분류 정보를 입력하세요.'}</p>
      {target.parentId && <label>상위 분야<Input readOnly value={parentName || target.parentId} /></label>}
      <div className={classificationStyles.field}><label htmlFor={nameId}><span>{target.parentId ? '주제명' : '분야명'}<RequiredMark /></span></label><div className={classificationStyles.clearable}><Input id={nameId} required maxLength={200} disabled={busy} value={form.name} onChange={event => setForm(value => ({ ...value, name: event.target.value }))} /><button type="button" aria-label={`${target.parentId ? '주제명' : '분야명'} 지우기`} disabled={busy || !form.name} onClick={() => { setForm(value => ({ ...value, name: '' })); document.getElementById(nameId)?.focus(); }}><img src={adminUrl('/assets/classification-clear.svg')} alt="" width={12.5} height={12.5} /></button></div></div>
      <div className={classificationStyles.field}><div className={classificationStyles.fieldHeading}><label htmlFor={descriptionId}>설명</label><span aria-hidden="true">{form.description.length.toLocaleString('ko-KR')} / 4,000자</span></div><div className={`${classificationStyles.clearable} ${classificationStyles.textareaClear}`}><textarea id={descriptionId} className={classificationStyles.description} maxLength={4000} disabled={busy} value={form.description} aria-describedby={descriptionHintId} onChange={event => setForm(value => ({ ...value, description: event.target.value }))} />{!target.parentId && <button type="button" aria-label="설명 지우기" disabled={busy || !form.description} onClick={() => { setForm(value => ({ ...value, description: '' })); document.getElementById(descriptionId)?.focus(); }}><img src={adminUrl('/assets/classification-clear.svg')} alt="" width={12.5} height={12.5} /></button>}</div><span id={descriptionHintId} className="sr-only">설명은 4,000자까지 입력할 수 있습니다.</span></div>
      {target.parentId ? ordinalInput : statusInput}
      <fieldset className={classificationStyles.management}><legend>분류 관리 정보</legend>
        <label><span>분류 코드<RequiredMark /></span><Input required pattern="[A-Za-z0-9_\-]{1,80}" maxLength={80} readOnly={!!target.id} disabled={busy} value={form.code} onChange={event => setForm(value => ({ ...value, code: event.target.value }))} /></label>
        {target.parentId ? statusInput : ordinalInput}
        {resource && <p id={usageHintId} className={styles.help}>참조 합계 {resource.data.usageCount}건. 메타데이터 버전 또는 하위 주제가 참조하는 분류는 비활성화·삭제할 수 없습니다.</p>}
        {target.id && <div className={classificationStyles.deleteAction}><Button type="button" variant="danger" disabled={busy || dirty || !!resource?.data.usageCount} aria-describedby={usageHintId} onClick={() => setDeleting(true)}>삭제</Button></div>}
      </fieldset>
    </form> : !error && <p role="status">분류 정보를 확인하고 있습니다.</p>}
  </Modal><ConfirmDialog open={deleting} description={<><strong>{form.name}</strong> 분류를 삭제하시겠습니까?</>} busy={busy} error={error} onCancel={() => setDeleting(false)} onConfirm={() => void save(true)} /></>;
}

function CategoryUsage({ target, parentName, onClose }: { target: Category; parentName?: string; onClose: () => void }) {
  const [category, setCategory] = useState<Category | null>(null);
  const [datasets, setDatasets] = useState<CategoryDatasetPage | null>(null);
  const [error, setError] = useState('');
  const [datasetError, setDatasetError] = useState('');
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Category>(`/categories/${target.id}/usage`, controller.signal).then(({ data }) => { setCategory(data); setError(''); })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [target.id, refresh]);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<CategoryDatasetPage>(`/categories/${target.id}/datasets?page=${page}&size=20`, controller.signal).then(({ data }) => { setDatasets(data); setDatasetError(''); })
      .catch(error => { if (!controller.signal.aborted) setDatasetError(errorMessage(error)); });
    return () => controller.abort();
  }, [target.id, page, refresh]);
  const retry = () => { setError(''); setDatasetError(''); setCategory(null); setDatasets(null); setRefresh(value => value + 1); };
  const movePage = (value: number) => { setDatasets(null); setDatasetError(''); setPage(value); };
  return <Modal open title="분류 사용 현황" wide className={classificationStyles.usageModal} closeIconSrc="/assets/classification-close.svg" onClose={onClose}>
    <div className={classificationStyles.usage}>
      <h3>{parentName ? `${parentName} - ` : ''}{target.name}</h3>
      {error ? <div className={styles.error} role="alert"><p>{error}</p><Button variant="secondary" onClick={retry}>다시 시도</Button></div> : category ? <>
        {category.description && <p className={classificationStyles.descriptionText}>{category.description}</p>}
        <dl className={classificationStyles.summary}><div><dt>참조 합계</dt><dd>{category.usageCount.toLocaleString('ko-KR')}건</dd></div><div><dt>분류 상태</dt><dd>{category.active ? '활성' : '비활성'}</dd></div></dl>
        <p className={styles.help}>조회 가능한 메타데이터 버전의 직접 참조와 공통 하위 주제를 합한 수입니다. 서로 다른 데이터셋의 개수와는 다를 수 있습니다.</p>
        <p className={styles.help}>{category.usageCount ? '참조가 남아 있어 이 분류를 비활성화하거나 삭제할 수 없습니다.' : '현재 조회 범위에서 이 분류를 참조하는 메타데이터 버전이나 하위 주제가 없습니다.'}</p>
      </> : <p role="status">분류 사용 현황을 불러오는 중입니다.</p>}
      <section aria-label="사용 데이터셋 목록" className={classificationStyles.datasetSection}>
        <h4>데이터셋 목록 {datasets && <strong>{datasets.totalElements.toLocaleString('ko-KR')}</strong>}</h4>
        <p className={styles.help}>접근 가능한 기관의 데이터셋만 표시합니다. 분야 조회는 하위 주제를 포함합니다. 각 데이터셋이 이 분류에 마지막으로 연결된 버전이며, ‘과거 연결’은 현재 버전과 다릅니다.</p>
        {datasetError ? <div className={styles.error} role="alert"><p>{datasetError}</p><Button variant="secondary" onClick={retry}>목록 다시 시도</Button></div> : datasets ? <>
          {datasets.items.length ? <section className={`${styles.tableWrap} ${classificationStyles.datasetTableWrap}`} aria-label="분류 사용 데이터셋 표" tabIndex={0}><table className={`${styles.table} ${classificationStyles.datasetTable}`}>
            <caption className="sr-only">권한 범위의 데이터셋과 가장 최근 연결 버전</caption>
            <thead><tr><th scope="col">데이터셋 이름</th><th scope="col">분야</th><th scope="col">주제</th><th scope="col">제공 기관</th><th scope="col">공개 범위</th><th scope="col"><span aria-hidden="true">상태</span><span className="sr-only">연결 버전 상태</span></th><th scope="col">연결 버전</th><th scope="col">수정일</th></tr></thead>
            <tbody>{datasets.items.map(item => <tr key={item.datasetId}><td>{item.title}</td><td><Badge>{item.fieldName}</Badge></td><td>{item.topicName || '—'}</td><td><Badge>{item.providerOrganizationName}</Badge></td><td><span className={item.visibility === 'ORGANIZATION' ? classificationStyles.statusCaution : item.visibility === 'PRIVATE' ? classificationStyles.statusMuted : undefined}><Badge>{visibilityLabel(item.visibility)}</Badge></span></td><td><span className={revisionClass(item.publicationState || item.revisionState)}><Badge>{revisionLabel(item.publicationState || item.revisionState)}</Badge></span></td><td>v{item.revisionNo}{!item.currentRevision && <span className={classificationStyles.historical}>과거 연결</span>}</td><td><time dateTime={item.updatedAt} title={new Date(item.updatedAt).toLocaleString('ko-KR')}>{formatModifiedAt(item.updatedAt)}</time></td></tr>)}</tbody>
          </table></section> : <p className={styles.state}>접근 가능한 사용 데이터셋이 없습니다.</p>}
          <nav aria-label="사용 데이터셋 페이지" className={classificationStyles.pagination}><Button variant="secondary" disabled={page === 0} onClick={() => movePage(page - 1)}>이전</Button><output>{datasets.totalPages ? page + 1 : 0} / {datasets.totalPages} 페이지 · 데이터셋 {datasets.totalElements.toLocaleString('ko-KR')}개</output><Button variant="secondary" disabled={page + 1 >= datasets.totalPages} onClick={() => movePage(page + 1)}>다음</Button></nav>
        </> : <p role="status">사용 데이터셋 목록을 불러오는 중입니다.</p>}
      </section>
    </div>
  </Modal>;
}

function visibilityLabel(value: string) { return ({ PUBLIC: '공개', ORGANIZATION: '기관 공개', PRIVATE: '비공개' } as Record<string, string>)[value] || value; }
function revisionLabel(value: string) { return ({ DRAFT: '초안', IN_REVIEW: '검토 중', APPROVED: '승인', REJECTED: '반려', PUBLISHED: '게시됨', WITHDRAWN: '철회' } as Record<string, string>)[value] || value; }
function revisionClass(value: string) { return value === 'APPROVED' || value === 'PUBLISHED' ? classificationStyles.statusPositive : value === 'IN_REVIEW' ? classificationStyles.statusCaution : classificationStyles.statusMuted; }
function formatModifiedAt(value: string) {
  const parts = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value || '';
  return `${part('year')}.${part('month')}.${part('day')} ${part('hour')}:${part('minute')}`;
}
