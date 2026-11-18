import Image from 'next/image';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { Badge } from '../../../components/ui';
import { MetadataActions } from '../../../features/MetadataActions';
import { RecordsPreview } from '../../../features/RecordsPreview';
import { PublicApiAccess } from '../../../features/PublicApiAccess';
import { CatalogError, getDataset, getVersions, getConsumerDocs, isDatasetId, formatDate, type SearchParams, type DatasetDetail } from '../../../lib/catalog';
import { collectionStatusLabel, formatDateTime } from '../../../lib/presentation';
import styles from '../../page.module.css';

export const dynamic = 'force-dynamic';
export const metadata = { title: '데이터 상세' };
const tabs = [{ id: 'overview', label: '기본정보' }, { id: 'preview', label: '데이터 미리보기' }, { id: 'fields', label: '필드정보' }, { id: 'access', label: '이용정보' }];

function Overview({ data, recordsReady }: { data: DatasetDetail; recordsReady: boolean }) {
  const details = data.details;
  const hub = data.hubMetadata;
  return <>
    <h2>데이터 기본정보</h2><dl className={styles.metadataGrid}>
      <div><dt>관리기관</dt><dd>{data.organization.name}</dd></div><div><dt>분야</dt><dd>{data.category?.name || '미지정'}</dd></div>
      {hub?.providerOrganization && <div><dt>제공기관</dt><dd>{hub.providerOrganization}</dd></div>}
      {hub?.providerSystem && <div><dt>제공 시스템</dt><dd>{hub.providerSystem}</dd></div>}
      {hub?.ownership && <div><dt>소유기관</dt><dd>{hub.ownership}</dd></div>}
      {hub?.dataModelId && <div><dt>데이터모델 ID</dt><dd>{hub.dataModelId}</dd></div>}
      {hub?.sourceModifiedAt && <div><dt>허브 메타정보 수정</dt><dd>{formatDateTime(hub.sourceModifiedAt)}</dd></div>}
      <div><dt>공개범위</dt><dd>공개 메타정보</dd></div><div><dt>게시 버전</dt><dd>v{data.publicationNo}</dd></div>
      <div><dt>게시 시각</dt><dd>{formatDateTime(data.publishedAt)}</dd></div><div><dt>제공 방식</dt><dd>{recordsReady ? '메타정보 · 데이터 조회 API · CSV/JSON 다운로드' : '메타정보 JSON · DCAT JSON-LD·Turtle'}</dd></div>
      {(data.regions?.length ?? 0) > 0 && <div><dt>지역</dt><dd>{data.regions.map(value => value.name).join(', ')}</dd></div>}
      {(data.topics?.length ?? 0) > 0 && <div><dt>주제</dt><dd>{data.topics.map(value => value.name).join(', ')}</dd></div>}
      {(data.hubs?.length ?? 0) > 0 && <div><dt>데이터허브</dt><dd>{data.hubs.map(value => value.name).join(', ')}</dd></div>}
      {details?.department && <div><dt>담당 부서</dt><dd>{details.department}</dd></div>}
      {details?.updateFrequency && <div><dt>제공기관 갱신 주기</dt><dd>{details.updateFrequency}</dd></div>}
      {details?.spatialCoverage && <div><dt>공간 범위</dt><dd>{details.spatialCoverage}</dd></div>}
      {(details?.temporalStart || details?.temporalEnd) && <div><dt>시간 범위</dt><dd>{details.temporalStart || '시작 미지정'} ~ {details.temporalEnd || '종료 미지정'}</dd></div>}
    </dl><h2>데이터 설명</h2><p className={styles.description}>{data.description || '등록된 설명이 없습니다.'}</p>
    {(details?.keywords.length ?? 0) > 0 && <><h2>키워드</h2><ul className={styles.keywords} aria-label="데이터 키워드">{details.keywords.map(keyword => <li key={keyword}>#{keyword}</li>)}</ul></>}
  </>;
}

export default async function DatasetPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<SearchParams> }) {
  const { id } = await params;
  if (!isDatasetId(id)) notFound();
  const query = await searchParams;
  const tab = typeof query.tab === 'string' && tabs.some(item => item.id === query.tab) ? query.tab : 'overview';
  const publicationNo = typeof query.version === 'string' && /^[1-9]\d{0,8}$/.test(query.version) ? Number(query.version) : undefined;
  let data;
  try { data = await getDataset(id, publicationNo); }
  catch (error) {
    if (error instanceof CatalogError && error.status === 404) notFound();
    return <main id="main" tabIndex={-1} className={styles.main}><h1>데이터 상세</h1><section role="alert" className={styles.state}><h2>데이터를 불러오지 못했습니다</h2><p>잠시 후 다시 시도해 주세요.</p><Link className={styles.action} href={'/datasets/' + id + '?tab=' + tab + (publicationNo ? '&version=' + publicationNo : '')}>다시 시도</Link></section></main>;
  }
  const versionsResult = await Promise.allSettled([getVersions(id), getConsumerDocs(id)]);
  const versions = versionsResult[0].status === 'fulfilled' ? versionsResult[0].value : null;
  const recordsReady = versionsResult[1].status === 'fulfilled' && versionsResult[1].value.documents.includes(`/api/catalog/v1/datasets/${id}/records`);
  const href = (nextTab: string, version = data.publicationNo) => '/datasets/' + id + '?tab=' + nextTab + '&version=' + version;
  return <main id="main" tabIndex={-1} className={styles.main + ' ' + styles.detailMain}>
    <nav className={styles.breadcrumb} aria-label="현재 위치"><div><Link href="/datasets">데이터 탐색</Link><Image src="/assets/chevron-right.svg" alt="" width={14} height={14} /><span aria-current="page">데이터 상세</span></div><Link className={styles.outlineLink} href="/datasets">목록으로</Link></nav>
    <section className={styles.detailHero}>
      <div className={styles.detailHeading}><div className={styles.badges}>{data.category && <Badge category>{data.category.name}</Badge>}{data.regions?.map(region => <Badge key={region.id}>{region.name}</Badge>)}<Badge>공개 메타정보</Badge></div><h1>{data.title}</h1><p>{data.description}</p></div>
      <div className={styles.detailToolbar}><div className={styles.publicationInfo}><span><Image src="/assets/time.svg" alt="" width={20} height={20} />게시일 <strong>{formatDate(data.publishedAt)}</strong></span><span><Image src="/assets/fields.svg" alt="" width={20} height={20} />공개 필드 <strong>{data.fields.length}개</strong></span><span>게시 버전 <strong>v{data.publicationNo}</strong></span></div><MetadataActions key={id + ':' + data.publicationNo} data={data} /></div>
    </section>
    <div className={styles.detailLayout}>
      <section className={styles.detailPanel} aria-label="데이터 메타정보">
        <nav className={styles.tabs} aria-label="상세정보 구분">{tabs.map(item => <Link key={item.id} href={href(item.id)} aria-current={tab === item.id ? 'page' : undefined}>{item.label}{item.id === 'fields' && <span>{data.fields.length}</span>}</Link>)}</nav>
        <div className={styles.tabContent}>
          {tab === 'overview' && <Overview data={data} recordsReady={recordsReady} />}
          {tab === 'fields' && <><h2>필드 구성과 의미</h2>{data.fields.length ? <><p className={styles.tableHint} id="fields-scroll-hint">표를 좌우로 스크롤해 모든 필드를 확인하세요.</p><section className={styles.tableScroll} tabIndex={0} aria-label="공개 필드 목록" aria-describedby="fields-scroll-hint"><table><caption className="sr-only">게시 버전 v{data.publicationNo}의 공개 필드 정의</caption><thead><tr><th scope="col">필드</th><th scope="col">의미</th><th scope="col">자료형 / 단위</th><th scope="col">빈값 허용</th></tr></thead><tbody>{data.fields.map(field => <tr key={field.fieldPath}><th scope="row">{field.displayName}<small>{field.physicalName}</small></th><td>{field.description || '설명 없음'}</td><td>{field.dataType}<small>{field.unit || '단위 없음'}</small></td><td>{field.nullable ? '허용' : '필수'}</td></tr>)}</tbody></table></section></> : <div className={styles.description}><p>공개된 필드정보가 없습니다.</p><p className={styles.help}>게시된 버전에 등록된 공개 필드만 표시합니다.</p></div>}</>}
          {tab === 'preview' && <RecordsPreview key={id} datasetId={id} publicationNo={data.publicationNo} />}
          {tab === 'access' && <><h2>API 이용정보</h2><dl className={styles.metadataGrid}><div><dt>관리기관</dt><dd>{data.organization.name}</dd></div>{data.hubMetadata?.providerOrganization && <div><dt>제공기관</dt><dd>{data.hubMetadata.providerOrganization}</dd></div>}<div><dt>제공 형식</dt><dd>{recordsReady ? '메타정보 JSON · DCAT JSON-LD·Turtle · 데이터 CSV/JSON' : '메타정보 JSON · DCAT JSON-LD·Turtle'}</dd></div></dl><PublicApiAccess datasetId={id} publicationNo={data.publicationNo} metadata={data} providingApiUri={data.hubMetadata?.providingApiUri} />{data.details?.license && <><h2>라이선스 표기</h2><p className={styles.description}>{data.details.license}</p></>}</>}
        </div>
      </section>
      <aside className={styles.detailSidebar} aria-label="기관과 게시 정보">
        <section><h2>{data.hubMetadata?.providerOrganization ? '제공기관' : '관리기관'}</h2><p className={styles.provider}>{data.hubMetadata?.providerOrganization || data.organization.name}</p><dl>{data.hubMetadata?.providerOrganization && <div><dt>관리기관</dt><dd>{data.organization.name}</dd></div>}<div><dt>데이터 식별자</dt><dd>{data.id}</dd></div></dl></section>
        <section><h2>게시·수집 정보</h2><dl>{data.freshness && <div><dt>최초 게시</dt><dd>{formatDateTime(data.freshness.firstPublishedAt)}</dd></div>}<div><dt>선택 버전 게시</dt><dd>{formatDateTime(data.publishedAt)}</dd></div><div><dt>조회 중인 버전</dt><dd>v{data.publicationNo}</dd></div>{data.freshness && <><div><dt>최근 메타정보 수집</dt><dd>{formatDateTime(data.freshness.metadataCollectedAt)}</dd></div><div><dt>최근 수집 상태</dt><dd>{collectionStatusLabel(data.freshness.collectionStatus)}</dd></div></>}</dl><p className={styles.help}>한국시간 기준. 수집 시각·상태는 현재 허브 연결의 메타정보 기준입니다. 데이터 수정·관측 시각은 미리보기에서 확인할 수 있습니다.</p>
          {versions ? <nav className={styles.versions} aria-label="게시 버전">{versions.map(version => <Link key={version.publicationNo} href={href(tab, version.publicationNo)} aria-current={version.publicationNo === data.publicationNo ? 'page' : undefined}>v{version.publicationNo}{version.current ? ' (현재)' : ''}</Link>)}</nav> : <p role="alert" className={styles.help}>버전 목록을 불러오지 못했습니다.</p>}
        </section>
      </aside>
    </div>
  </main>;
}
