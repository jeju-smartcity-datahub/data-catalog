import { createRequestId } from '../../lib/requestId';
import { useContext, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '../../components/ui';
import { HeadingActionsContext } from '../../components/HeadingActionsContext';
import { AdminApiError, adminDownload, adminWrite, errorMessage, type AdminSession } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { dateTime } from './dateTime';
import styles from './Operations.module.css';

type Report = components['schemas']['AnalyticsReport'];
export type ReportRequest = Omit<components['schemas']['AnalyticsReportRequest'], 'format'>;
export function AnalyticsReportActions({ session, request, disabled }: { session: AdminSession; request: ReportRequest; disabled: boolean }) {
  const target = useContext(HeadingActionsContext);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [limitExceeded, setLimitExceeded] = useState(false);
  const [report, setReport] = useState<Report | null>(null);
  const attempt = useRef<{ format: 'JSON' | 'CSV'; key: string; report?: Report } | null>(null);
  async function download(format: 'JSON' | 'CSV') {
    if (busy) return;
    setBusy(true); setError(''); setLimitExceeded(false);
    if (attempt.current?.format !== format) attempt.current = { format, key: createRequestId() };
    const current = attempt.current;
    try {
      if (!current.report) current.report = (await adminWrite<Report>('/analytics/reports', 'POST', session, { ...request, format }, undefined, current.key)).data;
      setReport(current.report);
      await adminDownload(`/analytics/reports/${current.report.id}/file`, current.report.fileName);
      // A confirmed download ends this attempt. A later click requests a fresh cutoff;
      // failed or uncertain attempts retain their key and existing report for retry.
      attempt.current = null;
    } catch (failure) {
      const exceeded = failure instanceof AdminApiError && failure.status === 422;
      setLimitExceeded(exceeded);
      setError(exceeded ? '보고서는 최대 5,000행 또는 8MiB까지 생성할 수 있습니다. 기간이나 조건을 좁혀 조회한 뒤 다시 생성하세요.' : errorMessage(failure));
    }
    finally { setBusy(false); }
  }
  const actions = <fieldset className={styles.reportActions} aria-busy={busy}><legend className="sr-only">보고서 다운로드</legend><Button variant="secondary" disabled={busy || disabled} onClick={() => void download('JSON')}>JSON 보고서</Button><Button variant="secondary" disabled={busy || disabled} onClick={() => void download('CSV')}>CSV 보고서</Button></fieldset>;
  return <>{target ? createPortal(actions, target) : actions}
    {busy && <p role="status" className={styles.note}>선택 조건으로 보고서를 준비하고 있습니다.</p>}
    {error && <div role="alert" className={styles.reportNotice}><p>{error}</p>{!limitExceeded && <p>다시 누르면 같은 요청으로 재시도합니다.</p>}<Button variant="secondary" disabled={busy} onClick={() => { attempt.current = null; setReport(null); setError(''); }}>요청 초기화</Button></div>}
    {report && <details className={styles.reportNotice}><summary>생성된 보고서 · {report.rowCount.toLocaleString('ko-KR')}행 · {report.format}</summary><dl className={styles.details}><dt>기준 시점</dt><dd>{dateTime(report.cutoff)}</dd><dt>다운로드 기한</dt><dd>{dateTime(report.expiresAt)}</dd><dt>무결성 SHA-256</dt><dd>{report.contentHash}</dd></dl><p className={styles.note}>요청자 본인의 현재 권한을 다운로드 시 다시 확인합니다.</p></details>}
  </>;
}
