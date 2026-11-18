'use client';

import { useEffect, useState } from 'react';
import { Button } from '../components/ui';
import type { components } from '../lib/generated/api';
import { formatDateTime, isNumericColumn, recordsRange, recordValueText } from '../lib/presentation';
import styles from '../app/page.module.css';

type RecordPage = components['schemas']['RecordPage'];
type State = { kind: 'loading' } | { kind: 'ready'; data: RecordPage } | { kind: 'error'; message: string };
const pageSize = 10;
async function errorMessage(response: Response): Promise<string> {
  const problem = await response.json().catch(() => null);
  if (problem?.code === 'DATA_ACCESS_UNAVAILABLE') return '이 데이터셋에는 데이터 조회 연결이 등록되어 있지 않습니다.';
  return typeof problem?.detail === 'string' ? problem.detail : '데이터를 조회하지 못했습니다. 다시 시도해 주세요.';
}

export function RecordsPreview({ datasetId, publicationNo }: { datasetId: string; publicationNo: number }) {
  const [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [format, setFormat] = useState<'table' | 'json'>('table');
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [downloading, setDownloading] = useState(false);
  const [notice, setNotice] = useState('');
  const path = `/api/catalog/v1/datasets/${datasetId}/records`;
  useEffect(() => {
    const controller = new AbortController();
    fetch(`${path}?limit=${pageSize}&offset=${offset}`, { cache: 'no-store', signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error(await errorMessage(response));
        const data = await response.json() as RecordPage;
        if (!Array.isArray(data.items) || !Array.isArray(data.columns)) throw new Error('데이터 응답 형식이 올바르지 않습니다.');
        if (!controller.signal.aborted) setState({ kind: 'ready', data });
      }).catch(error => { if (!controller.signal.aborted) setState({ kind: 'error', message: error instanceof Error ? error.message : '데이터를 조회하지 못했습니다.' }); });
    return () => controller.abort();
  }, [path, offset, refresh]);
  function changePage(next: number) { setState({ kind: 'loading' }); setNotice(''); setOffset(next); }
  function reload() { setState({ kind: 'loading' }); setNotice(''); setRefresh(value => value + 1); }
  async function download(extension: 'csv' | 'json') {
    setDownloading(true); setNotice('');
    try {
      const response = await fetch(`${path}/download?format=${extension}&limit=${pageSize}&offset=${offset}`, { cache: 'no-store', signal: AbortSignal.timeout(30000) });
      if (!response.ok) throw new Error(await errorMessage(response));
      const blob = await response.blob();const url = URL.createObjectURL(blob);const link = document.createElement('a');
      link.href = url;link.download = `data-${datasetId}-${offset + 1}.${extension}`;link.click();setTimeout(() => URL.revokeObjectURL(url), 1000);
      setNotice(`현재 페이지를 ${extension.toUpperCase()}로 내려받았습니다.`);
    } catch (error) { setNotice(error instanceof Error ? error.message : '파일을 내려받지 못했습니다.'); }
    finally { setDownloading(false); }
  }
  return <section aria-label="데이터 미리보기" aria-busy={state.kind === 'loading'}>
    <h2>데이터 미리보기</h2>
    <div className={styles.buttonRow}>
      <Button variant="secondary" aria-pressed={format === 'table'} onClick={() => setFormat('table')}>표</Button>
      <Button variant="secondary" aria-pressed={format === 'json'} onClick={() => setFormat('json')}>JSON</Button>
      <Button variant="secondary" disabled={state.kind === 'loading'} onClick={reload}>새로고침</Button>
    </div>
    {state.kind === 'loading' ? <p role="status">데이터를 조회하고 있습니다.</p>
      : state.kind === 'error' ? <div role="alert"><p>{state.message}</p><Button variant="secondary" onClick={reload}>다시 조회</Button></div>
        : <>
          <p className={styles.help}>현재 게시본 v{state.data.publicationNo} 기준{state.data.publicationNo !== publicationNo ? ` · 선택한 메타정보는 v${publicationNo}` : ''}</p>
          <p className={styles.recordSummary} role="status">{recordsRange(state.data.offset, state.data.items.length, state.data.totalCount)}</p>
          <dl className={styles.metadataGrid}>
            <div><dt>데이터 조회 시각</dt><dd>{formatDateTime(state.data.fetchedAt)} (한국시간)</dd></div>
            <div><dt>조회 행 최근 수정</dt><dd>{formatDateTime(state.data.recordUpdatedAt)}{state.data.recordUpdatedAt && ' (한국시간)'}</dd></div>
            <div><dt>조회 행 최근 관측</dt><dd>{formatDateTime(state.data.recordObservedAt)}{state.data.recordObservedAt && ' (한국시간)'}</dd></div>
          </dl>
          <p className={styles.help}>수정·관측 시각은 현재 조회한 행 중 가장 최근 시각입니다.</p>
          {format === 'json' ? <section className={styles.json} tabIndex={0} aria-label="조회 데이터 JSON"><pre className={styles.jsonText}>{JSON.stringify(state.data.items, null, 2)}</pre></section>
            : state.data.items.length === 0 ? <p>조회된 데이터가 없습니다.</p>
              : <section className={styles.tableScroll} tabIndex={0} aria-label="조회 데이터 표"><table className={styles.recordsTable}><caption className="sr-only">{recordsRange(state.data.offset, state.data.items.length, state.data.totalCount)}</caption><thead><tr><th scope="col">번호</th>{state.data.columns.map(column => <th scope="col" key={column.key} className={isNumericColumn(column.dataType) ? styles.numeric : undefined}>{column.label || column.key}{column.unit && <small>단위: {column.unit}</small>}</th>)}</tr></thead><tbody>{state.data.items.map((item, index) => <tr key={state.data.offset + index}><th scope="row">{state.data.offset + index + 1}</th>{state.data.columns.map(column => <td key={column.key} className={isNumericColumn(column.dataType) || typeof item[column.key] === 'number' ? styles.numeric : undefined}>{recordValueText(item[column.key])}</td>)}</tr>)}</tbody></table></section>}
          <div className={styles.buttonRow}>
            <Button variant="secondary" disabled={offset === 0 || downloading} onClick={() => changePage(Math.max(0, offset - pageSize))}>이전 10행</Button>
            <Button variant="secondary" disabled={!state.data.hasMore || downloading} onClick={() => changePage(offset + pageSize)}>다음 10행</Button>
            <Button variant="secondary" disabled={downloading || !state.data.items.length} onClick={() => void download('csv')}>현재 페이지 CSV</Button>
            <Button variant="secondary" disabled={downloading || !state.data.items.length} onClick={() => void download('json')}>현재 페이지 JSON</Button>
          </div>
        </>}
    {notice && <p role="status">{notice}</p>}
  </section>;
}
