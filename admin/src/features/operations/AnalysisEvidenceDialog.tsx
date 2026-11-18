import { useEffect, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Button } from '../../components/ui';
import { adminGet, errorMessage } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { dateTime } from './dateTime';
import { DefinitionConfidence, DefinitionDiagnostics } from '../metadata/DefinitionAssessment';
import styles from './Operations.module.css';

type AnalysisRun = components['schemas']['AnalysisRun'];
type Evidence = components['schemas']['AnalysisEvidence'];
type Diagnostics = components['schemas']['DefinitionDiagnostics'];
export function AnalysisEvidenceDialog({ run, onClose }: { run: AnalysisRun; onClose: () => void }) {
  const mapping = run.kind === 'FIELD_MAPPING';
  const definition = run.kind === 'DEFINITION_IMPORT';
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<{ rows: Evidence[] } | { error: string } | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [diagnosticsError, setDiagnosticsError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    adminGet<Evidence[]>(`/analysis-runs/${run.id}/evidence`, controller.signal)
      .then(({ data }) => setState({ rows: data }))
      .catch(error => { if (!controller.signal.aborted) setState({ error: errorMessage(error) }); });
    if (definition) adminGet<Diagnostics>(`/analysis-runs/${run.id}/definition-diagnostics`, controller.signal)
      .then(({ data }) => { setDiagnostics(data); setDiagnosticsError(''); })
      .catch(error => { if (!controller.signal.aborted) setDiagnosticsError(errorMessage(error)); });
    return () => controller.abort();
  }, [run.id, attempt, definition]);
  return <Modal open title="확인 근거" wide size="evidence" onClose={onClose}>
    <h3>{dateTime(run.startedAt)} · {run.kind === 'DEFINITION_IMPORT' ? '정의서 필드 추출' : run.kind === 'DEFINITION_EXPORT' ? '정의서 내보내기' : '필드 표준 매핑'}</h3>
    <p className={styles.note}>처리 당시의 필드와 근거입니다. 현재 초안의 값과 다를 수 있습니다.</p>
    {run.status === 'FAILED' && <p className={styles.failureReason}>정의서 형식을 확인해 다시 시도하세요. 파일 본문과 내부 오류 메시지는 보관하지 않습니다. 오류 코드: {run.errorCode || '확인 필요'}</p>}
    <dl className={styles.details}><dt>처리기 버전</dt><dd>{run.engineVersion}</dd><dt>원본 SHA-256</dt><dd>{run.sourceHash}</dd></dl>
    {definition && (diagnosticsError ? <div className={styles.state} role="alert"><p>{diagnosticsError}</p><Button variant="secondary" onClick={() => { setDiagnostics(null); setDiagnosticsError(''); setAttempt(value => value + 1); }}>항목별 진단 다시 불러오기</Button></div> : diagnostics ? <DefinitionDiagnostics diagnostics={diagnostics} /> : <p role="status">항목별 생성 진단을 불러오는 중입니다.</p>)}
    {!state ? <p className={styles.state} role="status">근거를 불러오는 중입니다.</p> : 'error' in state ? <div className={styles.state} role="alert"><p>{state.error}</p><Button variant="secondary" onClick={() => { setState(null); setAttempt(value => value + 1); }}>다시 시도</Button></div> : <>
      <p>총 {state.rows.length}개</p>
      {state.rows.length ? <section className={styles.tableWrap} aria-label="분석 근거 표" tabIndex={0}><table><caption className="sr-only">필드와 처리 근거</caption><thead><tr><th scope="col">필드명</th>{mapping ? <><th scope="col">필드 표시명</th><th scope="col">승인 용어 식별자</th></> : <th scope="col">추출 위치</th>}<th scope="col">확인 근거</th>{definition && <th scope="col">규칙 확인 점수</th>}</tr></thead><tbody>{state.rows.map((row, index) => <tr key={`${row.fieldPath}-${index}`}><td className={styles.identifier}>{row.field.physicalName}<br />{row.field.dataType}</td><td className={styles.identifier}>{mapping ? row.field.displayName : row.sourcePointer}</td>{mapping && <td className={styles.identifier}>{row.field.approvedTermId || '미연결'}</td>}<td className={styles.identifier}>{row.explanation}<br />{mapping ? row.field.mappingEvidence : row.field.description}</td>{definition && <td><DefinitionConfidence confidence={row.confidence} /></td>}</tr>)}</tbody></table></section> : <p className={styles.state}>이 처리에는 저장된 상세 근거가 없습니다.</p>}
    </>}
  </Modal>;
}
