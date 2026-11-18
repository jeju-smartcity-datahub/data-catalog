const dateTimeFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'medium', hour12: false,
});

export function formatDateTime(value?: string | null): string {
  if (!value) return '미제공';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '미제공' : dateTimeFormatter.format(date);
}

const collectionLabels: Record<string, string> = {
  SUCCEEDED: '완료', PARTIAL_FAILED: '일부 실패', FAILED: '실패', QUEUED: '대기',
  RUNNING: '수집 중', RETRY_WAIT: '재시도 대기', DISABLED: '연결 중지',
  NOT_LINKED: '연결 없음', NOT_COLLECTED: '수집 이력 없음',
};

export function collectionStatusLabel(status?: string): string {
  return status ? collectionLabels[status] ?? '상태 정보 없음' : '상태 정보 없음';
}

export function recordsRange(offset: number, count: number, totalCount?: number | null): string {
  const number = (value: number) => value.toLocaleString('ko-KR');
  const range = count === 0 ? '표시 0행' : `표시 ${number(offset + 1)}–${number(offset + count)}행`;
  return typeof totalCount === 'number' && Number.isSafeInteger(totalCount) && totalCount >= 0
    ? `${range} / 전체 ${number(totalCount)}행`
    : `${range} · 전체 행 수 미제공`;
}

const numericTypes = new Set(['number', 'integer', 'int', 'int32', 'int64', 'long', 'short', 'float', 'double', 'decimal', 'numeric', 'bigint', 'smallint', 'real']);

export function isNumericColumn(dataType?: string | null): boolean {
  return typeof dataType === 'string' && numericTypes.has(dataType.trim().toLowerCase().replace(/\(.+\)$/, ''));
}

export function recordValueText(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'number') return value.toLocaleString('ko-KR', { maximumSignificantDigits: 21 });
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
