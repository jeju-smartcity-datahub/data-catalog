import { Badge } from '../../components/ui';
import { activeRun, failureLabels, runDelay, runLabel, runTime, type Run } from './runStatus';
import common from '../metadata/Metadata.module.css';
import styles from './Hubs.module.css';
import { HubAlert } from './HubAlert';

export function RunProgress({ run, observedAt, onShowCollected }: { run: Run; observedAt: number; onShowCollected?: () => void }) {
  const delay = runDelay(run, observedAt);
  return <section className={styles.progress} aria-label="현재 작업 상태">
    <p role="status"><strong>{run.kind === 'CHECK' ? '연결 검사' : '메타데이터 수집'}</strong> <Badge>{runLabel(run)}</Badge></p>
    {run.kind !== 'CHECK' && <p>처리 {run.processedCount ?? 0}{run.totalCount != null ? ` / ${run.totalCount}` : ''}건 · 저장 {run.savedCount ?? 0} · 변경 없음 {run.unchangedCount ?? 0} · 실패 {run.failedCount ?? 0}</p>}
    <p className={common.help}>요청 {runTime(run.createdAt)} · 시작 {runTime(run.startedAt)} · 마지막 진행 {runTime(run.progressAt)}{run.finishedAt && ` · 종료 ${runTime(run.finishedAt)}`}</p>
    {run.status === 'RETRY_WAIT' && <p>{run.attempt}회 시도 · 다음 시도 {runTime(run.nextAttemptAt)}</p>}
    {(run.errorCode || ['FAILED', 'PARTIAL_FAILED'].includes(run.status)) && <HubAlert title={run.status === 'RETRY_WAIT' ? '다시 연결을 시도합니다' : run.kind === 'CHECK' ? '연결 검사 실패' : run.status === 'PARTIAL_FAILED' ? '일부 데이터 수집 실패' : '메타데이터 수집 실패'} failure={{ message: failureLabels[run.errorCode] || '허브 요청을 처리하지 못했습니다.', code: run.errorCode }} action={run.status === 'RETRY_WAIT' ? '표시된 다음 시도 시각에 자동으로 다시 실행합니다.' : run.kind === 'CHECK' ? '연결 주소·포트와 허브 상태를 확인한 뒤 연결 검사를 다시 실행하세요.' : '수집 이력의 결과 보기에서 실패 항목을 확인하세요.'} />}
    {delay && <p className={common.message}>{delay}</p>}
    {activeRun(run) && <p className={common.help}>작업 상태를 자동으로 확인합니다.</p>}
    {onShowCollected && run.kind !== 'CHECK' && !activeRun(run) && <a href={`#collected-data-${run.connectionId}`} onClick={event => { event.preventDefault(); onShowCollected(); }}>수집한 데이터 보기</a>}
  </section>;
}
