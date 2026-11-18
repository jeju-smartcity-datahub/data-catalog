import { useEffect, useState } from 'react';
import { Badge, Button } from '../../components/ui';
import checkIcon from './assets/check.svg';
import styles from './DcatWorkspace.module.css';

type Format = 'jsonld' | 'turtle';
type Feed = { body: string; total: number; page: number; format: Format };
type State = { status: 'loading' } | { status: 'ready'; feed: Feed } | { status: 'error' };
const pageSize = 10;
const mappings = [
  ['데이터셋 이름', 'dct:title'], ['설명', 'dct:description'],
  ['제공기관', 'dct:publisher'], ['식별자', 'dct:identifier'],
  ['게시 버전', 'dcat:version'], ['게시일', 'dct:issued'],
] as const;
const rules = ['검토 승인된 현재 게시 버전만 사용', '공개 범위가 ‘공개’인 데이터셋만 포함', '게시 철회 즉시 다음 조회·다운로드에서 제외'];

async function readFeed(format: Format, page: number, signal: AbortSignal, download = false): Promise<Feed> {
  const response = await fetch(`/api/catalog/v1/dcat${download ? '/download' : ''}?page=${page}&size=${pageSize}${download ? `&format=${format}` : ''}`, {
    headers: { Accept: format === 'jsonld' ? 'application/ld+json' : 'text/turtle' },
    credentials: 'same-origin', cache: 'no-store', signal,
  });
  if (!response.ok) throw new Error('피드를 가져오지 못했습니다.');
  const expectedMediaType = format === 'jsonld' ? 'application/ld+json' : 'text/turtle';
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim() !== expectedMediaType) throw new Error('피드 형식이 올바르지 않습니다.');
  const totalHeader = response.headers.get('X-Total-Count');
  const total = Number(totalHeader);
  if (totalHeader === null || !Number.isSafeInteger(total) || total < 0) throw new Error('피드 개수를 확인할 수 없습니다.');
  const body = await response.text();
  if (body.length > 2_000_000) throw new Error('미리보기 범위를 초과했습니다.');
  return { body: format === 'jsonld' ? JSON.stringify(JSON.parse(body), null, 2) : body, total, page, format };
}

