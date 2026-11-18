const allowed = ['제목', '설명', '제공기관', '공개등급', '담당부서', '라이선스', '키워드'] as const;
const prefix = '등록 필수항목을 입력하세요: ';
const suffix = '. 초안은 보관할 수 있습니다.';

/** Only reconstruct the fixed server validation grammar; never display arbitrary problem details. */
export function safeRequiredMetadataMessage(status: number, problem: unknown): string | null {
  if (status !== 422 || !problem || typeof problem !== 'object' || Array.isArray(problem)) return null;
  const body = problem as Record<string, unknown>;
  if (body.code !== 'REQUIRED_METADATA_MISSING' || typeof body.detail !== 'string' || body.detail.length > 300) return null;
  const detail = body.detail;
  if (!detail.startsWith(prefix) || !detail.endsWith(suffix)) return null;
  const labels = detail.slice(prefix.length, -suffix.length).split(', ');
  if (!labels.length || labels.length > allowed.length || new Set(labels).size !== labels.length || labels.some(label => !allowed.some(item => item === label))) return null;
  const selected = allowed.filter(label => labels.includes(label));
  return prefix + selected.join(', ') + suffix;
}
