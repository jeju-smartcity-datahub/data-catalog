import Image from 'next/image';
import Link from 'next/link';
import { Badge, Button, Input } from '../../components/ui';
import { getDatasets, getFacets, parseQuery, queryString, formatDate, filterKeys, type CatalogQuery, type SearchParams, type Dataset } from '../../lib/catalog';
import styles from '../page.module.css';
import { SearchFeedback } from '../../features/SearchFeedback';
import { collectionStatusLabel, formatDateTime } from '../../lib/presentation';

export const dynamic = 'force-dynamic';
export const metadata = { title: '공개 데이터셋' };
const accessLabels: Record<Dataset['accessState'], string> = {
  METADATA_ONLY: '메타데이터', LINK_READY: '제공 링크', SERVICE_READY: 'API 제공', UNAVAILABLE: '제공 일시 중단',
};
function FilterInputs({ query }: { query: CatalogQuery }) {
  return filterKeys.flatMap(key => query[key].map(id => <input key={key + id} type="hidden" name={key} value={id} />));
}

export default async function DatasetsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const query = parseQuery(await searchParams);
  const result = await Promise.allSettled([getDatasets(query), getFacets(query)]);
  if (result[0].status === 'rejected' || result[1].status === 'rejected') {
    return <main id="main" tabIndex={-1} className={styles.main}><h1>공개 데이터셋</h1>
      <section className={styles.state} role="alert"><h2>데이터를 불러오지 못했습니다</h2><p>잠시 후 다시 시도해 주세요.</p><Link href={'/datasets?' + queryString(query)} className={styles.action}>다시 시도</Link></section>
    </main>;
  }
  const data = result[0].value, facets = result[1].value;
  const filterGroups = [
    { name: 'regionId', title: '지역', values: facets.regions ?? [] },
    { name: 'categoryId', title: '분야', values: facets.categories },
    { name: 'topicId', title: '주제', values: facets.topics ?? [] },
    { name: 'hubId', title: '데이터허브', values: facets.hubs ?? [] },
    { name: 'organizationId', title: '관리기관', values: facets.organizations },
  ] as const;
  const appliedFilters = filterGroups.flatMap(group => group.values.filter(value => value.selected).map(value => ({ key: group.name, title: group.title, ...value })));
  const pageStart = Math.max(0, Math.min(query.page - 2, data.page.totalPages - 5));
  const pages = Array.from({ length: Math.min(5, data.page.totalPages) }, (_, index) => pageStart + index);
  return <div className={styles.catalogPage}>
    <main id="main" tabIndex={-1} className={styles.main}>
      <h1 className={styles.catalogTitle}>공개 데이터셋</h1>
      <form key={'search-' + queryString(query)} action="/datasets" className={styles.searchBox} role="search" aria-label="카탈로그 검색">
        <div className={styles.searchHeading}><label htmlFor="catalog-query">데이터 검색</label><span>찾고 싶은 데이터를 문장이나 주제어로 입력하세요.</span></div>
        <div className={styles.searchInput}><Image src="/assets/search-large.svg" alt="" width={24} height={24} /><Input id="catalog-query" name="q" type="search" maxLength={200} defaultValue={query.q} placeholder="어떤 데이터를 찾고 계신가요?" /><Button type="submit">검색</Button></div>
        <FilterInputs query={query} />
        <input type="hidden" name="sort" value={query.sort} />
      </form>
      <div className={styles.catalogLayout}>
        <aside className={styles.filters} aria-label="데이터 탐색 필터">
          <div className={styles.filterHeading}><h2>상세 탐색</h2><Link href="/datasets">초기화</Link></div>
          <form key={'filters-' + queryString(query)} action="/datasets">
            <input type="hidden" name="q" value={query.q} /><input type="hidden" name="sort" value={query.sort} />
            {filterGroups.map(group =>
              <details key={group.name} open><summary>{group.title}<Image src="/assets/chevron-up.svg" width={20} height={20} alt="" /></summary>
                {group.values.length === 0 ? <p className={styles.help}>선택 가능한 항목이 없습니다.</p> : group.values.map(value =>
                  <label className={styles.facet} key={value.id}><span className={styles.checkbox}><input type="checkbox" name={group.name} value={value.id} defaultChecked={value.selected} /><Image src="/assets/checkbox-check.svg" width={10.5} height={7} alt="" /></span><span>{value.name}</span><span>{value.count}<span className="sr-only">건</span></span></label>)}
              </details>)}
            <Button type="submit" variant="secondary">필터 적용</Button>
          </form>
        </aside>
        <section className={styles.results} aria-label="검색 결과">
          <div className={styles.listHeading}><h2>{query.q ? '‘' + query.q + '’ 검색 결과' : '데이터 목록'} <span>{data.page.totalElements.toLocaleString('ko-KR')}</span></h2>
            <form key={'sort-' + queryString(query)} action="/datasets" className={styles.sortForm}>
              <input type="hidden" name="q" value={query.q} /><FilterInputs query={query} />
              <label className="sr-only" htmlFor="catalog-sort">데이터 정렬</label><div className={styles.selectControl}><select id="catalog-sort" name="sort" defaultValue={query.sort}>
                <option value="publishedAt,desc">최신 게시순</option><option value="publishedAt,asc">이전 게시순</option><option value="title,asc">제목 오름차순</option><option value="title,desc">제목 내림차순</option><option value="relevance">검색 관련순</option>
              </select><Image src="/assets/chevron-down.svg" width={20} height={20} alt="" /></div><Button type="submit" variant="secondary">정렬</Button>
            </form>
          </div>
          {query.q && data.interpretation && <p className={styles.help} role="status">{data.interpretation.message}{data.interpretation.terms.length > 0 && <> 검색 주제어: {data.interpretation.terms.join(' · ')}</>}</p>}
          {appliedFilters.length > 0 && <nav className={styles.appliedFilters} aria-label="적용 필터"><span>적용 필터</span><ul>{appliedFilters.map(filter => <li key={filter.key + filter.id}><Link href={'/datasets?' + queryString({ ...query, [filter.key]: query[filter.key].filter(id => id !== filter.id) }, 0)} aria-label={filter.title + ': ' + filter.name + ' 필터 해제'}>{filter.title}:{filter.name}<Image src="/assets/filter-remove.svg" width={14} height={14} alt="" /></Link></li>)}</ul><Link className={styles.clearFilters} href={'/datasets?' + queryString({ ...query, regionId: [], categoryId: [], topicId: [], hubId: [], organizationId: [] }, 0)}>전체 해제</Link></nav>}
          {data.items.length === 0 ? <section className={styles.state}><h2>{query.page > 0 ? '이 페이지에 데이터가 없습니다' : query.q || filterKeys.some(key => query[key].length) ? '검색 결과가 없습니다' : '아직 공개된 데이터가 없습니다'}</h2><p>검색어나 필터를 바꾸어 다시 확인해 주세요.</p><Link href={query.page > 0 ? '/datasets' + (queryString(query, 0) ? '?' + queryString(query, 0) : '') : '/datasets'} className={styles.action}>{query.page > 0 ? '첫 페이지로' : '검색 초기화'}</Link></section> : <div className={styles.list}>
            {data.items.map(dataset => <article className={styles.card} key={dataset.id}>
              <div className={styles.badges}>{dataset.category && <Badge category>{dataset.category.name}</Badge>}{dataset.regions?.map(region => <Badge key={region.id}>{region.name}</Badge>)}<Badge>{accessLabels[dataset.accessState] || '이용 정보 확인'}</Badge></div>
              <h3><Link href={'/datasets/' + encodeURIComponent(dataset.id)}>{dataset.title}</Link></h3><p>{query.q ? data.matches?.find(match => match.datasetId === dataset.id)?.summary ?? dataset.description : dataset.description}</p>
              {query.q && data.matches?.filter(match => match.datasetId === dataset.id).map(match => <div key={match.datasetId} className={styles.help}><p><strong>검색 일치 항목</strong> · {match.reasons.map(reason => reason.field + '에 ‘' + reason.term + '’ 일치').join(' · ')} · 규칙 점수 {match.score}</p><Link href={'/datasets/' + encodeURIComponent(dataset.id)}>데이터 상세 바로가기</Link></div>)}
              {(dataset.details?.keywords.length ?? 0) > 0 && <ul className={styles.keywords} aria-label="데이터 키워드">{dataset.details.keywords.map(keyword => <li key={keyword}>#{keyword}</li>)}</ul>}
              <dl><div><dt>관리기관</dt><dd>{dataset.organization.name}</dd></div>{dataset.details?.updateFrequency && <div><dt>제공기관 갱신 주기</dt><dd>{dataset.details.updateFrequency}</dd></div>}<div><dt>게시일</dt><dd><time dateTime={dataset.publishedAt}>{formatDate(dataset.publishedAt)}</time></dd></div>{dataset.freshness && <><div><dt>메타정보 수집</dt><dd>{formatDateTime(dataset.freshness.metadataCollectedAt)}</dd></div><div><dt>최근 수집 상태</dt><dd>{collectionStatusLabel(dataset.freshness.collectionStatus)}</dd></div></>}</dl>
            </article>)}
          </div>}
          {data.feedback ? <SearchFeedback key={data.feedback.eventId} offer={data.feedback} /> : <p className={styles.help} role="status">검색 이력 기록이 일시적으로 불가하여 이번 검색의 평가를 제공하지 않습니다. 검색 결과는 계속 확인할 수 있습니다.</p>}
          {data.page.totalPages > 1 && <nav aria-label="데이터 목록 페이지" className={styles.pagination}>
            {query.page > 0 ? <Link href={'/datasets?' + queryString(query, query.page - 1)}>이전</Link> : <span aria-disabled="true">이전</span>}
            {pages.map(page => <Link key={page} href={'/datasets?' + queryString(query, page)} aria-current={query.page === page ? 'page' : undefined}>{page + 1}<span className="sr-only"> 페이지</span></Link>)}
            <span className="sr-only">총 {data.page.totalPages}페이지</span>
            {query.page + 1 < data.page.totalPages ? <Link href={'/datasets?' + queryString(query, query.page + 1)}>다음</Link> : <span aria-disabled="true">다음</span>}
          </nav>}
        </section>
      </div>
    </main>
  </div>;
}
