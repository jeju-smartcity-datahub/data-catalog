import { createRequestId } from '../../lib/requestId';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { Modal } from '../../components/Modal';
import { Button, Input, RequiredMark } from '../../components/ui';
import { AdminApiError, adminDownload, adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type AuditPage, type FieldDefinition, type FieldSet, type MetadataDraft, type MetadataOptions, type Publication, type Resource, type ReviewRequest } from '../../lib/admin';
import { TermMappingPicker } from './TermMappingPicker';
import { ClearableInput, ClearableTextarea } from './MetadataControls';
import { MetadataAccess } from './MetadataAccess';
import styles from './Metadata.module.css';
import layout from './MetadataPresentation.module.css';

type Tab = 'basic' | 'fields' | 'access' | 'audit';
type Action = 'submit' | 'approve' | 'reject' | 'publish' | 'withdraw' | 'withdrawReview' | 'newDraft';
type Form = { title: string; description: string; ownerOrganizationId: string; providerOrganizationId: string; primaryCategoryId: string; visibility: string; slug: string; department: string; license: string; updateFrequency: string; spatialCoverage: string; temporalStart: string; temporalEnd: string; keywords: string; regionIds: string[]; hubIds: string[]; topicIds: string[] };
const emptyDetails = { department: '', license: '', updateFrequency: '', spatialCoverage: '', temporalStart: '', temporalEnd: '', keywords: '', regionIds: [] as string[], hubIds: [] as string[], topicIds: [] as string[] };
type Loaded = { draft: Resource<MetadataDraft>; fields: Resource<FieldSet>; review?: Resource<ReviewRequest>; publication?: Resource<Publication> };
const actionNames: Record<Action, string> = { submit: '검토 요청', approve: '검토 승인', reject: '검토 반려', publish: '메타데이터 게시', withdraw: '게시 철회', withdrawReview: '검토 요청 철회', newDraft: '새 수정 초안 만들기' };
const dataTypes = ['STRING', 'INTEGER', 'NUMBER', 'BOOLEAN', 'DATE', 'DATETIME', 'OBJECT', 'ARRAY'];

