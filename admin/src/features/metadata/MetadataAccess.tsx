import { useEffect, useState } from 'react';
import { Badge, Button } from '../../components/ui';
import type { components } from '../../lib/generated/api';
import styles from './Metadata.module.css';
import layout from './MetadataPresentation.module.css';
import { DataAccessSettings } from './DataAccessSettings';
import type { AdminSession } from '../../lib/admin';

type Availability = components['schemas']['ConsumerDocsAvailability'];
type State = { status: 'loading' } | { status: 'ready'; data: Availability } | { status: 'not-public' } | { status: 'error' };

export function MetadataAccess({ datasetId, published, organizationId, session }: { datasetId: string; published: boolean; organizationId: string; session: AdminSession }) {
  const [state, setState] = useState<State>({ status: 'loading' });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!published) return;
    const controller = new AbortController();
    fetch(`/api/docs/v1/datasets/${encodeURIComponent(datasetId)}`, {
      credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    }).then(async response => {
      if (response.status === 404) { setState({ status: 'not-public' }); return; }
      if (!response.ok) throw new Error('Metadata API unavailable');
      const data = await response.json() as Availability;
      if (!controller.signal.aborted) setState({ status: 'ready', data });
    }).catch(() => { if (!controller.signal.aborted) setState({ status: 'error' }); });
    return () => controller.abort();
  }, [datasetId, published, retry]);
  const metadataPath = `/api/catalog/v1/datasets/${datasetId}`;
  const links = state.status === 'ready' ? [
    { path: metadataPath, label: '메타정보 JSON API' },
    { path: '/api/catalog/v1/dcat', label: 'DCAT(JSON-LD) API' },
    { path: `${metadataPath}/records`, label: '데이터 조회 API' },
  ].filter(link => state.data.documents.includes(link.path)) : [];
  return <section aria-labelledby="access-heading">
    <h3 id="access-heading" className={styles.subheading}>데이터 이용 경로</h3>
    {!published || state.status === 'not-public' ? <p className={styles.help}>현재 공개된 게시본이 없습니다.</p>
      : state.status === 'loading' ? <p role="status">게시본의 API 정보를 불러오고 있습니다.</p>
        : state.status === 'error' ? <div role="alert"><p>게시본의 API 정보를 불러오지 못했습니다.</p><Button type="button" variant="secondary" onClick={() => { setState({ status: 'loading' }); setRetry(value => value + 1); }}>다시 조회</Button></div>
          : <><Badge tone="neutral">게시 버전 {state.data.publicationVersion}</Badge><p className={styles.help}>{state.data.message}</p>
            <dl className={layout.accessGrid}>{links.map(link => <div className={layout.accessWide} key={link.path}><dt>{link.label}</dt><dd><a href={link.path} target="_blank" rel="noreferrer">{link.path}</a></dd></div>)}
              <div><dt>인증 조건</dt><dd>인증 없음</dd></div><div><dt>제공 내용</dt><dd>{state.data.documents.includes(`${metadataPath}/records`) ? '게시된 메타정보·공개 필드 데이터' : '게시된 메타정보·필드정보'}</dd></div>
            </dl></>}
    <DataAccessSettings datasetId={datasetId} organizationId={organizationId} session={session} onChecked={() => { setState({ status: 'loading' }); setRetry(value => value + 1); }} />
  </section>;
}