export function DcatWorkspace() {
  const [format, setFormat] = useState<Format>('jsonld');
  const [page, setPage] = useState(0);
  const [request, setRequest] = useState(0);
  const [state, setState] = useState<State>({ status: 'loading' });
  const [downloading, setDownloading] = useState(false);
  const [downloadMessage, setDownloadMessage] = useState('');
  const [downloadFailed, setDownloadFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), 8000);
    readFeed(format, page, controller.signal).then(feed => setState({ status: 'ready', feed })).catch(() => {
      if (!controller.signal.aborted || controller.signal.reason?.name === 'TimeoutError') setState({ status: 'error' });
    }).finally(() => clearTimeout(timeout));
    return () => { clearTimeout(timeout); controller.abort(); };
  }, [format, page, request]);

  function changeFormat(next: Format) {
    if (next === format) return;
    setState({ status: 'loading' }); setDownloadMessage(''); setFormat(next);
  }

  function changePage(next: number) { setState({ status: 'loading' }); setDownloadMessage(''); setPage(next); }
  function refresh() { setState({ status: 'loading' }); setDownloadMessage(''); setRequest(value => value + 1); }

  async function download() {
    setDownloading(true); setDownloadMessage(''); setDownloadFailed(false);
    try {
      const latest = await readFeed(format, page, AbortSignal.timeout(8000), true);
      const blob = new Blob([latest.body], { type: format === 'jsonld' ? 'application/ld+json;charset=utf-8' : 'text/turtle;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `catalog-${page + 1}.${format === 'jsonld' ? 'jsonld' : 'ttl'}`;
      link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setState({ status: 'ready', feed: latest });
      setDownloadMessage('현재 공개 상태를 다시 확인해 이 페이지의 파일을 준비했습니다.');
    } catch { setDownloadFailed(true); setDownloadMessage('파일을 내려받지 못했습니다. 서버 연결을 확인한 후 다시 시도해 주세요.'); }
    finally { setDownloading(false); }
  }

  const total = state.status === 'ready' ? state.feed.total : null;
  const pages = total === null ? 1 : Math.max(1, Math.ceil(total / pageSize));

  return <div className={styles.workspace}>
    <div className={styles.summary}>
      <section className={styles.card} aria-labelledby="dcat-provision-heading">
        <div><div className={styles.cardTitle}><h2 id="dcat-provision-heading">외부 메타데이터 제공</h2><Badge>공개 피드</Badge></div><p className={styles.description}>공개된 데이터셋의 메타데이터를 내보냅니다.</p></div>
        <div className={styles.readOnly}><strong>DCAT 공개 피드 조회</strong><span>읽기 전용</span></div>
        <dl className={styles.metrics}>
          <div><dt>공개 제공 대상</dt><dd><strong>{total === null ? '—' : total.toLocaleString('ko-KR')}</strong>{total !== null && <span>개</span>}</dd></div>
          <div><dt>기관 내부 자료</dt><dd className={styles.excluded}>공개에서 제외</dd></div>
          <div><dt>미게시 · 철회 자료</dt><dd className={styles.excluded}>공개에서 제외</dd></div>
        </dl>
        <ul className={styles.rules}>{rules.map(rule => <li key={rule}><img src={checkIcon} alt="" />{rule}</li>)}</ul>
      </section>
      <section className={styles.card} aria-labelledby="dcat-mapping-heading">
        <div><h2 id="dcat-mapping-heading">프로파일·항목 매핑</h2><p className={styles.description}>현재 제공하는 카탈로그 항목과 DCAT 속성입니다.</p></div>
        <dl className={styles.mapping}>{mappings.map(([label, property]) => <div key={property}><dt>{label}</dt><dd>{property}</dd></div>)}</dl>
        <p className={styles.note}>데이터 이용 경로가 확정되지 않은 메타데이터에는 다운로드 주소나 데이터 서비스 정보를 포함하지 않습니다.</p>
      </section>
    </div>
    <section className={styles.card} aria-labelledby="dcat-preview-heading" aria-busy={state.status === 'loading'}>
      <div className={styles.previewHeader}>
        <h2 id="dcat-preview-heading">공개 메타데이터 미리보기</h2>
        <div className={styles.actions}>
          <fieldset className={styles.formats}><legend className="sr-only">내보내기 형식</legend>
            <button type="button" aria-pressed={format === 'jsonld'} disabled={downloading} onClick={() => changeFormat('jsonld')}>JSON-LD</button>
            <button type="button" aria-pressed={format === 'turtle'} disabled={downloading} onClick={() => changeFormat('turtle')}>Turtle</button>
          </fieldset>
          <Button variant="secondary" onClick={refresh} disabled={state.status === 'loading' || downloading}>새로고침</Button>
          <Button onClick={() => void download()} disabled={state.status !== 'ready' || downloading}>{downloading ? '파일 준비 중' : '이 페이지 다운로드'}</Button>
        </div>
      </div>
      {state.status === 'loading' ? <p className={styles.state} role="status">공개 메타데이터를 불러오는 중입니다.</p> : state.status === 'error' ? <div className={styles.state} role="alert"><h3>공개 피드를 불러오지 못했습니다</h3><p>잠시 후 다시 시도해 주세요.</p><Button variant="secondary" onClick={refresh}>다시 시도</Button></div> : <>
        {state.feed.total === 0 ? <p className={styles.empty} role="status">현재 공개 제공 대상이 없습니다. 공개·승인·게시 조건을 충족하는 데이터가 등록되면 표시됩니다.</p> : page >= pages ? <div className={styles.empty}><p>게시 상태가 변경되어 이 페이지에 표시할 데이터가 없습니다.</p><Button variant="secondary" onClick={() => changePage(0)}>첫 페이지로 이동</Button></div> : null}
        <output className={styles.note} aria-atomic="true">{format === 'jsonld' ? 'JSON-LD' : 'Turtle'} 조회 완료 · 공개 제공 대상 {state.feed.total.toLocaleString('ko-KR')}개 · {page + 1}페이지</output>
        <section className={styles.code} tabIndex={0} aria-label={`${format === 'jsonld' ? 'JSON-LD' : 'Turtle'} 공개 메타데이터 원문`} aria-describedby="dcat-preview-scope"><pre><code>{state.feed.body}</code></pre></section>
        <nav className={styles.pagination} aria-label="공개 피드 페이지"><Button variant="secondary" disabled={page === 0 || downloading} onClick={() => changePage(page - 1)}>이전</Button><p>{page + 1} / {pages} 페이지 · 페이지당 최대 {pageSize}개 데이터셋</p><Button variant="secondary" disabled={page + 1 >= pages || downloading} onClick={() => changePage(page + 1)}>다음</Button></nav>
      </>}
      {downloadMessage && <p className={downloadFailed ? styles.error : styles.note} role={downloadFailed ? 'alert' : 'status'}>{downloadMessage}</p>}
      <p id="dcat-preview-scope" className={styles.note}>미리보기와 다운로드는 현재 페이지의 공개 메타데이터만 포함합니다. 데이터 파일은 포함하지 않습니다.</p>
    </section>
  </div>;
}
