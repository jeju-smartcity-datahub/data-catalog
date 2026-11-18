import { useId, useRef, useState } from 'react';
import { Button, Input, RequiredMark } from '../../components/ui';
import { AdminApiError, adminGet, adminWrite, errorMessage, type AdminSession, type Resource } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import common from '../metadata/Metadata.module.css';
import styles from './Hubs.module.css';

type Schedule = components['schemas']['ScheduleView'];
type Connection = components['schemas']['ConnectionView'];
const date = (value?: string) => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '—';

export function HubCollectionSchedule({ connection, resource, session, onChanged }: {
  connection: Connection; resource: Resource<Schedule>; session: AdminSession; onChanged: (message: string) => void;
}) {
  const id = useId();
  const schedule = resource.data;
  const [saved, setSaved] = useState(resource);
  const [enabled, setEnabled] = useState(schedule.enabled === true);
  const [interval, setInterval] = useState(String(schedule.intervalSeconds ?? 3600));
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const running = useRef(false);
  const canConfigure = session.authorities.includes('ROLE_PLATFORM_ADMIN');
  const canEnable = connection.enabled && connection.adapterAvailable && connection.capabilities.includes('DISCOVER_DATASETS') && schedule.workerEnabled === true;
  const seconds = Number(interval);
  const valid = Number.isInteger(seconds) && seconds >= 60 && seconds <= 86400;
  const dirty = enabled !== (saved.data.enabled === true) || seconds !== saved.data.intervalSeconds;
  function adopt(value: Resource<Schedule>) {
    setSaved(value); setEnabled(value.data.enabled === true); setInterval(String(value.data.intervalSeconds ?? 3600)); setUncertain(false);
  }
  async function reload() {
    if (running.current || (dirty && !uncertain && !window.confirm('작성 중인 설정을 저장된 값으로 바꾸시겠습니까?'))) return;
    running.current = true; setBusy(true); setError('');
    try { adopt(await adminGet<Schedule>(`/connections/${connection.id}/schedule`)); onChanged('저장된 정기 수집 설정을 불러왔습니다.'); }
    catch (error) { setError(errorMessage(error)); }
    finally { running.current = false; setBusy(false); }
  }
  async function save() {
    if (running.current || !canConfigure || !valid || !saved.etag || uncertain || (enabled && !canEnable)) return;
    running.current = true; setBusy(true); setError('');
    try {
      adopt(await adminWrite<Schedule>(`/connections/${connection.id}/schedule`, 'PUT', session, { enabled, intervalSeconds: seconds }, saved.etag));
      onChanged(enabled ? '정기 수집을 저장했습니다. 첫 작업은 곧 접수될 수 있습니다. 수집 이력에서 결과를 확인하세요.' : '정기 수집을 중지했습니다. 이미 접수된 작업은 이력에서 확인하세요.');
    } catch (error) {
      if (error instanceof AdminApiError && error.status === 0) setUncertain(true);
      setError(errorMessage(error));
    } finally { running.current = false; setBusy(false); }
  }
  return <section className={styles.panel} aria-labelledby={`${id}-heading`}>
    <div className={styles.heading}><div><h2 id={`${id}-heading`}>정기 메타데이터 수집</h2><p>설정한 주기로 수집합니다. 게시는 게시 설정에 따릅니다.</p></div></div>
    <div className={styles.history}>
      <dl className={styles.properties}>
        <div><dt>현재 설정</dt><dd>{schedule.enabled ? '정기 수집 켜짐' : '정기 수집 꺼짐'} · {schedule.intervalSeconds ?? 3600}초 간격</dd></div>
        <div><dt>다음 실행 예정</dt><dd>{date(schedule.nextRunAt)} (한국 시간)</dd></div>
        <div><dt>최근 접수 시각</dt><dd>{date(schedule.lastRequestedAt)} (한국 시간)</dd></div>
        {schedule.lastErrorCode && <div><dt>최근 접수 오류</dt><dd>{schedule.lastErrorCode}</dd></div>}
      </dl>
      <p id={`${id}-help`} className={common.help}>활성화하거나 주기를 바꾸면 첫 수집이 곧 접수될 수 있습니다. 이후 접수 시각부터 설정한 초 간격으로 실행합니다. 서버 중단 중에는 실행되지 않으며 게시된 데이터는 그대로 유지됩니다.</p>
      {!canConfigure ? <p className={common.help}>정기 수집 설정은 플랫폼 관리자가 변경합니다.</p> : <form className={styles.scheduleForm} onSubmit={event => { event.preventDefault(); void save(); }}>
        <label className={styles.check}><input type="checkbox" aria-describedby={`${id}-help ${!canEnable ? `${id}-unavailable` : ''}`.trim()} checked={enabled} disabled={busy || uncertain || (!canEnable && !enabled)} onChange={event => setEnabled(event.target.checked)} />정기 수집 사용</label>
        <label htmlFor={`${id}-interval`}><span>수집 주기(초)<RequiredMark /></span><Input id={`${id}-interval`} type="number" min={60} max={86400} step={1} required value={interval} disabled={busy || uncertain} onChange={event => setInterval(event.target.value)} /></label>
        {!canEnable && <p id={`${id}-unavailable`} className={common.help}>연결 활성화와 서버 수집 작업자 준비가 필요합니다. 작업자가 꺼져 있으면 설치 담당자에게 설정 재적용을 요청하세요.</p>}
        {error && <p role="alert" className={common.error}>{error}</p>}
        {uncertain && <p className={common.help}>저장 응답을 확인하지 못했습니다. 저장된 설정을 다시 조회하세요.</p>}
        <div className={styles.actions}><Button type="button" variant="secondary" disabled={busy} onClick={() => void reload()}>저장된 설정 다시 조회</Button><Button type="submit" disabled={busy || uncertain || !dirty || !valid || !saved.etag || (enabled && !canEnable)}>{busy ? '처리 중…' : '정기 수집 설정 저장'}</Button></div>
      </form>}
    </div>
  </section>;
}
