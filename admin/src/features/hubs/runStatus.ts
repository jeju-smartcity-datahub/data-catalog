import type { components } from '../../lib/generated/api';
export type Run = components['schemas']['RunView'];
export const activeRun = (run: Pick<Run, 'status'>) => ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(run.status);
export function completedSince(previous: Map<string, string> | null, runs: Pick<Run, 'id' | 'status'>[]) {
  return previous !== null && runs.some(run => !activeRun(run) && previous.get(run.id) !== run.status);
}
export const runTime = (value?: string | null) => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '—';
export const failureLabels: Record<string, string> = {
  ADAPTER_UNAVAILABLE: '서버에 해당 연동 기능이 등록되어 있지 않습니다.', CAPABILITY_UNSUPPORTED: '이 허브가 해당 기능을 지원하지 않습니다.',
  SECRET_STORE_UNAVAILABLE: '서버 비밀 저장소 연결을 확인하세요.', SOURCE_NOT_APPROVED: '서버의 연결 허용 목록에 없는 주소입니다. 설치 담당자에게 주소·포트 등록을 요청하세요.',
  SOURCE_ITEMS_FAILED: '일부 항목을 수집하지 못했습니다. 상세 결과를 확인하세요.', AUTHENTICATION_FAILED: '허브 인증 설정을 확인하세요.',
  RATE_LIMITED: '허브 호출 제한으로 다음 시각에 다시 시도합니다.', TIMEOUT: '허브 응답 시간이 초과되었습니다. 서버 상태와 포트를 확인한 뒤 다시 검사하세요.',
  SOURCE_UNAVAILABLE: '허브에 연결할 수 없습니다. 서버 상태와 포트를 확인하세요.', CHECKPOINT_CYCLE: '허브가 같은 목록을 반복 응답하여 수집을 중단했습니다.',
  PAGE_LIMIT_EXCEEDED: '설정된 최대 수집 페이지에 도달했습니다.', INVALID_SCHEMA: '허브 응답 형식을 확인하세요.',
};
export function runLabel(run: Run) {
  if (run.status === 'PARTIAL_FAILED') return '일부 항목 실패';
  if (run.status === 'FAILED') return '실패';
  if (run.status === 'SUCCEEDED') return run.kind === 'CHECK' ? '연결 확인 완료' : '수집 완료';
  if (run.status === 'RETRY_WAIT') return '재시도 준비';
  const phases: Record<string, string> = { PREPARING: run.kind === 'CHECK' ? '검사 시작 준비' : '수집 시작 준비', CHECKING_CONNECTION: '연결 확인 중', FETCHING_LIST: '데이터 목록 조회 중', FETCHING_DETAILS: '상세·필드 조회 중', SAVING_RESULTS: '수집 결과 저장 중' };
  return phases[run.phase] || (run.status === 'QUEUED' ? phases.PREPARING : '처리 중');
}
export function runDelay(run: Run, now: number) {
  const last = Date.parse(run.progressAt || run.startedAt || run.createdAt);
  if (!activeRun(run) || run.status === 'RETRY_WAIT' || !Number.isFinite(last) || now - last < 60000) return '';
  return run.status === 'QUEUED' ? '작업 시작이 지연되고 있습니다. 서버 작업자 실행 상태를 확인하세요.' : '최근 1분 동안 새 진행 정보가 없습니다. 허브 응답 또는 서버 처리 상태를 확인하세요.';
}
