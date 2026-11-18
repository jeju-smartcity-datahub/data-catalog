import type { ReactNode } from 'react';
import type { HubFailure } from './hubError';
import styles from './Hubs.module.css';

export function HubAlert({ id, title, failure, action, children }: { id?: string; title: string; failure: HubFailure; action?: string; children?: ReactNode }) {
  return <div id={id} className={styles.alert} role="alert">
    <strong>{title}</strong><p>{failure.message}</p>
    {action && <p className={styles.alertAction}>{action}</p>}
    {(failure.code || failure.requestId) && <details><summary>상세 정보</summary>{failure.code && <p>오류 코드: {failure.code}</p>}{failure.requestId && <p>요청 ID: {failure.requestId}</p>}</details>}
    {children}
  </div>;
}
