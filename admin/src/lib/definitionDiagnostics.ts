import type { components } from './generated/api';

type Diagnostics = components['schemas']['DefinitionDiagnostics'];
export type DefinitionFailure = { analysisRunId: string | null; diagnostics: Diagnostics | null };

export const definitionReasonLabels: Record<string, string> = {
  DOCUMENT_REJECTED: '같은 파일의 다른 항목에서 오류가 발견되어 이 항목도 생성하지 않았습니다. 파일을 수정해 다시 검증하세요.',
  FIELD_NOT_OBJECT: '필드 항목은 JSON 객체여야 합니다.',
  UNSUPPORTED_PROPERTY: '지원하지 않는 속성이 있습니다. 지원 형식에 맞게 수정하세요.',
  REQUIRED_PROPERTY_MISSING: '필수 속성이 빠져 있습니다.',
  INVALID_PROPERTY_TYPE: '속성의 값 형식을 확인하세요.',
  INVALID_FIELD_CONSTRAINT: '필드 식별자·자료형·길이·순서 등 허용 조건을 확인하세요.',
  DUPLICATE_FIELD_IDENTITY: '파일 안에서 중복된 필드 식별자나 순서를 확인하세요.',
  INVALID_DOCUMENT: 'fields 배열을 포함한 정의서 JSON 구조를 확인하세요.',
  INVALID_JSON: 'JSON 구문을 확인하세요.',
  INVALID_UTF8: 'UTF-8 인코딩으로 저장한 파일을 선택하세요.',
  EMPTY_FIELDS: 'fields 배열에 필드 정의를 입력하세요.',
  FIELD_COUNT_LIMIT: '한 파일에 최대 500개 필드를 입력하세요.',
};
const properties = new Set(['fields', 'fieldPath', 'physicalName', 'displayName', 'description', 'dataType', 'nullable', 'maxLength', 'unit', 'ordinal', 'publicVisible', 'approvedTermId', 'mappingEvidence']);
const record = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
const count = (value: unknown): value is number => Number.isInteger(value) && typeof value === 'number' && value >= 0 && value <= 500;
function pointer(value: unknown): value is string {
  if (value === '/' || value === '/fields') return true;
  if (typeof value !== 'string' || !/^\/fields\/(0|[1-9][0-9]{0,2})$/.test(value)) return false;
  return Number(value.slice('/fields/'.length)) < 500;
}

/** Only this fixed definition error is interpreted. Never render a reflected error body. */
export function safeDefinitionFailure(problem: unknown): DefinitionFailure | null {
  const source = record(problem);
  if (source?.code !== 'DEFINITION_PARSE_FAILED') return null;
  const analysisRunId = typeof source.analysisRunId === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(source.analysisRunId) ? source.analysisRunId : null;
  const value = record(source.definitionDiagnostics);
  if (!value || value.status !== 'FAILED' || value.ruleVersion !== 'explicit-field-evidence-v1'
    || !count(value.inputFieldCount) || value.generatedFieldCount !== 0 || !Array.isArray(value.unextractedFields)
    || value.unextractedFields.length < 1 || value.unextractedFields.length > 500 || !Array.isArray(value.assessments) || value.assessments.length !== 0) return { analysisRunId, diagnostics: null };
  const reasons: Diagnostics['unextractedFields'] = [];
  for (const raw of value.unextractedFields) {
    const item = record(raw);
    if (!item || !pointer(item.sourcePointer) || typeof item.reasonCode !== 'string' || !Object.hasOwn(definitionReasonLabels, item.reasonCode)
      || !Array.isArray(item.properties) || item.properties.length > properties.size
      || !item.properties.every(property => typeof property === 'string' && properties.has(property))) return { analysisRunId, diagnostics: null };
    reasons.push({ sourcePointer: item.sourcePointer, reasonCode: item.reasonCode, message: definitionReasonLabels[item.reasonCode], properties: [...new Set(item.properties as string[])] });
  }
  return { analysisRunId, diagnostics: { ruleVersion: value.ruleVersion, status: 'FAILED', inputFieldCount: value.inputFieldCount, generatedFieldCount: 0, unextractedFields: reasons, assessments: [] } };
}
