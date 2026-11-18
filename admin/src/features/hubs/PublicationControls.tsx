import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button, Input } from '../../components/ui';
import { AdminApiError, adminGet, adminWrite, errorMessage, type AdminSession, type MetadataOptions, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { createRequestId } from '../../lib/requestId';
import common from '../metadata/Metadata.module.css';
import hubs from './Hubs.module.css';
import styles from './PublicationControls.module.css';
import { canPublishCandidates } from './publicationPermissions';

type Policy = components['schemas']['PublicationPolicy'];
type PolicyInput = components['schemas']['PublicationPolicyInput'];
type Preview = components['schemas']['PublicationPreview'];
type BatchResult = components['schemas']['BatchPublicationResult'];
type PublishInput = components['schemas']['PublishCandidatesInput'];
type PolicyForm = { enabled: boolean; department: string; license: string; keywords: string; defaultCategoryId: string };
const toForm = (policy: Policy): PolicyForm => ({ enabled: policy.enabled, department: policy.department || '', license: policy.license || '', keywords: policy.keywords.join(', '), defaultCategoryId: policy.defaultCategoryId || '' });
const toInput = (form: PolicyForm): PolicyInput => ({ ...form, department: form.department.trim() || undefined, license: form.license.trim() || undefined, keywords: form.keywords.split(',').map(value => value.trim()).filter(Boolean), defaultCategoryId: form.defaultCategoryId || undefined });

export function ConnectionPublicationSettings({ connectionId, session, refresh, onChanged }: { connectionId: string; session: AdminSession; refresh: number; onChanged: (message: string) => void }) {
  const [policy, setPolicy] = useState<Resource<Policy> | null>(null);
  const [error, setError] = useState('');
  const [edit, setEdit] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Policy>(`/connections/${connectionId}/publication-policy`, controller.signal).then(result => { setPolicy(result); setError(''); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [connectionId, refresh]);
  return <section className={hubs.panel} aria-label="게시 설정"><div className={hubs.heading}><h2>게시 설정</h2>{canPublishCandidates(session) && <Button type="button" variant="secondary" disabled={!policy} onClick={() => setEdit(true)}>게시 설정 수정</Button>}</div><div className={hubs.history}>
    {error && <p role="alert" className={common.error}>{error}</p>}
    {policy ? <div className={styles.settings}><div><Badge tone={policy.data.enabled ? 'success' : 'neutral'}>{policy.data.enabled ? '수집 후 바로 게시' : '목록에서 선택 후 게시'}</Badge><p>{policy.data.enabled ? '필수 정보가 갖춰진 공개 데이터를 자동으로 게시합니다. 누락·충돌 항목은 목록에서 확인하세요.' : '수집 목록에서 여러 항목을 선택해 한 번에 게시할 수 있습니다.'}</p></div></div> : !error && <p role="status">게시 설정을 불러오고 있습니다.</p>}
  </div>{edit && policy && <PolicyEditor connectionId={connectionId} resource={policy} session={session} onClose={() => setEdit(false)} onSaved={resource => { setPolicy(resource); setEdit(false); onChanged('게시 설정을 저장했습니다.'); }} />}</section>;
}

function PolicyEditor({ connectionId, resource, session, onClose, onSaved }: { connectionId: string; resource: Resource<Policy>; session: AdminSession; onClose: () => void; onSaved: (resource: Resource<Policy>) => void }) {
  const id = useId();
  const [original] = useState(resource);
  const [form, setForm] = useState(() => toForm(resource.data));
  const [categories, setCategories] = useState<MetadataOptions['categories']>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const running = useRef(false);
  const dirty = JSON.stringify(form) !== JSON.stringify(toForm(original.data));
  useEffect(() => { const controller = new AbortController(); adminGet<MetadataOptions>('/metadata-options', controller.signal).then(result => setCategories(result.data.categories)).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }); return () => controller.abort(); }, []);
  function close() { if (!busy && (!dirty || window.confirm('저장하지 않은 변경사항을 버리시겠습니까?'))) onClose(); }
  async function save() {
    if (running.current || !original.etag || uncertain) return;
    running.current = true; setBusy(true); setError('');
    try { onSaved(await adminWrite<Policy>(`/connections/${connectionId}/publication-policy`, 'PUT', session, toInput(form), original.etag)); }
    catch (error) { setError(errorMessage(error)); if (error instanceof AdminApiError && error.status === 0) setUncertain(true); }
    finally { running.current = false; setBusy(false); }
  }
  return <Modal open size="medium" title="게시 설정" busy={busy} onClose={close} footer={<Button form={id} type="submit" disabled={busy || !dirty || !original.etag || uncertain}>{busy ? '저장 중…' : '저장'}</Button>}>
    {error && <p role="alert" className={common.error}>{error}</p>}
    <form id={id} className={styles.form} onSubmit={event => { event.preventDefault(); void save(); }}>
      <label className={styles.check}><input type="checkbox" checked={form.enabled} disabled={busy} onChange={event => setForm({ ...form, enabled: event.target.checked })} />수집 후 바로 게시</label>
      <p className={common.help}>체크하면 기존 미게시 데이터와 이후 수집한 공개 데이터의 메타데이터가 사용자 화면과 DCAT API에 바로 공개됩니다. 필수 정보가 없거나 변경이 충돌한 항목은 게시하지 않습니다.</p>
      <h3 className={common.subheading}>공통 정보</h3><p className={common.help}>허브에서 제공하지 않은 값에만 적용합니다.</p>
      <label>담당부서<Input maxLength={200} disabled={busy} value={form.department} onChange={event => setForm({ ...form, department: event.target.value })} /></label>
      <label>라이선스<Input maxLength={500} disabled={busy} value={form.license} onChange={event => setForm({ ...form, license: event.target.value })} /></label>
      <details className={styles.optional}><summary>추가 정보 (선택)</summary><div>
        <label>기본 분류<select className={common.select} disabled={busy} value={form.defaultCategoryId} onChange={event => setForm({ ...form, defaultCategoryId: event.target.value })}><option value="">허브 정보로 자동 분류</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
        <label>공통 키워드<Input disabled={busy} value={form.keywords} placeholder="쉼표로 구분" onChange={event => setForm({ ...form, keywords: event.target.value })} /></label>
      </div></details>
    </form>
  </Modal>;
}

