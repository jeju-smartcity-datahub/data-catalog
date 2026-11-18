/** Mirrors IntegrationCandidateService.mapped for display only; the server applies metadata. */
export const candidateFieldLabels: Readonly<Record<string, string>> = {
  title: '제목', description: '설명', 'details.license': '라이선스',
  'details.updateFrequency': '갱신 주기', 'details.spatialCoverage': '공간 범위',
  'details.keywords': '키워드', fields: '필드 정의', hubMetadata: '허브 메타데이터',
};
export function candidateFieldLabel(field: string): string {
  return candidateFieldLabels[field] ?? `기타 항목 (${field})`;
}
const hasIsoControl = (text: string): boolean => Array.from(text).some(char => { const code = char.codePointAt(0)!; return code <= 31 || code >= 127 && code <= 159; });
// Java Character.isWhitespace; NBSP, FIGURE SPACE and NARROW NBSP are not blank.
const isJavaBlank = (text: string): boolean => Array.from(text).every(char => {
  const code = char.codePointAt(0)!;
  return code >= 9 && code <= 13 || code >= 28 && code <= 32 || code === 0x1680 || code >= 0x2000 && code <= 0x2006 || code >= 0x2008 && code <= 0x200a || code === 0x2028 || code === 0x2029 || code === 0x205f || code === 0x3000;
});
export type MappingExclusion = { sourceKey: string; label: string; reasons: string[]; value: unknown };

export function mappingExclusions(metadata?: Record<string, unknown>): MappingExclusion[] {
  if (!metadata) return [];
  const result: MappingExclusion[] = [];
  for (const [key, label, maximum] of [['license', '라이선스', 500], ['updateInterval', '갱신 주기', 100]] as const) {
    const value = metadata[key];
    if (value == null) continue;
    const reasons: string[] = [];
    if (typeof value !== 'string') reasons.push('문자열 형식이 아닙니다.');
    else {
      if (value.length > maximum) reasons.push(`일반 필드 한도 ${maximum}자를 넘습니다 (${value.length}자).`);
      if (hasIsoControl(value)) reasons.push('제어문자가 포함되어 있습니다.');
    }
    if (reasons.length) result.push({ sourceKey: key, label, reasons, value });
  }
  const regions = metadata.targetRegions;
  if (regions != null) {
    const reasons: string[] = [];
    if (!Array.isArray(regions) || regions.some(value => typeof value !== 'string')) reasons.push('문자열 목록 형식이 아닙니다.');
    else {
      const joined = regions.join(', ');
      if (joined.length > 500) reasons.push(`지역을 쉼표로 연결한 값이 500자를 넘습니다 (${joined.length}자).`);
      if (hasIsoControl(joined)) reasons.push('제어문자가 포함되어 있습니다.');
    }
    if (reasons.length) result.push({ sourceKey: 'targetRegions', label: '공간 범위', reasons, value: regions });
  }
  const words = metadata.keywords;
  if (words != null) {
    const reasons: string[] = [];
    if (!Array.isArray(words)) reasons.push('키워드 목록 형식이 아닙니다.');
    else {
      if (words.length > 20) reasons.push(`일반 필드 한도 20개를 넘습니다 (${words.length}개).`);
      const nonText = words.filter(value => typeof value !== 'string').length;
      const blank = words.filter(value => typeof value === 'string' && isJavaBlank(value)).length;
      const long = words.filter(value => typeof value === 'string' && value.length > 100).length;
      const controls = words.filter(value => typeof value === 'string' && hasIsoControl(value)).length;
      if (nonText) reasons.push(`문자열이 아닌 키워드가 ${nonText}개 있습니다.`);
      if (blank) reasons.push(`공백 키워드가 ${blank}개 있습니다.`);
      if (long) reasons.push(`100자를 넘는 키워드가 ${long}개 있습니다.`);
      if (controls) reasons.push(`제어문자를 포함한 키워드가 ${controls}개 있습니다.`);
    }
    if (reasons.length) result.push({ sourceKey: 'keywords', label: '키워드', reasons, value: words });
  }
  return result;
}

export function candidateValue(value: unknown): { text: string; summary: string | null } {
  if (value == null) return { text: '—', summary: null };
  let decoded = value;
  if (typeof value === 'string' && (value.startsWith('[') || value.startsWith('{'))) {
    try { const parsed: unknown = JSON.parse(value); if (parsed && typeof parsed === 'object') decoded = parsed; } catch { /* descriptive text */ }
  }
  if (Array.isArray(decoded)) return { text: printableJson(decoded), summary: `${decoded.length}개 항목 · 전체 값 보기` };
  if (typeof decoded === 'object') return { text: printableJson(decoded), summary: `${Object.keys(decoded as object).length}개 속성 · 전체 값 보기` };
  const text = String(value);
  if (hasIsoControl(text)) return { text: printableJson(text), summary: `${text.length}자 · 제어문자 표기 포함 전체 값 보기` };
  return { text, summary: text.length > 160 ? `${text.length}자 · 전체 값 보기` : null };
}
function printableJson(value: unknown): string {
  return JSON.stringify(value, null, 2).replace(/[\u007f-\u009f]/g, char => `\\u${(char.codePointAt(0) ?? 0).toString(16).padStart(4, '0')}`);
}
