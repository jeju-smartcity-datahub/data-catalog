import { createRequestId } from '../../lib/requestId';
import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from '../../components/Modal';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import shared from '../metadata/Metadata.module.css';
import styles from './DiscoveryTaxonomies.module.css';

type Lookup = components['schemas']['DiscoveryLookup'];
type Kind = 'REGION' | 'HUB';
const kindName = (kind: Kind) => kind === 'REGION' ? '지역' : '허브';

/** Public discovery labels, separate from integration connections and their secrets. */
export function DiscoveryTaxonomies({ session }: { session: AdminSession }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  if (!hasAuthority(session, 'PLATFORM_ADMIN')) return null;
  return <section className={`${shared.panel} ${styles.section}`} aria-label="공개 탐색 분류 설정">
    <div className={shared.panelHeading}><h2>공개 탐색 분류</h2><Button variant="secondary" aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(value => !value)}>{open ? '설정 접기' : '지역·허브 분류 관리'}</Button></div>
    <p className={shared.help}>공개 목록의 지역·허브 검색 조건에 사용할 분류명입니다. 허브 접속 주소·인증 정보·수집 연결은 이곳에서 설정하지 않습니다.</p>
    <div id={panelId} hidden={!open}>{open && <LookupList session={session} />}</div>
  </section>;
}

function LookupList({ session }: { session: AdminSession }) {
  const [kind, setKind] = useState<Kind>('REGION');
  const [items, setItems] = useState<Lookup[] | null>(null);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [editor, setEditor] = useState<{ id?: string; kind: Kind } | null>(null);
  const [notice, setNotice] = useState('');
  const noticeRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Lookup[]>(`/discovery-lookups?kind=${kind}`, controller.signal).then(({ data }) => { setItems(data); setError(''); })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [kind, refresh]);
  useEffect(() => { if (notice) noticeRef.current?.focus(); }, [notice]);
  const reload = () => { setItems(null); setError(''); setRefresh(value => value + 1); };
  const changeKind = (value: Kind) => { if (value !== kind) { setItems(null); setError(''); setNotice(''); setKind(value); } };
  return <div className={styles.content}>
    <div className={styles.toolbar}><fieldset className={styles.kinds}><legend className="sr-only">공개 분류 유형</legend>
      {(['REGION', 'HUB'] as const).map(value => <Button key={value} variant={value === kind ? 'primary' : 'secondary'} aria-pressed={value === kind} onClick={() => changeKind(value)}>{kindName(value)}</Button>)}
    </fieldset><div className={styles.actions}><Button variant="secondary" onClick={reload}>분류 새로고침</Button><Button disabled={!!items && items.length >= 500} onClick={() => setEditor({ kind })}>{kindName(kind)} 분류 추가</Button></div></div>
    {notice && <p ref={noticeRef} tabIndex={-1} role="status" className={shared.message}>{notice}</p>}
    <h3>{kindName(kind)} 분류{items && ` ${items.length.toLocaleString('ko-KR')}개`}</h3>
    <p className={shared.help}>활성 분류만 새 메타데이터의 선택지에 표시됩니다. 참조 수는 메타데이터 버전의 연결 수이며 데이터셋 개수와 다를 수 있습니다. 사용 중인 분류는 비활성화할 수 없습니다.</p>
    {error ? <div className={shared.error} role="alert"><p>{error}</p><Button variant="secondary" onClick={reload}>분류 목록 다시 시도</Button></div> : items ? items.length ? <>
      <section className={shared.tableWrap} aria-label={`${kindName(kind)} 공개 분류 표`} tabIndex={0}><table className={`${shared.table} ${styles.table}`}>
        <caption className="sr-only">공개 탐색용 {kindName(kind)} 분류와 참조 버전 수</caption>
        <thead><tr><th scope="col">분류명</th><th scope="col">코드</th><th scope="col">상태</th><th scope="col">참조 버전 수</th><th scope="col">관리</th></tr></thead>
        <tbody>{items.map(item => <tr key={item.id}><td>{item.name}</td><td>{item.code}</td><td><Badge tone={item.active ? 'success' : 'neutral'}>{item.active ? '활성' : '비활성'}</Badge></td><td>{item.referenceCount.toLocaleString('ko-KR')}건</td><td><Button variant="secondary" onClick={() => setEditor({ id: item.id, kind })} aria-label={`${item.name} 공개 분류 수정`}>수정</Button></td></tr>)}</tbody>
      </table></section>{items.length >= 500 && <p className={shared.help}>종류별 등록 한도인500개에 도달했습니다. 기존 분류의 이름과 상태를 관리할 수 있습니다.</p>}
    </> : <p className={shared.state}>등록된 {kindName(kind)} 공개 분류가 없습니다.</p> : <p className={shared.state} role="status">{kindName(kind)} 분류를 불러오는 중입니다.</p>}
    {editor && <LookupEditor session={session} target={editor} onClose={() => setEditor(null)} onSaved={message => { setEditor(null); setNotice(message); reload(); }} />}
  </div>;
}

