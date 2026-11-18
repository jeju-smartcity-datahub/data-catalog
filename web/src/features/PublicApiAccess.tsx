'use client';

import { useEffect, useState, useSyncExternalStore } from 'react';
import { Button } from '../components/ui';
import { copyText } from '../lib/clipboard';
import { PublicDcat } from './PublicDcat';
import { ApiErrors, ApiParameters, ApiResponseFields, LiveApiResponse } from './ApiDocumentation';
import type { DatasetDetail } from '../lib/catalog';
import styles from '../app/page.module.css';

const subscribe = () => () => {};
const browserOrigin = () => window.location.origin;
const serverOrigin = () => '';

export function PublicApiAccess({ datasetId, publicationNo, metadata, providingApiUri }: { datasetId: string; publicationNo: number; metadata: DatasetDetail; providingApiUri?: string | null }) {
  const origin = useSyncExternalStore(subscribe, browserOrigin, serverOrigin);
  const [feedback, setFeedback] = useState('');
  const [availability, setAvailability] = useState<{ datasetId: string; request: number; ready: boolean; failed: boolean } | null>(null);
  const [refresh, setRefresh] = useState(0);
  const recordsPath = `/api/catalog/v1/datasets/${datasetId}/records`;
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/docs/v1/datasets/${datasetId}`, { cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) })
      .then(async response => {
        if (!response.ok) throw new Error('API availability failed');
        const data: unknown = await response.json();
        if (typeof data !== 'object' || data === null || !('documents' in data) || !Array.isArray(data.documents)) throw new Error('Invalid API availability');
        if (!controller.signal.aborted) setAvailability({ datasetId, request: refresh, ready: data.documents.includes(recordsPath), failed: false });
      }).catch(() => { if (!controller.signal.aborted) setAvailability({ datasetId, request: refresh, ready: false, failed: true }); });
    return () => controller.abort();
  }, [datasetId, recordsPath, refresh]);
  const currentAvailability = availability?.datasetId === datasetId && availability.request === refresh ? availability : null;
  const recordsReady = currentAvailability?.ready === true;
  const endpoints = [
    { kind: 'metadata', title: '데이터셋 메타정보 API', path: `/api/catalog/v1/datasets/${datasetId}/versions/${publicationNo}`, description: `게시 버전 v${publicationNo}의 설명·필드 정의·기관 정보를 JSON으로 제공합니다.`, accept: 'application/json' },
    ...(recordsReady ? [{ kind: 'records', title: '데이터 조회 API', path: `${recordsPath}?limit=10&offset=0`, description: '허브에서 조회한 현재 데이터를 현재 게시본의 공개 필드 기준으로 제공합니다. 이전 메타정보 버전을 선택해도 데이터 조회는 현재 게시본을 사용합니다.', accept: 'application/json' }] : []),
  ];
  async function copy(value: string, label: string) {
    try { await copyText(value); setFeedback(`${label}를 복사했습니다.`); }
    catch { setFeedback('복사하지 못했습니다. 표시된 내용을 선택해 복사해 주세요.'); }
  }
  return <>
    <p className={styles.description}>데이터셋의 메타정보·필드 정의와 연결된 데이터 조회 API입니다.</p>
    <dl className={styles.metadataGrid}><div><dt>호출 방식</dt><dd>HTTP GET</dd></div><div><dt>인증</dt><dd>공개 API · 로그인·API 키 불필요</dd></div><div><dt>공개 범위</dt><dd>현재 공개 중인 데이터셋과 공개 필드</dd></div><div><dt>응답</dt><dd>JSON · DCAT은 JSON-LD/Turtle</dd></div></dl>
    {endpoints.map(endpoint => {
      const url = origin + endpoint.path;
      const command = `curl -H 'Accept: ${endpoint.accept}' '${url}'`;
      return <section key={endpoint.path} aria-label={endpoint.title}>
        <h3>{endpoint.title}</h3><p className={styles.help}>{endpoint.description}</p>
        <div className={styles.json}><code>GET {url}</code></div>
        <div className={styles.buttonRow}>
          <Button variant="secondary" onClick={() => copy(window.location.origin + endpoint.path, 'API 주소')}>{endpoint.title} 주소 복사</Button>
          <a className={styles.outlineLink} href={endpoint.path} target="_blank" rel="noopener noreferrer">{endpoint.title} JSON 조회 ↗</a>
        </div>
        <h4>호출 예시</h4><div className={styles.json}><pre className={styles.jsonText}>{command}</pre></div>
        <Button variant="secondary" onClick={() => copy(`curl -H 'Accept: ${endpoint.accept}' '${window.location.origin}${endpoint.path}'`, '호출 예시')}>{endpoint.title} 호출 예시 복사</Button>
        <ApiParameters title={endpoint.title + " 요청 매개변수"} items={[
          { name: 'datasetId', location: '경로', defaultValue: '필수', description: `데이터셋 UUID: ${datasetId}` },
          ...(endpoint.kind === 'metadata' ? [{ name: 'publicationNo', location: '경로', defaultValue: '필수', description: `게시 버전 번호. 현재 선택: ${publicationNo}` }] : [
            { name: 'limit', location: '쿼리', defaultValue: '10', description: '한 번에 조회할 행 수. 1~1,000.' },
            { name: 'offset', location: '쿼리', defaultValue: '0', description: '건너뛸 행 수. 0~10,000.' },
          ]),
        ]} />
        {endpoint.kind === 'metadata' ? <>
          <ApiResponseFields items={[
            ['id / title / description', '데이터셋 식별자·이름·설명'], ['publicationNo / publishedAt', '선택한 게시 버전·게시 시각'],
            ['organization / category / regions / hubs', '관리기관·분야·지역·데이터허브'],
            ['fields', '공개 필드의 이름·의미·자료형·단위'], ['freshness', '최초 게시·최근 메타정보 수집 시각·수집 상태'],
          ]} />
          <details className={styles.apiDetails}><summary>현재 메타정보 응답 보기</summary><section className={styles.json} tabIndex={0} aria-label="메타정보 API 응답"><pre className={styles.jsonText}>{JSON.stringify(metadata, null, 2)}</pre></section></details>
        </> : <>
          <ApiResponseFields items={[
            ['datasetId / publicationNo', '조회에 사용한 데이터셋·현재 게시 버전'], ['columns', '현재 게시본의 공개 필드. key·label·dataType·unit'],
            ['items', '조회한 데이터 행 배열. 누락된 값은 null'], ['offset / limit / hasMore', '시작 위치·요청 행 수·다음 행 존재 여부'],
            ['totalCount', '허브가 제공한 전체 행 수. 미제공 시 null'], ['fetchedAt', '데이터 조회 완료 시각'],
            ['recordUpdatedAt / recordObservedAt', '현재 응답 행의 최근 수정·관측 시각. 미제공 시 null'],
          ]} />
          <LiveApiResponse key={endpoint.path} path={endpoint.path} />
        </>}
      </section>;
    })}
    {recordsReady && <section aria-label="데이터 다운로드 API"><h3>데이터 다운로드 API</h3><div className={styles.json}><code>GET {origin}{recordsPath}/download?format=json&amp;limit=1000&amp;offset=0</code></div><ApiParameters title="다운로드 요청 매개변수" items={[
      { name: 'datasetId', location: '경로', defaultValue: '필수', description: '위 데이터 조회 API와 같은 데이터셋 UUID.' },
      { name: 'format', location: '쿼리', defaultValue: 'json', description: 'json 또는 csv.' },
      { name: 'limit', location: '쿼리', defaultValue: '1000', description: '1~1,000행. 전체 데이터 자동 다운로드가 아닌 지정 범위 다운로드.' },
      { name: 'offset', location: '쿼리', defaultValue: '0', description: '건너뛸 행 수. 0~10,000.' },
    ]} /><p className={styles.help}>JSON은 items에 해당하는 행 배열, CSV는 필드 키를 머리글로 제공합니다. 다음 범위는 offset을 늘려 조회하세요.</p><div className={styles.buttonRow}><a className={styles.outlineLink} href={`${recordsPath}/download?format=csv&limit=1000&offset=0`}>최대 1,000행 CSV 다운로드</a><a className={styles.outlineLink} href={`${recordsPath}/download?format=json&limit=1000&offset=0`}>최대 1,000행 JSON 다운로드</a></div></section>}
    {!currentAvailability && <p role="status">데이터 조회 API 제공 여부를 확인하는 중입니다.</p>}
    {currentAvailability?.failed && <div role="alert"><p>데이터 조회 API 상태를 불러오지 못했습니다.</p><Button variant="secondary" onClick={() => setRefresh(value => value + 1)}>API 상태 다시 조회</Button></div>}
    {currentAvailability && !currentAvailability.failed && !recordsReady && <p className={styles.help}>이 데이터셋은 메타정보 API를 제공합니다.</p>}
    {providingApiUri && <section aria-label="허브 제공 API 주소">
      <h3>허브 제공 API 주소</h3>
      <div className={styles.json}><code>{providingApiUri}</code></div>
      <div className={styles.buttonRow}>
        <Button variant="secondary" onClick={() => copy(providingApiUri, '허브 API 주소')}>허브 API 주소 복사</Button>
        <a className={styles.outlineLink} href={providingApiUri} target="_blank" rel="noopener noreferrer">허브 API 열기 ↗</a>
      </div>
    </section>}
    {feedback && <p className={styles.help} role="status">{feedback}</p>}
    <PublicDcat origin={origin} />
    <ApiErrors />
  </>;
}
