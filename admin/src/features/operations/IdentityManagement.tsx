import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Modal } from '../../components/Modal';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, type AdminSession, type Resource } from '../../lib/admin';
import { createRequestId } from '../../lib/requestId';
import type { components } from '../../lib/generated/api';
import styles from './Operations.module.css';
import formStyles from '../hubs/Hubs.module.css';

type Account = components['schemas']['ManagedAccount'];
type Organization = components['schemas']['ManagedOrganization'];
type Load<T> = { data: T } | { error: string } | null;
const roles = [{ code: 'PLATFORM_ADMIN', name: '전체 관리' }, { code: 'ORG_EDITOR', name: '메타데이터 편집' }, { code: 'ORG_REVIEWER', name: '검토·승인' }, { code: 'ORG_PUBLISHER', name: '게시 관리' }, { code: 'ORG_AUDITOR', name: '작업 이력 조회' }];
const roleName = (code: string) => roles.find(role => role.code === code)?.name || code;
const toggle = (values: string[], value: string) => values.includes(value) ? values.filter(item => item !== value) : [...values, value];

function useList<T>(path: string) {
  const [state, setState] = useState<Load<T[]>>(null);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<T[]>(path, controller.signal).then(({ data }) => setState({ data })).catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); });
    return () => controller.abort();
  }, [path, version]);
  return { state, reload: () => { setState(null); setVersion(value => value + 1); } };
}

export function AccountManagement({ session }: { session: AdminSession }) {
  const accounts = useList<Account>('/accounts');
  const organizations = useList<Organization>('/organizations');
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit' | 'password'; resource?: Resource<Account> } | null>(null);
  const [opening, setOpening] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  async function edit(id: string, mode: 'edit' | 'password') {
    if (opening) return;
    setOpening(true); setError('');
    try { setEditor({ mode, resource: await adminGet<Account>(`/accounts/${id}`) }); }
    catch (failure) { setError(errorMessage(failure)); }
    finally { setOpening(false); }
  }
  const organizationNames = organizations.state && 'data' in organizations.state ? organizations.state.data : [];
  return <section className={styles.panel}>
    <h2>계정 관리</h2><div className={styles.reportActions}><Button disabled={opening || !organizations.state || 'error' in organizations.state} onClick={() => { setMessage(''); setEditor({ mode: 'create' }); }}>계정 등록</Button><Button variant="secondary" onClick={() => { accounts.reload(); organizations.reload(); }}>새로고침</Button></div>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    {organizations.state && 'error' in organizations.state && <p role="alert">기관 목록: {organizations.state.error}</p>}
    {!accounts.state ? <p role="status">계정 목록을 불러오는 중입니다.</p> : 'error' in accounts.state ? <p role="alert">{accounts.state.error}</p> : accounts.state.data.length === 0 ? <p>등록된 계정이 없습니다.</p> : <section className={styles.tableWrap} aria-label="계정 목록" tabIndex={0}><table><caption className="sr-only">로컬 로그인 계정과 권한</caption><thead><tr><th scope="col">계정 ID</th><th scope="col">상태</th><th scope="col">역할</th><th scope="col">기관 범위</th><th scope="col">관리</th></tr></thead><tbody>{accounts.state.data.map(account => <tr key={account.id}><th scope="row">{account.username}{account.id === session.userId && ' (현재 계정)'}</th><td><Badge tone={account.active ? 'success' : 'neutral'}>{account.active ? '사용' : '중지'}</Badge></td><td>{account.roles.map(roleName).join(', ')}</td><td>{account.roles.includes('PLATFORM_ADMIN') ? '전체 기관' : account.organizationIds.map(id => organizationNames.find(org => org.id === id)?.name || id).join(', ') || '없음'}</td><td><div className={styles.reportActions}><Button variant="secondary" disabled={opening || !organizations.state || 'error' in organizations.state} onClick={() => void edit(account.id, 'edit')} aria-label={`${account.username} 계정 수정`}>수정</Button><Button variant="secondary" disabled={opening} onClick={() => void edit(account.id, 'password')} aria-label={`${account.username} 비밀번호 재설정`}>비밀번호 재설정</Button></div></td></tr>)}</tbody></table></section>}
    {editor && <AccountDialog key={`${editor.mode}:${editor.resource?.data.id || 'new'}`} session={session} organizations={organizationNames} mode={editor.mode} resource={editor.resource} onClose={() => setEditor(null)} onSaved={() => { setEditor(null); accounts.reload(); setMessage(editor.mode === 'password' ? '비밀번호를 재설정했습니다.' : '계정을 저장했습니다.'); }} />}
  </section>;
}