function LookupEditor({ session, target, onClose, onSaved }: { session: AdminSession; target: { id?: string; kind: Kind }; onClose: () => void; onSaved: (message: string) => void }) {
  const [resource, setResource] = useState<Resource<Lookup> | null>(null);
  const [form, setForm] = useState({ code: '', name: '', active: true });
  const [baseline, setBaseline] = useState(JSON.stringify(form));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [deactivating, setDeactivating] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const running = useRef(false);
  const key = useRef(createRequestId());
  const errorRef = useRef<HTMLDivElement>(null);
  const formId = useId(); const codeHintId = useId(); const referenceHintId = useId(); const descriptionId = useId();
  const dirty = JSON.stringify(form) !== baseline;
  const used = !!resource?.data.referenceCount;
  useEffect(() => {
    if (!target.id) return;
    const controller = new AbortController();
    adminGet<Lookup>(`/discovery-lookups/${target.id}`, controller.signal).then(result => {
      setResource(result); const value = { code: result.data.code, name: result.data.name, active: result.data.active }; setForm(value); setBaseline(JSON.stringify(value)); setError('');
    }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [target.id, refresh]);
  useEffect(() => { if (error && !deactivating) errorRef.current?.focus(); }, [error, deactivating]);
  useEffect(() => {
    if (!dirty) return;
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent);
  }, [dirty]);
  const close = () => { if (!busy && (!dirty || window.confirm('저장하지 않은 변경사항을 버리시겠습니까?'))) onClose(); };
  const reload = () => { if (!dirty || window.confirm('입력 내용을 버리고 최신 내용을 불러오시겠습니까?')) { setResource(null); setError(''); setRefresh(value => value + 1); } };
  async function save(deactivate = false) {
    if (running.current) return;
    if (target.id && !resource?.etag) { setError('버전을 확인하지 못했습니다. 최신 내용을 다시 불러와 주세요.'); return; }
    running.current = true; setBusy(true); setError('');
    try {
      await adminWrite<Lookup>(target.id ? `/discovery-lookups/${target.id}` : '/discovery-lookups', deactivate ? 'DELETE' : target.id ? 'PATCH' : 'POST', session,
        deactivate ? undefined : target.id ? { name: form.name.trim(), active: form.active } : { kind: target.kind, code: form.code, name: form.name.trim(), active: form.active }, resource?.etag, key.current);
      onSaved(deactivate ? '공개 분류를 비활성화했습니다. 기존 이력은 유지됩니다.' : '공개 탐색 분류를 저장했습니다.');
    } catch (error) { setError(errorMessage(error)); } finally { running.current = false; setBusy(false); }
  }
  return <><Modal open title={`${kindName(target.kind)} 공개 분류 ${target.id ? '수정' : '추가'}`} className={styles.editorModal} closeIconSrc="/assets/classification-close.svg" busy={busy} onClose={close} descriptionId={descriptionId} footerMode="replace" footer={<>
    <Button variant="secondary" data-initial-focus="true" disabled={busy} onClick={close}>취소</Button>{(!target.id || resource) && <Button type="submit" form={formId} disabled={busy || (!!target.id && !dirty)}>{busy ? '저장 중…' : '저장'}</Button>}
  </>}>
    <p id={descriptionId} className={shared.help}>공개 검색에 사용할 분류명입니다. 실제 허브 연결이나 수집 설정을 생성하지 않습니다.</p>
    {error && <div ref={errorRef} tabIndex={-1} role="alert" className={shared.error}><p>{error}</p>{target.id && <Button variant="secondary" onClick={reload}>최신 분류 확인</Button>}</div>}
    {busy && <p role="status" className="sr-only">공개 분류 변경을 처리하고 있습니다.</p>}
    {!target.id || resource ? <form id={formId} className={styles.form} onSubmit={event => { event.preventDefault(); void save(); }}>
      <label><span>분류 코드{!target.id && <RequiredMark />}</span><Input required readOnly={!!target.id} disabled={busy} maxLength={80} pattern="[a-z0-9][a-z0-9_\-]{0,79}" aria-describedby={codeHintId} value={form.code} onChange={event => setForm(value => ({ ...value, code: event.target.value }))} /></label>
      <p id={codeHintId} className={shared.help}>소문자 영문 또는 숫자로 시작하는80자 이내 코드입니다. 소문자 영문·숫자·밑줄·하이픈을 사용할 수 있으며 생성 후 변경할 수 없습니다.</p>
      <label><span>분류명<RequiredMark /></span><Input required maxLength={200} pattern=".*\S.*" disabled={busy} value={form.name} onChange={event => setForm(value => ({ ...value, name: event.target.value }))} /></label>
      <label>상태<select className={shared.select} disabled={busy} aria-describedby={referenceHintId} value={String(form.active)} onChange={event => setForm(value => ({ ...value, active: event.target.value === 'true' }))}><option value="true">활성</option><option value="false" disabled={used}>비활성</option></select></label>
      <p id={referenceHintId} className={shared.help}>{resource && `참조 버전 ${resource.data.referenceCount.toLocaleString('ko-KR')}건. `}사용 중인 분류는 이름을 바꿀 수 있지만 비활성화할 수 없습니다. 이미 게시된 버전의 분류명은 유지됩니다.</p>
      {resource && <div><Button variant="danger" type="button" disabled={busy || dirty || used || !resource.data.active} aria-describedby={referenceHintId} onClick={() => { setError(''); setDeactivating(true); }}>분류 비활성화</Button></div>}
    </form> : !error && <p role="status">공개 분류 정보를 불러오는 중입니다.</p>}
  </Modal><ConfirmDialog open={deactivating} title="공개 분류 비활성화" confirmLabel="비활성화" busyLabel="비활성화 중…" busy={busy} error={error} onCancel={() => setDeactivating(false)} onConfirm={() => void save(true)} description={<><strong>{form.name}</strong> 분류를 비활성화하시겠습니까? 기존 이력은 보존되며 새 메타데이터 선택지에서 제외됩니다.</>} /></>;
}
