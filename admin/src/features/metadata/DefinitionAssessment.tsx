import type { components } from '../../lib/generated/api';
import { definitionReasonLabels } from '../../lib/definitionDiagnostics';
import styles from './DefinitionAssessment.module.css';
import table from './Metadata.module.css';

type Confidence = components['schemas']['RuleConfidence'];
type Diagnostics = components['schemas']['DefinitionDiagnostics'];
const checkLabels: Record<string, string> = {
  SOURCE_POINTER_VALIDATED: '추출 위치 확인',
  IDENTITY_EXPLICIT_VALIDATED: '필드 식별정보 명시·검증',
  TYPE_EXPLICIT_VALIDATED: '자료형 명시·검증',
  CONSTRAINTS_VALIDATED: '제약조건 검증',
  DESCRIPTION_NONEMPTY: '설명 입력 확인',
};

export function DefinitionConfidence({ confidence }: { confidence?: Confidence | null }) {
  if (!confidence) return <p className={styles.note}>이 기록에는 규칙 확인 점수가 저장되지 않았습니다.</p>;
  return <div className={styles.confidence} aria-label="규칙 확인 점수">
    <strong>{confidence.score} / {confidence.maxScore}점</strong>
    <span>{confidence.level === 'DIRECT_EVIDENCE_COMPLETE' ? '명시 정보 확인' : '설명 검토 필요'}</span>
    <details><summary>규칙 산식·판정 기준</summary>
      <p>규칙 버전: {confidence.ruleVersion}</p><p>{confidence.formula}</p>
      <ul>{confidence.checks.map(check => <li key={check.code}>{checkLabels[check.code] || '확인 항목'}: {check.passed ? '확인' : '검토 필요'} · {check.points}점</li>)}</ul>
      <p>이 점수는 명시된 정보의 확인 정도이며, 통계적 정확도나 성공 확률이 아닙니다. 담당자가 추출 결과를 검토해야 합니다.</p>
    </details>
  </div>;
}

export function DefinitionDiagnostics({ diagnostics }: { diagnostics?: Diagnostics | null }) {
  if (!diagnostics || diagnostics.status === 'NOT_RECORDED') return <p className={styles.note}>이 기록에는 항목별 생성 진단이 저장되지 않았습니다.</p>;
  return <section className={styles.diagnostics} aria-label="항목별 생성 진단">
    <h3>항목별 생성 진단</h3>
    <p>입력 {diagnostics.inputFieldCount}개 · 생성 {diagnostics.generatedFieldCount}개 · 규칙 {diagnostics.ruleVersion}</p>
    {diagnostics.status === 'FAILED' && <p>파일 전체 검증에 실패하여 어떤 필드도 초안에 적용하지 않았습니다. 표시된 항목과 파일을 수정한 뒤 다시 검증하세요.</p>}
    {diagnostics.unextractedFields.length > 0 ? <section className={table.tableWrap} aria-label="미생성 항목별 사유" tabIndex={0}><table className={table.table}><caption className="sr-only">미생성 항목별 사유</caption><thead><tr><th scope="col">입력 위치</th><th scope="col">미생성 사유</th><th scope="col">확인할 속성</th></tr></thead><tbody>{diagnostics.unextractedFields.map((item, index) => <tr key={`${item.sourcePointer}-${item.reasonCode}-${index}`}><td><code>{item.sourcePointer}</code></td><td>{definitionReasonLabels[item.reasonCode] || '저장된 진단 형식을 확인할 수 없습니다.'}</td><td>{item.properties.join(', ') || '파일 구조'}</td></tr>)}</tbody></table></section> : <p>미생성 항목이 없습니다.</p>}
  </section>;
}
