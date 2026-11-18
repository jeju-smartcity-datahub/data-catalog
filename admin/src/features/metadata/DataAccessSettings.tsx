import { useEffect, useRef, useState } from 'react';
import { Badge, Button, Input, RequiredMark } from '../../components/ui';
import { adminGet, adminWrite, errorMessage, hasAuthority, type AdminSession, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import styles from './Metadata.module.css';
import { createRequestId } from '../../lib/requestId';

type Settings = components['schemas']['DataAccessView'];
type InputData = components['schemas']['DataAccessInput'];
type Connection = components['schemas']['ConnectionView'];
type Check = components['schemas']['DataAccessCheck'];
const toInput = (data: Settings): InputData => ({ connectionId: data.connectionId || '', enabled: data.enabled, mode: data.mode, queryPath: data.queryPath, scopeField: data.scopeField, scopeValue: data.scopeValue, scopeParameter: data.scopeParameter, fixedQuery: data.fixedQuery });

export function DataAccessSettings({ datasetId, organizationId, session, onChecked }: {
  datasetId: string; organizationId: string; session: AdminSession; onChecked: () => void;
}) {
  const [resource, setResource] = useState<Resource<Settings> | null>(null);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [form, setForm] = useState<InputData | null>(null);
  const [busy, setBusy] = useState<'saving' | 'checking' | null>(null);
  const running = useRef(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [checkNotice, setCheckNotice] = useState('');
  const [checkError, setCheckError] = useState('');
  const [retry, setRetry] = useState(0);
  const editable = hasAuthority(session, 'ORG_EDITOR') && hasAuthority(session, 'ORG_PUBLISHER');
  const canReadConnections = hasAuthority(session, 'ORG_EDITOR') || hasAuthority(session, 'ORG_REVIEWER');
  const path = `/datasets/${encodeURIComponent(datasetId)}/data-access`;
  function adopt(value: Resource<Settings>) {
    setResource(value);
    setForm(toInput(value.data));
  }
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([adminGet<Settings>(path, controller.signal),
      canReadConnections ? adminGet<Connection[]>(`/connections?organizationId=${encodeURIComponent(organizationId)}`, controller.signal) : Promise.resolve({ data: [] as Connection[], etag: null })])
      .then(([value, list]) => { if (!controller.signal.aborted) { adopt(value); setError(''); setConnections(list.data.filter(item => item.enabled && item.endpointConfigured)); } })
      .catch(reason => { if (!controller.signal.aborted) setError(errorMessage(reason)); });
    return () => controller.abort();
  }, [path, organizationId, retry, canReadConnections]);
  const dirty = !!form && !!resource && JSON.stringify(form) !== JSON.stringify(toInput(resource.data));
  const selectedConnection = connections.find(connection => connection.id === form?.connectionId);
  async function saveAndCheck() {
    if (!form || !resource || running.current || !editable) return;
    running.current = true; setError(''); setCheckError(''); setCheckNotice('');
    let saved = resource;
    try {
      if (dirty) {
        setBusy('saving'); setNotice('');
        saved = await adminWrite<Settings>(path, 'PUT', session, form, resource.etag);
        adopt(saved); setNotice(form.enabled ? '설정 저장 완료' : '설정 저장 완료 · 데이터 조회 중지');
        onChecked();
      }
      if (!form.enabled) return;
      setBusy('checking');
      try {
        const checked = await adminWrite<Check>(`${path}/check`, 'POST', session, undefined, saved.etag, createRequestId());
        adopt({ data: checked.data.settings, etag: checked.etag });
        setCheckNotice(`조회 연결 확인 완료 · ${checked.data.recordCount}개 응답 확인`); onChecked();
      } catch (reason) { setCheckError(`조회 연결 실패 · ${errorMessage(reason)}`); }
    } catch (reason) { setError(`설정 저장 실패 · ${errorMessage(reason)}`); }
    finally { running.current = false; setBusy(null); }
  }
  return <section aria-labelledby="records-settings-heading">
    <h3 id="records-settings-heading" className={styles.subheading}>데이터 조회 연결</h3>
    {error && <div role="alert" className={styles.error}>{error}{!form && <Button type="button" variant="secondary" onClick={() => setRetry(value => value + 1)}>다시 조회</Button>}</div>}
    {notice && <p role="status" className={styles.message}>{notice}</p>}
    {checkNotice && <p role="status" className={styles.message}>{checkNotice}</p>}
    {checkError && <p role="alert" className={styles.error}>{checkError}</p>}
    {!form || !resource ? !error && <p role="status">조회 설정을 불러오고 있습니다.</p> : <>
      <p className={styles.summary}><Badge tone={resource.data.ready ? 'success' : 'neutral'}>{!resource.data.configured && resource.data.connectionId ? '수집 허브 자동 연결' : resource.data.ready ? '조회 가능' : resource.data.enabled ? '조회 시험 필요' : '조회 중지'}</Badge>
        {resource.data.checkedAt && <time dateTime={resource.data.checkedAt}>{new Date(resource.data.checkedAt).toLocaleString('ko-KR')} 확인</time>}</p>
      <fieldset disabled={!!busy || !editable} className={styles.formGrid}>
        <legend className="sr-only">데이터셋 조회 설정</legend>
        <label className={styles.full}><span><input type="checkbox" checked={form.enabled} onChange={event => setForm({ ...form, enabled: event.target.checked })} /> 사용자 데이터 조회 제공</span></label>
        <label><span>허브 연결<RequiredMark /></span><select className={styles.select} value={form.connectionId} onChange={event => setForm({ ...form, connectionId: event.target.value })}><option value="">연결 선택</option>{form.connectionId && !connections.some(connection => connection.id === form.connectionId) && <option value={form.connectionId}>등록된 연결</option>}{connections.map(connection => <option key={connection.id} value={connection.id}>{connection.name}</option>)}</select></label>
        <label><span>조회 방식<RequiredMark /></span><select className={styles.select} value={form.mode} onChange={event => setForm({ ...form, mode: event.target.value, scopeParameter: event.target.value === 'NGSI_LD' ? 'q' : 'datasetId', ...(event.target.value === 'KETI_DATASET' ? { queryPath: '/entities', scopeField: 'datasetId' } : {}) })}><option value="KETI_DATASET">KETI 데이터셋 조회</option><option value="NGSI_LD">NGSI-LD 속성 조건</option><option value="JSON_ARRAY">JSON 배열</option></select></label>
        <label className={styles.full}><span>조회 경로<RequiredMark /></span><Input value={form.queryPath} maxLength={500} onChange={event => setForm({ ...form, queryPath: event.target.value })} /></label>
        <label><span>데이터셋 식별 필드<RequiredMark /></span><Input value={form.scopeField} maxLength={200} onChange={event => setForm({ ...form, scopeField: event.target.value })} /></label>
        <label><span>데이터셋 식별값<RequiredMark /></span><Input value={form.scopeValue} maxLength={300} onChange={event => setForm({ ...form, scopeValue: event.target.value })} /></label>
        {form.mode === 'JSON_ARRAY' && <label><span>조회 조건 이름<RequiredMark /></span><Input value={form.scopeParameter} maxLength={80} onChange={event => setForm({ ...form, scopeParameter: event.target.value })} /></label>}
        <label>데이터 유형(type)<Input value={form.fixedQuery?.type || ''} maxLength={1000} onChange={event => { const fixedQuery = { ...form.fixedQuery }; if (event.target.value) fixedQuery.type = event.target.value; else delete fixedQuery.type; setForm({ ...form, fixedQuery }); }} /></label>
      </fieldset>
      {selectedConnection && <dl className={styles.summary}><div><dt>메타데이터 수집 주소</dt><dd>{selectedConnection.metadataEndpoint || '미등록'}</dd></div><div><dt>데이터 조회 기본 주소</dt><dd>{selectedConnection.recordsEndpoint || '미등록'}</dd></div></dl>}
      <p className={styles.help}>데이터 조회 기본 주소에 조회 경로를 연결합니다. 식별값이 일치하는 데이터의 공개 필드만 제공합니다.</p>
      {editable && <div className={styles.actions}><Button type="button" disabled={!!busy || !form.connectionId || !form.scopeValue.trim() || (!form.enabled && !dirty)} onClick={saveAndCheck}>{busy === 'saving' ? '설정 저장 중…' : busy === 'checking' ? '조회 시험 중…' : form.enabled ? dirty ? '저장 후 조회 시험' : '다시 검사' : '설정 저장'}</Button></div>}
    </>}
  </section>;
}
