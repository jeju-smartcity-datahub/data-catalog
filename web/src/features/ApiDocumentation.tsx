'use client';

import { useEffect, useRef, useState } from 'react';
import { Button } from '../components/ui';
import styles from '../app/page.module.css';

type Parameter = { name: string; location: string; defaultValue: string; description: string };

export function ApiParameters({ title = '요청 매개변수', items }: { title?: string; items: Parameter[] }) {
  return <>
    <h4>{title}</h4>
    <section className={styles.tableScroll} tabIndex={0} aria-label={title}>
      <table className={styles.apiTable}><caption className="sr-only">{title}</caption>
        <thead><tr><th scope="col">이름</th><th scope="col">위치</th><th scope="col">기본값</th><th scope="col">입력 범위·설명</th></tr></thead>
        <tbody>{items.map(item => <tr key={item.name}><th scope="row"><code>{item.name}</code></th><td>{item.location}</td><td>{item.defaultValue}</td><td>{item.description}</td></tr>)}</tbody>
      </table>
    </section>
  </>;
}

export function ApiResponseFields({ items }: { items: [string, string][] }) {
  return <><h4>응답 항목</h4><dl className={styles.apiFields}>{items.map(([name, description]) => <div key={name}><dt><code>{name}</code></dt><dd>{description}</dd></div>)}</dl></>;
}

export function LiveApiResponse({ path }: { path: string }) {
  const [state, setState] = useState<{ status: number; body: unknown } | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => { request.current?.abort(); }, []);
  async function query() {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setLoading(true); setMessage(''); setState(null);
    try {
      const response = await fetch(path, { cache: 'no-store', headers: { Accept: 'application/json' }, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]) });
      const body: unknown = await response.json();
      if (!controller.signal.aborted) setState({ status: response.status, body });
    } catch {
      if (!controller.signal.aborted) setMessage('응답을 불러오지 못했습니다. 다시 조회해 주세요.');
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) setLoading(false);
    }
  }
  return <div>
    <h4>응답 확인</h4>
    <Button variant="secondary" disabled={loading} onClick={() => void query()}>{loading ? '응답 조회 중…' : '데이터 응답 조회'}</Button>
    {state && <><p className={styles.help} role="status">HTTP {state.status} · 실제 조회 응답</p><section className={styles.json} aria-label="데이터 API 응답" tabIndex={0}><pre className={styles.jsonText}>{JSON.stringify(state.body, null, 2)}</pre></section></>}
    {message && <p role="alert">{message}</p>}
  </div>;
}

export function ApiErrors() {
  return <section aria-label="API 오류 안내">
    <h3>오류 응답</h3>
    <p className={styles.help}>HTTP 상태와 JSON의 code·detail을 확인하세요. requestId는 요청별 식별자입니다.</p>
    <section className={styles.tableScroll} tabIndex={0} aria-label="API 오류 코드">
      <table className={styles.apiTable}><caption className="sr-only">API 오류별 처리 방법</caption><thead><tr><th scope="col">HTTP</th><th scope="col">code</th><th scope="col">처리 방법</th></tr></thead><tbody>
        <tr><th scope="row">400</th><td><code>INVALID_ARGUMENT</code></td><td>식별자·게시 버전·매개변수 자료형 또는 DCAT 조회 범위를 확인합니다.</td></tr>
        <tr><th scope="row">400</th><td><code>INVALID_FORMAT</code></td><td>데이터 다운로드 format을 json 또는 csv로 지정합니다.</td></tr>
        <tr><th scope="row">404</th><td><code>RESOURCE_NOT_FOUND</code></td><td>데이터셋 식별자·게시 버전과 현재 공개 상태를 확인합니다.</td></tr>
        <tr><th scope="row">404</th><td><code>DATA_ACCESS_UNAVAILABLE</code></td><td>데이터 조회 연결이 없거나 중지된 상태입니다. 메타정보 API를 이용합니다.</td></tr>
        <tr><th scope="row">412</th><td><code>VERSION_CONFLICT</code></td><td>조회 중 게시 상태·연결 설정이 변경되었습니다. 다시 조회합니다.</td></tr>
        <tr><th scope="row">422</th><td><code>DATA_ACCESS_INVALID</code></td><td>데이터 조회 limit을 1~1,000, offset을 0~10,000 범위로 지정합니다.</td></tr>
        <tr><th scope="row">502</th><td><code>DATA_SOURCE_UNAVAILABLE</code></td><td>허브 응답 오류입니다. 잠시 후 다시 조회합니다.</td></tr>
        <tr><th scope="row">503</th><td><code>CATALOG_UNAVAILABLE</code></td><td>카탈로그 조회가 일시 중단되었습니다. 잠시 후 다시 조회합니다.</td></tr>
        <tr><th scope="row">500</th><td><code>INTERNAL_ERROR</code></td><td>잠시 후 다시 조회하고 반복되면 requestId를 관리자에게 전달합니다.</td></tr>
      </tbody></table>
    </section>
    <h4>범위를 벗어난 데이터 조회의 오류 예시</h4>
    <p className={styles.help}>limit=1001 요청 시 반환되는 주요 항목입니다.</p>
    <div className={styles.json}><pre className={styles.jsonText}>{JSON.stringify({ type: 'about:blank', title: 'Unprocessable Entity', status: 422, detail: '조회 범위를 확인하세요.', code: 'DATA_ACCESS_INVALID' }, null, 2)}</pre></div>
  </section>;
}
