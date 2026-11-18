'use client';

import { useEffect, useId, useRef, useState } from 'react';
import { Button } from '../components/ui';
import { copyText } from '../lib/clipboard';
import { DCAT_PAGE_SIZE, dcatMediaType, dcatPath, readDcatFeed, type DcatFeed, type DcatFormat } from './dcatFeed';
import { ApiParameters, ApiResponseFields } from './ApiDocumentation';
import common from '../app/page.module.css';
import styles from './PublicDcat.module.css';

type FeedState = { kind: 'loading' } | { kind: 'ready'; feed: DcatFeed } | { kind: 'error' };

export function PublicDcat({ origin }: { origin: string }) {
  const id = useId();
  const [format, setFormat] = useState<DcatFormat>('jsonld');
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [state, setState] = useState<FeedState>({ kind: 'loading' });
  const [notice, setNotice] = useState<{ text: string; failed: boolean } | null>(null);
  const [downloading, setDownloading] = useState(false);
  const downloadRequest = useRef<AbortController | null>(null);
  const label = format === 'turtle' ? 'Turtle' : 'JSON-LD';
  const path = dcatPath(page);
  const command = `curl -H 'Accept: ${dcatMediaType(format)}' '${origin}${path}'`;

  useEffect(() => {
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]);
    readDcatFeed(format, page, signal)
      .then(feed => { if (!controller.signal.aborted) setState({ kind: 'ready', feed }); })
      .catch(() => { if (!controller.signal.aborted) setState({ kind: 'error' }); });
    return () => controller.abort();
  }, [format, page, refresh]);

  useEffect(() => () => { downloadRequest.current?.abort(); }, []);

  function changeFormat(next: DcatFormat) {
    if (next === format) return;
    setState({ kind: 'loading' }); setNotice(null); setFormat(next);
  }

  function changePage(next: number) { setState({ kind: 'loading' }); setNotice(null); setPage(next); }
  function reload() { setState({ kind: 'loading' }); setNotice(null); setRefresh(value => value + 1); }

  async function copy(value: string, name: string) {
    try { await copyText(value); setNotice({ text: `${name} 복사 완료`, failed: false }); }
    catch { setNotice({ text: '복사하지 못했습니다. 표시된 내용을 선택해 복사해 주세요.', failed: true }); }
  }

  async function download() {
    if (downloadRequest.current) return;
    const controller = new AbortController();
    downloadRequest.current = controller;
    setDownloading(true); setNotice(null);
    try {
      const feed = await readDcatFeed(format, page, AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]), true);
      if (controller.signal.aborted) return;
      const blob = new Blob([feed.body], { type: `${dcatMediaType(format)};charset=utf-8` });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `catalog-${page + 1}.${format === 'turtle' ? 'ttl' : 'jsonld'}`;
      link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setState({ kind: 'ready', feed });
      setNotice({ text: `현재 페이지를 ${label}로 내려받았습니다.`, failed: false });
    } catch {
      if (!controller.signal.aborted) setNotice({ text: '파일을 내려받지 못했습니다. 다시 시도해 주세요.', failed: true });
    } finally {
      if (downloadRequest.current === controller) downloadRequest.current = null;
      if (!controller.signal.aborted) setDownloading(false);
    }
  }

  const feed = state.kind === 'ready' ? state.feed : null;
  const pages = feed ? Math.max(1, Math.ceil(feed.total / DCAT_PAGE_SIZE)) : 1;
  const pageAvailable = feed !== null && page < pages;

  return <section className={styles.section} aria-labelledby={`${id}-title`} aria-busy={state.kind === 'loading'}>
    <h3 id={`${id}-title`}>공개 카탈로그 DCAT</h3>
    <p className={common.help}>현재 공개된 데이터셋 목록의 메타정보입니다. 이 데이터셋의 개별 게시 버전은 위의 메타정보 API에서 조회할 수 있습니다.</p>
    <div className={styles.toolbar}>
      <fieldset className={styles.formats}><legend>DCAT 형식</legend><div>
        <Button variant={format === 'jsonld' ? 'primary' : 'secondary'} aria-pressed={format === 'jsonld'} disabled={downloading} onClick={() => changeFormat('jsonld')}>JSON-LD</Button>
        <Button variant={format === 'turtle' ? 'primary' : 'secondary'} aria-pressed={format === 'turtle'} disabled={downloading} onClick={() => changeFormat('turtle')}>Turtle</Button>
      </div></fieldset>
      <Button variant="secondary" disabled={state.kind === 'loading' || downloading} onClick={reload}>DCAT 다시 조회</Button>
    </div>
    <div className={common.json}><code>GET {origin}{path}</code></div>
    <div className={common.buttonRow}>
      <Button variant="secondary" onClick={() => void copy(window.location.origin + path, 'DCAT API 주소')}>DCAT API 주소 복사</Button>
      <Button variant="secondary" onClick={() => void copy(`curl -H 'Accept: ${dcatMediaType(format)}' '${window.location.origin}${path}'`, 'DCAT 호출 예시')}>DCAT 호출 예시 복사</Button>
    </div>
    <h4>{label} 호출 예시</h4><div className={common.json}><pre className={common.jsonText}>{command}</pre></div>
    <ApiParameters title="DCAT 요청 매개변수" items={[
      { name: 'page', location: '쿼리', defaultValue: '0', description: '0부터 시작하는 페이지 번호.' },
      { name: 'size', location: '쿼리', defaultValue: '100', description: '페이지당 데이터셋 수. 1~500.' },
      { name: 'Accept', location: '헤더', defaultValue: 'application/ld+json', description: 'application/ld+json 또는 text/turtle.' },
    ]} />
    <ApiResponseFields items={[
      ['JSON-LD / Turtle', '현재 공개된 데이터셋의 DCAT 메타정보. 아래에 실제 응답 표시.'],
      ['X-Total-Count', '응답 헤더. 전체 공개 데이터셋 수.'],
      ['Link', '응답 헤더. 다음 페이지가 있으면 rel="next" 주소 제공.'],
    ]} />
    <p className={common.help}>파일 API: <code>/api/catalog/v1/dcat/download</code>. page·size는 동일하며 format=jsonld(기본값) 또는 format=turtle을 지정합니다.</p>
    {state.kind === 'loading' ? <p className={styles.state} role="status">{label} 메타정보를 불러오는 중입니다.</p>
      : state.kind === 'error' ? <div className={styles.state} role="alert"><p>공개 카탈로그를 불러오지 못했습니다.</p><Button variant="secondary" onClick={reload}>DCAT 다시 시도</Button></div>
        : <>
          <p className={styles.summary} role="status">{label} · 공개 데이터셋 {state.feed.total.toLocaleString('ko-KR')}개 · {page + 1}페이지</p>
          {state.feed.total === 0 ? <p>현재 공개된 데이터셋이 없습니다.</p> : !pageAvailable && <div><p>게시 상태가 변경되어 이 페이지에 데이터가 없습니다.</p><Button variant="secondary" onClick={() => changePage(0)}>첫 페이지로 이동</Button></div>}
          <section className={styles.content} tabIndex={0} aria-label={`DCAT ${label} 본문`} aria-describedby={`${id}-scope`}><pre><code>{state.feed.body}</code></pre></section>
          <nav className={styles.pagination} aria-label="DCAT 페이지">
            <Button variant="secondary" disabled={page === 0 || downloading} onClick={() => changePage(page - 1)}>DCAT 이전 페이지</Button>
            <span>{page + 1} / {pages} 페이지</span>
            <Button variant="secondary" disabled={page + 1 >= pages || downloading} onClick={() => changePage(page + 1)}>DCAT 다음 페이지</Button>
          </nav>
          <div className={common.buttonRow}>
            <Button variant="secondary" disabled={downloading || !pageAvailable} onClick={() => void copy(state.feed.body, `${label} 본문`)}>{label} 본문 복사</Button>
            <Button variant="secondary" disabled={downloading || !pageAvailable} onClick={() => void download()}>{downloading ? '파일 준비 중…' : `현재 페이지 ${label} 다운로드`}</Button>
          </div>
        </>}
    <p id={`${id}-scope`} className={styles.summary}>페이지당 최대 {DCAT_PAGE_SIZE}개 데이터셋. 복사·다운로드는 현재 페이지의 메타정보만 포함합니다.</p>
    {notice && <p className={styles.message} role={notice.failed ? 'alert' : 'status'}>{notice.text}</p>}
  </section>;
}
