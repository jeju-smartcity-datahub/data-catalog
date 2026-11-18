import { useEffect, useState } from 'react';
import { Modal } from '../../components/Modal';
import { Button } from '../../components/ui';
import { AdminApiError, adminGet, errorMessage } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import { dateTime } from './dateTime';
import styles from './Operations.module.css';

type SearchEvent = components['schemas']['SearchEvent'];
type HistoryDetail = components['schemas']['HistoryDetail'];
const labels: Record<string, string> = { organizationId: '기관', categoryId: '분야', regionId: '지역', hubId: '허브', topicId: '주제', sort: '정렬', page: '페이지', size: '페이지 크기' };
const termLabels: Record<string, string> = { JEJU: '제주', PARKING: '주차', TRAFFIC: '교통', BUS: '버스', WEATHER: '기상·날씨', ENVIRONMENT: '환경', AIR: '대기', WATER_QUALITY: '수질', TOURISM: '관광', POPULATION: '인구', SAFETY: '안전', ENERGY: '에너지', AGRICULTURE: '농업', OCEAN: '해양' };
const intentLabels: Record<string, string> = { BROWSE: '공개 목록 조회', REQUEST_WITHOUT_SUBJECT: '주제어 없는 검색 요청', CONTROLLED_SEARCH: '분류어 검색', UNCLASSIFIED_SEARCH: '미분류 검색' };
function resultHref(datasetId?: string, publicationNo?: number): string | null {
  return typeof datasetId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(datasetId) && typeof publicationNo === 'number' && Number.isSafeInteger(publicationNo) && publicationNo > 0 ? `/datasets/${datasetId}?version=${publicationNo}` : null;
}

export function SearchEventDialog({ event, onClose }: { event: SearchEvent; onClose: () => void }) {
  const filters = Object.entries(event.normalizedFilters);
  const [state, setState] = useState<{ detail: HistoryDetail } | { error: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    adminGet<HistoryDetail>(`/search-history/${event.id}`, controller.signal)
      .then(({ data }) => setState({ detail: data }))
      .catch(error => { if (!controller.signal.aborted) setState({ error: error instanceof AdminApiError && error.status === 404 ? '보유기간이 만료되었거나 조회할 수 없는 검색 이력입니다.' : errorMessage(error) }); });
    return () => controller.abort();
  }, [event.id, attempt]);
  const detail = state && 'detail' in state ? state.detail : null;
  const representation = detail?.queryRepresentation;
  const results = detail?.returnedResults ?? [];
  return <Modal open wide size="details" title="검색 이력 상세" onClose={onClose}>
    <h3 className={styles.dialogTitle}>{event.hasQuery ? '검색어를 포함한 공개 검색' : '공개 목록 조회'}</h3>
    <h4>검색 정보</h4>
    <dl className={styles.record}><dt>검색 시각</dt><dd>{dateTime(event.occurredAt)}</dd><dt>검색 범위</dt><dd>공개 게시본</dd><dt>결과 건수</dt><dd>{event.resultCount}건</dd><dt>처리 시간</dt><dd>{event.durationMs}ms</dd></dl>
    <p className={styles.note}>이용자 ID·IP·검색어 원문·자유서술은 보관하지 않습니다. 검색별 정제된 분류 표현과 화면에 표시된 공개 결과, 선택형 평가만 연결합니다.</p>
    {!state ? <p role="status">정제된 검색 표현·결과·평가를 확인하고 있습니다.</p> : 'error' in state ? <div role="alert"><p>{state.error}</p><Button variant="secondary" onClick={() => { setState(null); setAttempt(value => value + 1); }}>검색 상세 다시 불러오기</Button></div> : <>
      {detail?.legacyIncomplete ? <p className={styles.note}>이전 이력에는 정제된 질의 표현·표시 결과·선택형 평가가 저장되지 않았습니다. 원문을 추정하거나 복원하지 않습니다.</p> : <>
        <h4>정제된 검색 표현</h4>
        <dl className={styles.record}><dt>검색 유형</dt><dd>{representation?.intent ? intentLabels[representation.intent] ?? '분류 정보 확인 필요' : '분류 정보 없음'}</dd><dt>분류어</dt><dd>{representation?.controlledTerms?.length ? representation.controlledTerms.map(code => termLabels[code] ?? '분류 코드 확인 필요').join(', ') : '해당 없음'}</dd><dt>표현 정책</dt><dd>{representation?.policyVersion === 'catalog-search-privacy-v1' ? '개인정보 제외 검색분류 v1' : '정책 정보 확인 필요'}</dd><dt>미분류 입력</dt><dd>{representation?.hasUnclassifiedTerms ? '미분류 입력 있음 · 내용 미보관' : '없음'}</dd></dl>
        <h4>표시된 공개 결과</h4>
        <p className={styles.note}>검색 당시 반환한 {(detail?.returnedPage ?? 0) + 1}페이지의 공개 결과입니다. 전체 검색 결과 목록과 구분하며 게시 버전을 고정합니다.</p>
        {results.length ? <section className={styles.tableWrap} aria-label="검색 당시 공개 결과 표" tabIndex={0}><table><caption className="sr-only">검색 결과 순서와 데이터셋 게시 버전</caption><thead><tr><th scope="col">표시 순서</th><th scope="col">데이터셋</th><th scope="col">게시 버전</th></tr></thead><tbody>{results.map((result, index) => { const href = resultHref(result.datasetId, result.publicationNo); return <tr key={`${result.datasetId}:${result.ordinal ?? index}`}><td>{(result.ordinal ?? index) + 1}</td><td className={styles.identifier}>{href ? <a href={href}>검색 당시 게시본 {result.datasetId}</a> : '저장된 공개 결과 식별 정보 확인 필요'}</td><td>v{result.publicationNo}</td></tr>; })}</tbody></table></section> : <p className={styles.note}>이 페이지에 표시된 공개 결과가 없습니다.</p>}
        <h4>선택형 평가</h4><dl className={styles.record}><dt>평가</dt><dd>{detail?.rating === 'HELPFUL' ? '도움됨' : detail?.rating === 'NOT_HELPFUL' ? '도움안됨' : '미평가'}</dd><dt>평가 시각</dt><dd>{detail?.ratedAt ? dateTime(detail.ratedAt) : '—'}</dd></dl>
      </>}
      <p className={styles.note}>프로젝트 보유정책: 5년. {detail?.expiresAt ? `보유 만료 시각: ${dateTime(detail.expiresAt)} (한국 시간).` : '보유 만료 정보 없음.'} 별도 백업 보관·파기는 운영 절차로 관리합니다.</p>
    </>}
    <h4>적용 조건</h4>
    {filters.length ? <dl className={styles.record}>{filters.map(([key, values]) => <div className={styles.recordGroup} key={key}><dt>{labels[key] || key}</dt><dd>{values.join(', ') || '전체'}</dd></div>)}</dl> : <p className={styles.note}>선택한 필터가 없습니다.</p>}
    <details className={styles.trace}><summary>처리 식별 정보</summary><dl className={styles.details}><dt>검색 이력 ID</dt><dd>{event.id}</dd><dt>요청 ID</dt><dd>{event.requestId}</dd></dl></details>
  </Modal>;
}