function AccountDialog({ session, organizations, mode, resource, onClose, onSaved }: { session: AdminSession; organizations: Organization[]; mode: 'create' | 'edit' | 'password'; resource?: Resource<Account>; onClose: () => void; onSaved: () => void }) {
  const id = useId();
  const [username, setUsername] = useState(resource?.data.username || '');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [active, setActive] = useState(resource?.data.active ?? true);
  const [selectedRoles, setRoles] = useState<string[]>(resource?.data.roles || ['ORG_EDITOR']);
  const [organizationIds, setOrganizationIds] = useState<string[]>(resource?.data.organizationIds || []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef(false);
  const key = useRef(createRequestId());
  const title = mode === 'create' ? '계정 등록' : mode === 'password' ? '비밀번호 재설정' : '계정 수정';
  async function submit(event: FormEvent) {
    event.preventDefault(); if (pending.current) return;
    if (mode !== 'edit' && (password !== confirmation || password.length < 8 || new TextEncoder().encode(password).length > 72 || Array.from(password).some(value => value.charCodeAt(0) < 32 || value.charCodeAt(0) === 127))) { setError('비밀번호는 8자 이상·72바이트 이하로 입력하고 확인값을 동일하게 입력하세요.'); return; }
    if (mode !== 'password' && (!selectedRoles.length || !selectedRoles.includes('PLATFORM_ADMIN') && !organizationIds.length)) { setError('역할과 기관 범위를 선택하세요.'); return; }
    if (mode !== 'create' && !resource?.etag) { setError('계정 버전을 확인하지 못했습니다. 닫은 뒤 다시 열어 주세요.'); return; }
    pending.current = true; setBusy(true); setError('');
    try {
      if (mode === 'create') await adminWrite('/accounts', 'POST', session, { username, password, active, roles: selectedRoles, organizationIds }, undefined, key.current);
      else if (mode === 'password') await adminWrite(`/accounts/${resource!.data.id}/password`, 'POST', session, { password }, resource!.etag, key.current);
      else await adminWrite(`/accounts/${resource!.data.id}`, 'PUT', session, { active, roles: selectedRoles, organizationIds }, resource!.etag);
      setPassword(''); setConfirmation(''); onSaved();
    } catch (failure) { setPassword(''); setConfirmation(''); setError(errorMessage(failure)); }
    finally { pending.current = false; setBusy(false); }
  }
  return <Modal open title={title} wide busy={busy} onClose={onClose} footer={<Button type="submit" form={id} disabled={busy}>{busy ? '저장 중' : '저장'}</Button>}>
    <form id={id} className={formStyles.form} onSubmit={event => void submit(event)}>
      <label htmlFor={`${id}-username`}><span>계정 ID{mode === 'create' && <RequiredMark />}</span><Input id={`${id}-username`} autoComplete="off" required minLength={3} maxLength={80} pattern="[a-z0-9][a-z0-9._-]{2,79}" disabled={busy || mode !== 'create'} value={username} onChange={event => setUsername(event.target.value)} /></label>
      {mode !== 'edit' && <><label htmlFor={`${id}-password`}><span>새 비밀번호<RequiredMark /></span><Input id={`${id}-password`} type="password" autoComplete="new-password" required minLength={8} maxLength={72} disabled={busy} value={password} onChange={event => setPassword(event.target.value)} /><small>8자 이상·72바이트 이하. 비밀번호는 목록이나 상세에 표시하지 않습니다.</small></label><label htmlFor={`${id}-confirm`}><span>비밀번호 확인<RequiredMark /></span><Input id={`${id}-confirm`} type="password" autoComplete="new-password" required minLength={8} maxLength={72} disabled={busy} value={confirmation} onChange={event => setConfirmation(event.target.value)} /></label></>}
      {mode !== 'password' && <><label className={formStyles.check}><input type="checkbox" checked={active} disabled={busy || resource?.data.id === session.userId} onChange={event => setActive(event.target.checked)} />계정 사용</label><fieldset disabled={busy}><legend>역할<RequiredMark /></legend>{roles.map(role => <label key={role.code} className={formStyles.check}><input type="checkbox" checked={selectedRoles.includes(role.code)} disabled={role.code === 'PLATFORM_ADMIN' && resource?.data.id === session.userId} onChange={() => setRoles(value => toggle(value, role.code))} />{role.name}</label>)}</fieldset><fieldset disabled={busy}><legend>기관 범위{!selectedRoles.includes('PLATFORM_ADMIN') && <RequiredMark />}</legend>{organizations.length ? organizations.map(organization => <label key={organization.id} className={formStyles.check}><input type="checkbox" checked={organizationIds.includes(organization.id)} disabled={!organization.active} onChange={() => setOrganizationIds(value => toggle(value, organization.id))} />{organization.name}{!organization.active && ' (사용 중지)'}</label>) : <p>등록된 기관이 없습니다. 기관 관리에서 먼저 등록하세요.</p>}{selectedRoles.includes('PLATFORM_ADMIN') && <small>전체 관리 역할은 모든 기관에 접근합니다.</small>}</fieldset></>}
      {mode !== 'create' && <p>저장하면 해당 계정의 기존 로그인이 종료됩니다.</p>}
      {error && <p role="alert" className={styles.validation}>{error}</p>}
    </form>
  </Modal>;
}

export function OrganizationManagement({ session }: { session: AdminSession }) {
  const organizations = useList<Organization>('/organizations');
  const [editor, setEditor] = useState<{ resource?: Resource<Organization> } | null>(null);
  const [opening, setOpening] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  async function edit(id: string) {
    if (opening) return;
    setOpening(true); setError('');
    try { setEditor({ resource: await adminGet<Organization>(`/organizations/${id}`) }); }
    catch (failure) { setError(errorMessage(failure)); }
    finally { setOpening(false); }
  }
  return <section className={styles.panel}><h2>기관 관리</h2><div className={styles.reportActions}><Button disabled={opening} onClick={() => { setMessage(''); setEditor({}); }}>기관 등록</Button><Button variant="secondary" onClick={organizations.reload}>새로고침</Button></div>{message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    {!organizations.state ? <p role="status">기관 목록을 불러오는 중입니다.</p> : 'error' in organizations.state ? <p role="alert">{organizations.state.error}</p> : organizations.state.data.length === 0 ? <p>등록된 기관이 없습니다.</p> : <section className={styles.tableWrap} aria-label="기관 목록" tabIndex={0}><table><caption className="sr-only">등록 기관과 사용 상태</caption><thead><tr><th scope="col">기관 코드</th><th scope="col">기관명</th><th scope="col">상태</th><th scope="col">관리</th></tr></thead><tbody>{organizations.state.data.map(organization => <tr key={organization.id}><th scope="row">{organization.code}</th><td>{organization.name}</td><td><Badge tone={organization.active ? 'success' : 'neutral'}>{organization.active ? '사용' : '중지'}</Badge></td><td><Button variant="secondary" disabled={opening} aria-label={`${organization.name} 기관 수정`} onClick={() => void edit(organization.id)}>수정</Button></td></tr>)}</tbody></table></section>}
    {editor && <OrganizationDialog key={editor.resource?.data.id || 'new'} session={session} resource={editor.resource} onClose={() => setEditor(null)} onSaved={() => { setEditor(null); organizations.reload(); setMessage('기관을 저장했습니다.'); }} />}
  </section>;
}

function OrganizationDialog({ session, resource, onClose, onSaved }: { session: AdminSession; resource?: Resource<Organization>; onClose: () => void; onSaved: () => void }) {
  const id = useId();
  const [code, setCode] = useState(resource?.data.code || '');
  const [name, setName] = useState(resource?.data.name || '');
  const [active, setActive] = useState(resource?.data.active ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef(false);
  const key = useRef(createRequestId());
  async function submit(event: FormEvent) {
    event.preventDefault(); if (pending.current) return;
    if (resource && !resource.etag) { setError('기관 버전을 확인하지 못했습니다. 닫은 뒤 다시 열어 주세요.'); return; }
    pending.current = true; setBusy(true); setError('');
    try {
      await adminWrite(resource ? `/organizations/${resource.data.id}` : '/organizations', resource ? 'PUT' : 'POST', session, { code: code.trim(), name: name.trim(), active }, resource?.etag, resource ? undefined : key.current);
      onSaved();
    } catch (failure) { setError(errorMessage(failure)); }
    finally { pending.current = false; setBusy(false); }
  }
  return <Modal open title={resource ? '기관 수정' : '기관 등록'} wide busy={busy} onClose={onClose} footer={<Button type="submit" form={id} disabled={busy}>{busy ? '저장 중' : '저장'}</Button>}><form id={id} className={formStyles.form} onSubmit={event => void submit(event)}><label htmlFor={`${id}-code`}><span>기관 코드<RequiredMark /></span><Input id={`${id}-code`} required maxLength={80} pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,79}" disabled={busy || !!resource} value={code} onChange={event => setCode(event.target.value)} /></label><label htmlFor={`${id}-name`}><span>기관명<RequiredMark /></span><Input id={`${id}-name`} required maxLength={200} disabled={busy} value={name} onChange={event => setName(event.target.value)} /></label><label className={formStyles.check}><input type="checkbox" checked={active} disabled={busy} onChange={event => setActive(event.target.checked)} />기관 사용</label>{error && <p role="alert" className={styles.validation}>{error}</p>}</form></Modal>;
}