export function MetadataEditor({ id, session, options, onClose, onChanged }: { id: string; session: AdminSession; options: MetadataOptions; onClose: () => void; onChanged: (message: string) => void }) {
  const isNew = id === 'new';
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [loading, setLoading] = useState(!isNew);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<Tab>('basic');
  const [form, setForm] = useState<Form>({ ...emptyDetails, title: '', description: '', ownerOrganizationId: options.organizations[0]?.id || '', providerOrganizationId: options.organizations[0]?.id || '', primaryCategoryId: '', visibility: 'PUBLIC', slug: '' });
  const [fields, setFields] = useState<FieldDefinition[]>([]);
  const [baseline, setBaseline] = useState(isNew ? JSON.stringify(form) : '');
  const [fieldsBaseline, setFieldsBaseline] = useState('[]');
  const [reload, setReload] = useState(0);
  const [action, setAction] = useState<Action | null>(null);
  const [reason, setReason] = useState('');
  const [mappingIndex, setMappingIndex] = useState<number | null>(null);
  const running = useRef(false);
  const keys = useRef(new Map<string, string>());
  const formRef = useRef<HTMLFormElement>(null);
  const tabsRef = useRef<HTMLDivElement>(null);
  const formId = useId();
  const dirty = (isNew || !!loaded) && (JSON.stringify(form) !== baseline || JSON.stringify(fields) !== fieldsBaseline);
  const editable = hasAuthority(session, 'ORG_EDITOR') && (isNew || loaded?.draft.data.state === 'DRAFT');
  const reviewer = hasAuthority(session, 'ORG_REVIEWER');
  const publisher = hasAuthority(session, 'ORG_PUBLISHER');
  const auditor = hasAuthority(session, 'ORG_AUDITOR');

  useEffect(() => {
    if (isNew) return;
    const controller = new AbortController();
    async function load() {
      const draft = await adminGet<MetadataDraft>(`/datasets/${id}/draft`, controller.signal);
      const [fieldResult, review, publication] = await Promise.all([
        adminGet<FieldSet>(`/datasets/${id}/draft/fields`, controller.signal),
        draft.data.reviewRequestId ? adminGet<ReviewRequest>(`/review-requests/${draft.data.reviewRequestId}`, controller.signal) : undefined,
        publisher && draft.data.currentPublicationId ? adminGet<Publication>(`/publications/${draft.data.currentPublicationId}`, controller.signal) : undefined,
      ]);
      if (draft.data.lockVersion !== fieldResult.data.lockVersion) throw new AdminApiError(412);
      const next = formFromDraft(draft.data);
      setLoaded({ draft, fields: fieldResult, review, publication }); setForm(next); setBaseline(JSON.stringify(next)); setFields(fieldResult.data.fields); setFieldsBaseline(JSON.stringify(fieldResult.data.fields)); setLoading(false); setError(''); setConflict(false);
    }
    load().catch(error => { if (!controller.signal.aborted) { setError(errorMessage(error)); setLoading(false); } });
    return () => controller.abort();
  }, [id, isNew, reviewer, publisher, reload]);

  useEffect(() => {
    if (!dirty) return;
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', prevent);
    return () => window.removeEventListener('beforeunload', prevent);
  }, [dirty]);

  function close() { if (!busy && (!dirty || window.confirm('저장하지 않은 변경사항을 버리고 닫으시겠습니까?'))) onClose(); }
  function update(key: keyof Form, value: string | string[]) { setForm(current => ({ ...current, [key]: value })); }
  function changeTab(next: Tab) { if (dirty) { setError('변경한 내용을 먼저 저장해 주세요. 저장 후 다른 탭으로 이동할 수 있습니다.'); return; } setError(''); setTab(next); }
  function handleTabKey(event: KeyboardEvent<HTMLButtonElement>) {
    if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
    const tabs = Array.from(tabsRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]:not(:disabled)') ?? []);
    const current = tabs.indexOf(event.currentTarget);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    event.preventDefault();
    if (dirty) { changeTab(tab); return; }
    tabs[next]?.focus(); tabs[next]?.click();
  }
  function retryLatest() { if (dirty && !window.confirm('입력 중인 내용을 버리고 서버의 최신 내용을 불러오시겠습니까?')) return; setLoading(true); setError(''); setReload(value => value + 1); }
  function keyFor(path: string, body: unknown, etag?: string | null) { const fingerprint = JSON.stringify([path, body, etag]); let key = keys.current.get(fingerprint); if (!key) { key = createRequestId(); keys.current.set(fingerprint, key); } return key; }
  async function run(work: () => Promise<void>) {
    if (running.current) return;
    running.current = true; setBusy(true); setError('');
    try { await work(); } catch (failure) { setError(errorMessage(failure)); setConflict(failure instanceof AdminApiError && (failure.status === 412 || failure.status === 409)); }
    finally { running.current = false; setBusy(false); }
  }
  async function save() {
    if (!formRef.current?.reportValidity()) return;
    const keywords = form.keywords ? form.keywords.split('\n').map(value => value.trim()).filter(Boolean) : [];
    if (tab === 'basic' && (keywords.length > 20 || keywords.some(value => value.length > 100) || new Set(keywords).size !== keywords.length)) { setError('키워드는 중복 없이 최대 20개, 각각 100자까지 입력해 주세요.'); return; }
    if (tab === 'basic' && form.temporalStart && form.temporalEnd && form.temporalEnd < form.temporalStart) { setError('기간 종료일은 시작일 이후여야 합니다.'); return; }
    await run(async () => {
      if (!isNew && !loaded?.draft.etag) throw new Error('수정 버전을 확인하지 못했습니다.');
      if (tab === 'fields') {
        const path = `/datasets/${id}/draft/fields`;
        await adminWrite<FieldSet>(path, 'PUT', session, { fields: fields.map((field, ordinal) => ({ ...field, ordinal })) }, loaded?.draft.etag);
        onChanged('필드정보를 저장했습니다. 검토 승인 전까지 공개 게시본에는 반영되지 않습니다.'); return;
      }
      const body = { title: form.title.trim(), description: form.description.trim(), providerOrganizationId: form.providerOrganizationId, primaryCategoryId: form.primaryCategoryId || null, visibility: form.visibility, regionIds: form.regionIds, hubIds: form.hubIds, topicIds: form.topicIds, details: { department: form.department.trim() || null, license: form.license.trim() || null, updateFrequency: form.updateFrequency.trim() || null, spatialCoverage: form.spatialCoverage.trim() || null, temporalStart: form.temporalStart || null, temporalEnd: form.temporalEnd || null, keywords } };
      if (isNew) {
        const create = { ...body, ownerOrganizationId: form.ownerOrganizationId, slug: form.slug.trim() };
        await adminWrite<MetadataDraft>('/datasets', 'POST', session, create, undefined, keyFor('/datasets', create));
      } else await adminWrite<MetadataDraft>(`/datasets/${id}/draft`, 'PATCH', session, body, loaded?.draft.etag);
      onChanged(isNew ? '초안을 등록했습니다. 목록에서 열어 필드정보를 추가하고 검토를 요청하세요.' : '초안을 저장했습니다. 검토 승인 전까지 공개 게시본에는 반영되지 않습니다.');
    });
  }
  async function perform() {
    if (!loaded || !action) return;
    await run(async () => {
      let path = ''; let body: unknown; let etag: string | null = loaded.draft.etag;
      if (action === 'submit') path = `/datasets/${id}/review-requests`;
      if (action === 'newDraft') path = `/datasets/${id}/drafts`;
      if (action === 'approve' || action === 'reject') { path = `/review-requests/${loaded.review?.data.id}/decisions`; body = { decision: action === 'approve' ? 'APPROVE' : 'REJECT', reason: reason.trim() }; etag = loaded.review?.etag || null; }
      if (action === 'publish') { path = `/datasets/${id}/publications`; body = { revisionId: loaded.draft.data.revisionId, reviewDecisionId: loaded.draft.data.reviewDecisionId }; }
      if (action === 'withdraw') { path = `/publications/${loaded.publication?.data.id}/withdrawal`; body = { reason: reason.trim() }; etag = loaded.publication?.etag || null; }
      if (action === 'withdrawReview') { path = `/review-requests/${loaded.review?.data.id}/withdrawal`; body = { reason: reason.trim() }; etag = loaded.review?.etag || null; }
      if (!etag) throw new Error('수정 버전을 확인하지 못했습니다.');
      await adminWrite(path, 'POST', session, body, etag, keyFor(path, body, etag));
      onChanged(`${actionNames[action]} 처리가 완료되었습니다.`);
    });
  }
  const missingRequired = [['제목', form.title], ['설명', form.description], ['제공기관', form.providerOrganizationId], ['공개등급', form.visibility], ['담당부서', form.department], ['라이선스', form.license], ['키워드', form.keywords]].filter(([, value]) => !value.trim()).map(([label]) => label);
  const draft = loaded?.draft.data;
  const topicParent = options.topics?.find(item => item.id === form.primaryCategoryId)?.parentId || form.primaryCategoryId;
  const topics = options.topics?.filter(item => item.parentId === topicParent) || [];
  const stateBadge = draft ? <span className={`${styles.badge} ${draft.state === 'IN_REVIEW' ? styles.review : draft.state === 'APPROVED' ? styles.published : draft.state === 'REJECTED' ? styles.rejected : ''}`}>{loaded?.review?.data.status === 'WITHDRAWN' ? '검토 철회' : ({ DRAFT: '초안', IN_REVIEW: '검토 대기', APPROVED: '승인', REJECTED: '반려' } as Record<string, string>)[draft.state] || draft.state}</span> : undefined;
  return <>
    <Modal open title={isNew ? '메타데이터 초안 등록' : '메타데이터 상세'} titleAdornment={stateBadge} className={layout.dialog} wide busy={busy} onClose={close} footerMode="replace" footer={<div className={layout.footer}>
      {draft && <div className={layout.footerDownload}><Button variant="secondary" disabled={busy || dirty} onClick={() => void run(() => adminDownload(`/datasets/${id}/definition?format=json`, `metadata-${id}.json`))}>정의서 JSON</Button><Button variant="secondary" disabled={busy || dirty} onClick={() => void run(() => adminDownload(`/datasets/${id}/definition?format=csv`, `metadata-${id}.csv`))}>정의서 CSV</Button></div>}
      <Button variant="secondary" disabled={busy} onClick={close}>취소</Button>
      {editable && (tab === 'basic' || tab === 'fields') && <Button form={formId} type="submit" disabled={loading || busy || (!isNew && !dirty) || conflict}>{busy ? '저장 중…' : isNew ? '초안 등록' : '변경 저장'}</Button>}
      {draft && <>
        {editable && <Button disabled={busy || dirty || conflict} onClick={() => { setReason(''); setAction('submit'); }}>검토 요청</Button>}
        {hasAuthority(session, 'ORG_EDITOR') && draft.state === 'IN_REVIEW' && loaded?.review?.data.status === 'SUBMITTED' && <Button variant="secondary" disabled={busy || dirty || conflict} onClick={() => { setReason(''); setAction('withdrawReview'); }}>검토 요청 철회</Button>}
        {reviewer && draft.state === 'IN_REVIEW' && loaded?.review && <><Button disabled={busy || dirty} onClick={() => { setReason(''); setAction('approve'); }}>검토 승인</Button><Button variant="danger" disabled={busy || dirty} onClick={() => { setReason(''); setAction('reject'); }}>반려</Button></>}
        {publisher && draft.state === 'APPROVED' && draft.reviewDecisionId && loaded?.publication?.data.revisionId !== draft.revisionId && <Button disabled={busy || dirty || conflict} onClick={() => { setReason(''); setAction('publish'); }}>메타데이터 게시</Button>}
        {publisher && loaded?.publication?.data.state === 'PUBLISHED' && <Button variant="danger" disabled={busy || dirty || conflict} onClick={() => { setReason(''); setAction('withdraw'); }}>게시 철회</Button>}
        {hasAuthority(session, 'ORG_EDITOR') && ['APPROVED', 'REJECTED'].includes(draft.state) && <Button variant="secondary" disabled={busy || dirty || conflict} onClick={() => { setReason(''); setAction('newDraft'); }}>새 수정 초안 만들기</Button>}
      </>}
    </div>}>
      {loading ? <p className={styles.state} role="status">메타데이터를 불러오는 중입니다.</p> : <>
        {draft && <div className={`${styles.summary} ${layout.detailSummary}`}><span>메타데이터 v{draft.revisionNo}</span>{draft.currentPublicationId && <span className={`${styles.badge} ${styles.published}`}>게시본 있음</span>}{dirty && <span role="status">저장하지 않은 변경사항</span>}</div>}
        {error && <div className={styles.error} role="alert">{error}{!isNew && (conflict || !loaded) && <div><Button variant="secondary" disabled={busy} onClick={retryLatest}>최신 내용 불러오기</Button></div>}</div>}
        {(isNew || loaded) && <>
          <div ref={tabsRef} className={`${styles.tabs} ${layout.detailTabs}`} role="tablist" aria-label="메타데이터 상세정보">{([{ key: 'basic', label: '기본정보' }, { key: 'fields', label: '필드정보' }, { key: 'access', label: '데이터 이용 경로' }, ...(auditor && !isNew ? [{ key: 'audit', label: '변경 이력' }] : [])] as { key: Tab; label: string }[]).map(item => <button key={item.key} id={`metadata-tab-${item.key}`} type="button" role="tab" aria-selected={tab === item.key} aria-controls={tab === item.key ? `metadata-panel-${item.key}` : undefined} tabIndex={tab === item.key ? 0 : -1} disabled={busy || (isNew && item.key !== 'basic')} onKeyDown={handleTabKey} onClick={() => changeTab(item.key)}>{item.label}{item.key === 'fields' && <span className={layout.tabCount}>{fields.length}</span>}</button>)}</div>
          <form id={formId} ref={formRef} className={layout.contentForm} onSubmit={event => { event.preventDefault(); void save(); }}>
            <div role="tabpanel" id={`metadata-panel-${tab}`} aria-labelledby={`metadata-tab-${tab}`}>
              {tab === 'basic' && <><h3 className={styles.subheading}>게시 필수 정보</h3><output className={styles.help}><span id={`${formId}-registration-required`}>필수 7개 중 {7 - missingRequired.length}개 입력{missingRequired.length > 0 ? ` · 누락: ${missingRequired.join(', ')}` : ' · 입력 완료'}</span></output><div className={styles.formGrid}>
                <label className={styles.full}><span>데이터셋 이름<RequiredMark /></span><ClearableInput required maxLength={300} value={form.title} readOnly={!editable} disabled={busy} onChange={event => update('title', event.target.value)} clearLabel="데이터셋 이름 비우기" onClear={() => update('title', '')} /></label>
                <label className={styles.full}><span>데이터 설명<RequiredMark /></span><ClearableTextarea className={styles.textarea} aria-describedby={`${formId}-registration-required`} maxLength={20000} value={form.description} readOnly={!editable} disabled={busy} onChange={event => update('description', event.target.value)} clearLabel="데이터 설명 비우기" onClear={() => update('description', '')} /></label>
                {isNew && <><label><span>관리 기관<RequiredMark /></span><select className={styles.select} required value={form.ownerOrganizationId} disabled={busy} onChange={event => update('ownerOrganizationId', event.target.value)}><option value="">기관 선택</option>{options.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label><span>데이터셋 식별 이름<RequiredMark /></span><Input required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" maxLength={100} value={form.slug} disabled={busy} placeholder="예: parking-status" onChange={event => update('slug', event.target.value)} /><span>영문 소문자·숫자·하이픈을 사용합니다.</span></label></>}
                <label><span>제공기관<RequiredMark /></span><select className={styles.select} required value={form.providerOrganizationId} disabled={!editable || busy} onChange={event => update('providerOrganizationId', event.target.value)}><option value="">기관 선택</option>{options.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
                <label><span>담당부서<RequiredMark /></span><ClearableInput aria-describedby={`${formId}-registration-required`} maxLength={200} value={form.department} readOnly={!editable} disabled={busy} onChange={event => update('department', event.target.value)} clearLabel="담당부서 비우기" onClear={() => update('department', '')} /></label>
                <label><span>공개 범위<RequiredMark /></span><select className={styles.select} value={form.visibility} disabled={!editable || busy} onChange={event => update('visibility', event.target.value)}><option value="PUBLIC">공개</option><option value="ORGANIZATION">기관 내부</option><option value="PRIVATE">비공개</option></select></label>
                <label><span>라이선스<RequiredMark /></span><ClearableInput aria-describedby={`${formId}-registration-required`} maxLength={500} value={form.license} readOnly={!editable} disabled={busy} onChange={event => update('license', event.target.value)} clearLabel="라이선스 비우기" onClear={() => update('license', '')} /><span>메타데이터 설명이며 실제 데이터 재배포 권한을 부여하지 않습니다.</span></label>
                <label className={styles.full}><span>키워드<RequiredMark /></span><ClearableTextarea className={`${styles.textarea} ${layout.keywords}`} aria-describedby={`${formId}-registration-required`} value={form.keywords} maxLength={2020} readOnly={!editable} disabled={busy} onChange={event => update('keywords', event.target.value)} clearLabel="키워드 비우기" onClear={() => update('keywords', '')} /><span>한 줄에 하나씩, 중복 없이 최대 20개를 입력하세요. 키워드마다 100자까지 저장할 수 있습니다.</span></label>
              </div><details className={layout.optionalDetails} onInvalidCapture={event => { event.currentTarget.open = true; }}><summary>추가 정보 (선택)</summary><div className={styles.formGrid}>
                <label>분야<select className={styles.select} value={form.primaryCategoryId} disabled={!editable || busy} onChange={event => setForm(current => ({ ...current, primaryCategoryId: event.target.value, topicIds: [] }))}><option value="">미분류</option>{options.categories.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
                <MetadataChoices label="주제" options={topics} selected={form.topicIds} disabled={!editable || busy} onChange={value => update('topicIds', value)} emptyMessage={form.primaryCategoryId ? '해당 분야에 등록된 주제가 없습니다.' : '분야를 먼저 선택해 주세요.'} />
                <label>데이터 갱신 주기<ClearableInput maxLength={100} value={form.updateFrequency} readOnly={!editable} disabled={busy} onChange={event => update('updateFrequency', event.target.value)} clearLabel="데이터 갱신 주기 비우기" onClear={() => update('updateFrequency', '')} /></label>
                <label>공간 범위<ClearableInput maxLength={500} value={form.spatialCoverage} readOnly={!editable} disabled={busy} onChange={event => update('spatialCoverage', event.target.value)} clearLabel="공간 범위 비우기" onClear={() => update('spatialCoverage', '')} /></label>
                <fieldset className={`${styles.full} ${layout.dateRange}`}><legend>기간 범위</legend><div><label><span className="sr-only">기간 시작일</span><Input type="date" value={form.temporalStart} max={form.temporalEnd || undefined} readOnly={!editable} disabled={busy} onChange={event => update('temporalStart', event.target.value)} /></label><span aria-hidden="true">~</span><label><span className="sr-only">기간 종료일</span><Input type="date" value={form.temporalEnd} min={form.temporalStart || undefined} readOnly={!editable} disabled={busy} onChange={event => update('temporalEnd', event.target.value)} /></label></div></fieldset>
                <MetadataChoices label="지역" options={options.regions || []} selected={form.regionIds} disabled={!editable || busy} onChange={value => update('regionIds', value)} emptyMessage="등록된 지역 선택지가 없습니다." />
                <MetadataChoices label="연결 허브" options={options.hubs || []} selected={form.hubIds} disabled={!editable || busy} onChange={value => update('hubIds', value)} emptyMessage="등록된 허브 선택지가 없습니다." />
              </div></details></>}
              {tab === 'fields' && <><div className={styles.panelHeading}><h3 className={styles.subheading}>필드 정보 <strong>{fields.length}</strong></h3>{editable && <Button type="button" disabled={busy || fields.length >= 500} onClick={() => setFields(current => [...current, { fieldPath: '', physicalName: '', displayName: '', description: '', dataType: 'STRING', nullable: true, maxLength: null, unit: null, ordinal: current.length, publicVisible: true, approvedTermId: null, mappingEvidence: null }])}>필드 추가</Button>}</div>
                {fields.length === 0 && <p className={styles.state}>등록된 필드가 없습니다.</p>}
                {fields.map((field, index) => <FieldRow key={index} field={field} index={index} disabled={!editable || busy} onChange={next => setFields(current => current.map((item, position) => position === index ? next : item))} onRemove={() => setFields(current => current.filter((_, position) => position !== index))} onMap={() => setMappingIndex(index)} />)}
              </>}
              {tab === 'access' && <MetadataAccess datasetId={id} published={!!draft?.currentPublicationId} organizationId={draft?.ownerOrganizationId || form.ownerOrganizationId} session={session} />}
              {tab === 'audit' && <AuditHistory id={id} />}
            </div>
          </form>
          {loaded?.review?.data.reason && <p className={styles.help}>검토 의견: {loaded.review.data.reason}</p>}
          {loaded?.review?.data.status === 'WITHDRAWN' && <p className={styles.help}>철회된 검토본은 기록으로 보존됩니다. 새 수정 초안을 만든 뒤 보완하고 다시 검토를 요청하세요.</p>}
        </>}
      </>}
    </Modal>
    {mappingIndex !== null && fields[mappingIndex] && <TermMappingPicker datasetId={id} organizationId={form.ownerOrganizationId} field={fields[mappingIndex]} onClose={() => setMappingIndex(null)} onApply={(approvedTermId, mappingEvidence) => { setFields(current => current.map((field, index) => index === mappingIndex ? { ...field, approvedTermId, mappingEvidence } : field)); setMappingIndex(null); }} />}
    {action && <Modal open title={actionNames[action]} busy={busy} onClose={() => { if (!busy) setAction(null); }}>
      <form onSubmit={event => { event.preventDefault(); void perform(); }}><p>{action === 'withdraw' ? '게시를 철회하면 공개 목록과 상세에서 제외됩니다.' : action === 'withdrawReview' ? '현재 검토 요청을 철회하고 검토본을 보존합니다. 수정하려면 철회 후 새 초안을 만들어야 합니다. 이미 공개된 게시본은 유지됩니다.' : action === 'publish' ? '승인된 버전의 메타데이터를 게시합니다. 실제 데이터 제공 기능은 활성화되지 않습니다.' : action === 'submit' ? '저장한 기본정보와 필드를 검토 요청합니다. 필수 정보 7개를 확인하세요.' : action === 'newDraft' ? '기존 버전을 보존하고 수정할 수 있는 새 초안을 만듭니다.' : '제출된 메타데이터와 필드정보를 확인한 뒤 결정해 주세요.'}</p>
        {['approve', 'reject', 'withdraw', 'withdrawReview'].includes(action) && <label className={styles.reason}><span>{action === 'approve' ? '검토 의견 (선택)' : '사유'}{action !== 'approve' && <RequiredMark />}</span><textarea className={styles.textarea} required={action !== 'approve'} minLength={action !== 'approve' ? 1 : undefined} maxLength={2000} value={reason} disabled={busy} onChange={event => setReason(event.target.value)} /></label>}
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.actions}><Button variant={action === 'withdraw' || action === 'withdrawReview' || action === 'reject' ? 'danger' : 'primary'} disabled={busy || (['reject', 'withdraw', 'withdrawReview'].includes(action) && !reason.trim()) || conflict}>{busy ? '처리 중…' : actionNames[action]}</Button></div>
      </form>
    </Modal>}
  </>;
}

function formFromDraft(draft: MetadataDraft): Form { return { title: draft.title, description: draft.description, ownerOrganizationId: draft.ownerOrganizationId, providerOrganizationId: draft.providerOrganizationId, primaryCategoryId: draft.primaryCategoryId || '', visibility: draft.visibility, slug: draft.slug, department: draft.details?.department || '', license: draft.details?.license || '', updateFrequency: draft.details?.updateFrequency || '', spatialCoverage: draft.details?.spatialCoverage || '', temporalStart: draft.details?.temporalStart || '', temporalEnd: draft.details?.temporalEnd || '', keywords: draft.details?.keywords?.join('\n') || '', regionIds: draft.regionIds || [], hubIds: draft.hubIds || [], topicIds: draft.topicIds || [] }; }

function MetadataChoices({ label, options, selected, disabled, onChange, emptyMessage }: { label: string; options: { id: string; name: string }[]; selected: string[]; disabled: boolean; onChange: (selected: string[]) => void; emptyMessage: string }) {
  const choices = [...options, ...selected.filter(id => !options.some(item => item.id === id)).map(id => ({ id, name: `사용 중인 항목 (${id})` }))];
  return <fieldset className={layout.choices}><legend>{label}</legend>{disabled || !choices.length ? <p className={layout.choiceSummary}>{selected.length ? choices.filter(item => selected.includes(item.id)).map(item => item.name).join(', ') : choices.length ? '선택 없음' : emptyMessage}</p> : <details><summary>{selected.length ? choices.filter(item => selected.includes(item.id)).map(item => item.name).join(', ') : `${label} 선택`}</summary><div className={layout.choiceOptions}>{choices.map(item => <label key={item.id}><input type="checkbox" checked={selected.includes(item.id)} disabled={!selected.includes(item.id) && selected.length >= 20} onChange={event => onChange(event.target.checked ? [...selected, item.id] : selected.filter(id => id !== item.id))} />{item.name}</label>)}<p className={styles.help}>최대 20개를 선택할 수 있습니다.</p></div></details>}</fieldset>;
}

function FieldRow({ field, index, disabled, onChange, onRemove, onMap }: { field: FieldDefinition; index: number; disabled: boolean; onChange: (field: FieldDefinition) => void; onRemove: () => void; onMap: () => void }) {
  const [expanded, setExpanded] = useState(!field.fieldPath);
  function update(key: keyof FieldDefinition, value: string | boolean | number | null) { onChange({ ...field, [key]: value }); }
  return <fieldset className={layout.fieldRow}><legend className="sr-only">{`필드 ${index + 1}`}</legend>
    <div className={layout.fieldColumn}><label><span>물리명<RequiredMark /></span><ClearableInput required maxLength={200} value={field.physicalName} readOnly={disabled} onChange={event => update('physicalName', event.target.value)} clearLabel={`필드 ${index + 1} 물리명 비우기`} onClear={() => update('physicalName', '')} /></label><label><span>표시이름<RequiredMark /></span><ClearableInput required maxLength={200} value={field.displayName} readOnly={disabled} onChange={event => update('displayName', event.target.value)} clearLabel={`필드 ${index + 1} 표시이름 비우기`} onClear={() => update('displayName', '')} /></label></div>
    <div className={layout.fieldColumn}><label><span>자료형<RequiredMark /></span><select className={styles.select} value={field.dataType} disabled={disabled} onChange={event => update('dataType', event.target.value)}>{dataTypes.map(type => <option key={type}>{type}</option>)}</select></label><label>단위<ClearableInput maxLength={100} value={field.unit || ''} readOnly={disabled} onChange={event => update('unit', event.target.value || null)} clearLabel={`필드 ${index + 1} 단위 비우기`} onClear={() => update('unit', null)} /></label></div>
    <div className={layout.fieldColumn}><label>필드 설명<ClearableTextarea className={`${styles.textarea} ${layout.fieldDescription}`} maxLength={4000} value={field.description || ''} readOnly={disabled} onChange={event => update('description', event.target.value)} clearLabel={`필드 ${index + 1} 설명 비우기`} onClear={() => update('description', '')} /></label></div>
    <div className={layout.fieldColumn}><span className={layout.fieldContext}>표준용어</span>{!disabled ? <Button type="button" className={layout.fieldMap} variant="secondary" aria-label={`필드 ${index + 1} 표준용어 연결`} onClick={onMap}>표준용어 연결</Button> : <p className={layout.fieldMapInfo}>{field.approvedTermId ? '승인 표준용어 연결됨' : '연결된 표준용어 없음'}</p>}{field.approvedTermId && <><p className={layout.fieldMapInfo}>{field.mappingEvidence}</p>{!disabled && <Button type="button" variant="secondary" aria-label={`필드 ${index + 1} 표준용어 연결 해제`} onClick={() => onChange({ ...field, approvedTermId: null, mappingEvidence: null })}>연결 해제</Button>}</>}</div>
    {!disabled && <Button type="button" className={layout.fieldDelete} variant="secondary" aria-label={`필드 ${index + 1} 삭제`} onClick={onRemove}>삭제</Button>}
    <details className={layout.fieldAdditional} open={expanded} onToggle={event => setExpanded(event.currentTarget.open)} onInvalidCapture={event => { event.currentTarget.open = true; setExpanded(true); }}><summary>필드 경로와 추가 속성</summary><div className={layout.fieldAdditionalBody}><label><span>필드 경로<RequiredMark /></span><Input required maxLength={200} value={field.fieldPath} readOnly={disabled} onChange={event => update('fieldPath', event.target.value)} /></label><label>최대 길이<Input type="number" min={1} max={1000000} value={field.maxLength ?? ''} readOnly={disabled} onChange={event => update('maxLength', event.target.value ? Number(event.target.value) : null)} /></label><div className={layout.checks}><label><input type="checkbox" checked={field.nullable} disabled={disabled} onChange={event => update('nullable', event.target.checked)} />빈 값 허용</label><label><input type="checkbox" checked={field.publicVisible} disabled={disabled} onChange={event => update('publicVisible', event.target.checked)} />공개 필드정보에 표시</label></div></div></details>
  </fieldset>;
}

function AuditHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const [state, setState] = useState<{ data?: AuditPage; error?: string }>({});
  useEffect(() => { const controller = new AbortController(); adminGet<AuditPage>(`/datasets/${id}/audit-events?page=${page}&size=20`, controller.signal).then(({ data }) => setState({ data })).catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); }); return () => controller.abort(); }, [id, page, retry]);
  if (state.error) return <div className={styles.error} role="alert">{state.error}<Button type="button" variant="secondary" onClick={() => { setState({}); setRetry(value => value + 1); }}>변경 이력 다시 불러오기</Button></div>;
  if (!state.data) return <p role="status">변경 이력을 불러오는 중입니다.</p>;
  return <><h3 className={styles.subheading}>변경 감사 이력</h3>{state.data.items.length === 0 ? <p className={styles.state} role="status">기록된 변경 이력이 없습니다.</p> : <section className={`${styles.tableWrap} ${layout.tableScroll}`} aria-label="메타데이터 변경 감사 이력" tabIndex={0}><table className={styles.table}><caption className="sr-only">메타데이터 변경 감사 이력</caption><thead><tr><th scope="col">일시 (한국 시간)</th><th scope="col">행위자</th><th scope="col">작업</th><th scope="col">변경 내용</th><th scope="col">결과</th></tr></thead><tbody>{state.data.items.map(item => <tr key={item.id}><td>{new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Seoul' }).format(new Date(item.occurredAt))}</td><td>{item.actorUserId}</td><td>{item.action}</td><td>{typeof item.changeSummary === 'string' ? item.changeSummary : JSON.stringify(item.changeSummary)}</td><td>{item.result}</td></tr>)}</tbody></table></section>}<div className={styles.pagination}><Button type="button" variant="secondary" disabled={page === 0} onClick={() => { setState({}); setPage(value => value - 1); }}>이전 이력</Button><span>{page + 1} / {Math.max(1, state.data.page.totalPages)}</span><Button type="button" variant="secondary" disabled={page + 1 >= state.data.page.totalPages} onClick={() => { setState({}); setPage(value => value + 1); }}>다음 이력</Button></div></>;
}