export function BatchPublicationModal({ connectionId, candidateIds, session, onClose, onPublished }: { connectionId: string; candidateIds: string[]; session: AdminSession; onClose: () => void; onPublished: (message: string) => void }) {
  const formId = useId();
  const [policy, setPolicy] = useState<Resource<Policy> | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [form, setForm] = useState<PolicyForm | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<BatchResult | null>(null);
  const [refresh, setRefresh] = useState(0);
  const running = useRef(false);
  const pending = useRef<{ body: PublishInput; key: string } | null>(null);
  const [uncertain, setUncertain] = useState(false);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    Promise.all([adminGet<Policy>(`/connections/${connectionId}/publication-policy`, controller.signal), adminWrite<Preview>(`/connections/${connectionId}/publication-preview`, 'POST', session, { candidateIds }, undefined, createRequestId())])
      .then(([resource, checked]) => { if (active) { setPolicy(resource); setForm(toForm(resource.data)); setPreview(checked.data); setError(''); } }).catch(error => { if (active) setError(errorMessage(error)); });
    return () => { active = false; controller.abort(); };
  }, [connectionId, candidateIds, session, refresh]);
  const missing = new Set(preview?.items.flatMap(item => item.missingFields) || []);
  const shared = ['담당부서', '라이선스', '키워드'].filter(field => missing.has(field));
  const hasPotential = preview?.items.some(item => item.publishable || (!item.alreadyPublished && item.missingFields.length > 0 && item.missingFields.every(field => ['담당부서', '라이선스', '키워드'].includes(field))));
  async function publish() {
    if (running.current || !policy?.etag || !form || !preview) return;
    running.current = true; setBusy(true); setError('');
    try {
      if (!pending.current) {
        const next = { ...toForm(policy.data), department: missing.has('담당부서') ? form.department : policy.data.department || '', license: missing.has('라이선스') ? form.license : policy.data.license || '', keywords: missing.has('키워드') ? form.keywords : policy.data.keywords.join(', ') };
        if (JSON.stringify(toInput(next)) !== JSON.stringify(toInput(toForm(policy.data)))) {
          const saved = await adminWrite<Policy>(`/connections/${connectionId}/publication-policy`, 'PUT', session, toInput(next), policy.etag);
          setPolicy(saved);
        }
        const checked = await adminWrite<Preview>(`/connections/${connectionId}/publication-preview`, 'POST', session, { candidateIds }, undefined, createRequestId());
        setPreview(checked.data);
        const items = checked.data.items.filter(item => item.publishable).map(item => ({ candidateId: item.candidateId, etag: item.etag, targetEtag: item.targetEtag || undefined }));
        if (!items.length) { setError('게시할 수 있는 항목이 없습니다. 아래 확인 사항을 보완해 주세요.'); return; }
        pending.current = { body: { items, policyVersion: checked.data.policy.lockVersion }, key: createRequestId() };
      }
      const response = await adminWrite<BatchResult>(`/connections/${connectionId}/publications`, 'POST', session, pending.current.body, undefined, pending.current.key);
      setResult(response.data); setUncertain(false); pending.current = null;
    } catch (error) {
      setError(errorMessage(error));
      if (pending.current && (!(error instanceof AdminApiError) || error.status === 0 || error.status >= 500)) setUncertain(true);
      else pending.current = null;
    } finally { running.current = false; setBusy(false); }
  }
  const excluded = result ? result.skipped + Math.max(0, (preview?.items.length || 0) - result.items.length) : 0;
  function close() { if (busy) return; if (result || uncertain) onPublished(result ? `게시 ${result.published}건 · 제외 ${excluded}건 · 실패 ${result.failed}건` : '게시 결과를 목록에서 확인하세요.'); else onClose(); }
  return <Modal open wide title="선택 항목 게시" busy={busy} onClose={close} footerMode="replace" footer={<>
    <Button type="button" variant="secondary" disabled={busy} onClick={close}>{result ? '닫기' : '취소'}</Button>
    {!result && <Button type="submit" form={formId} disabled={busy || !preview || !policy?.etag || !hasPotential}>{busy ? '게시 중…' : uncertain ? '게시 결과 다시 확인' : '선택 게시'}</Button>}
  </>}>
    {error && <p role="alert" className={common.error}>{error}</p>}
    {preview && form ? <>
      <p className={common.help}>선택한 {candidateIds.length}건 중 게시 가능한 항목을 사용자 화면과 DCAT API에 공개합니다.</p>
      <form id={formId} className={styles.form} onSubmit={event => { event.preventDefault(); void publish(); }}>
        {!result && shared.length > 0 && <><h3 className={common.subheading}>누락된 공통 정보</h3><p className={common.help}>한 번 입력하면 이 연결에서 값이 없는 항목에 적용합니다.</p>
          {shared.includes('담당부서') && <label>담당부서<Input maxLength={200} disabled={busy || uncertain} value={form.department} onChange={event => setForm({ ...form, department: event.target.value })} /></label>}
          {shared.includes('라이선스') && <label>라이선스<Input maxLength={500} disabled={busy || uncertain} value={form.license} onChange={event => setForm({ ...form, license: event.target.value })} /></label>}
          {shared.includes('키워드') && <label>키워드<Input disabled={busy || uncertain} value={form.keywords} placeholder="쉼표로 구분" onChange={event => setForm({ ...form, keywords: event.target.value })} /></label>}
        </>}
      </form>
      {result && <output className={styles.summary}>게시 {result.published}건 · 제외 {excluded}건 · 실패 {result.failed}건</output>}
      <section className={`${common.tableWrap} ${styles.preview}`} aria-label="선택 항목 게시 확인" tabIndex={0}><table className={common.table}><caption className="sr-only">선택 항목의 필수 정보 및 게시 결과</caption><thead><tr><th scope="col">제목</th><th scope="col">분류</th><th scope="col">확인 사항</th></tr></thead><tbody>{preview.items.map(item => {
        const completed = result?.items.find(value => value.candidateId === item.candidateId);
        return <tr key={item.candidateId}><td>{item.title}</td><td>{item.categoryName || '미분류'}</td><td>{completed ? <><Badge tone={completed.status === 'PUBLISHED' ? 'success' : completed.status === 'FAILED' ? 'danger' : 'neutral'}>{completed.status === 'PUBLISHED' ? '게시 완료' : completed.status === 'FAILED' ? '게시 실패' : '제외'}</Badge><p className={styles.note}>{completed.message}</p></> : item.alreadyPublished ? <Badge tone="success">게시 완료</Badge> : item.publishable ? <Badge>게시 가능</Badge> : <><Badge tone="warning">확인 필요</Badge><p className={styles.note}>{item.missingFields.join(', ')}</p></>}</td></tr>;
      })}</tbody></table></section>
      {!result && <p className={common.help}>누락·충돌 항목은 제외하고 게시합니다. 개별 수정은 목록의 ‘변경 비교’에서 진행하세요.</p>}
    </> : !error ? <p role="status">필수 정보와 게시 상태를 확인하고 있습니다.</p> : <Button variant="secondary" onClick={() => setRefresh(value => value + 1)}>다시 확인</Button>}
  </Modal>;
}
