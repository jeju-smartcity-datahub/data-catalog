import type { components } from './generated/api';
export type Dataset = components['schemas']['DatasetSummary'];
export type DatasetPage = components['schemas']['DatasetPage'];
export type CatalogSearchPage = components['schemas']['CatalogSearchPage'];
export type DatasetDetail = components['schemas']['DatasetDetail'];
export type CatalogFacets = components['schemas']['CatalogFacets'];
export type DatasetVersion = components['schemas']['DatasetVersion'];
export type ConsumerDocsAvailability = components['schemas']['ConsumerDocsAvailability'];
export type SearchParams = Record<string, string | string[] | undefined>;
export const filterKeys = ['regionId', 'categoryId', 'topicId', 'hubId', 'organizationId'] as const;
export type FilterKey = typeof filterKeys[number];
export type CatalogQuery = { page: number; q: string; sort: string } & Record<FilterKey, string[]>;
const sorts = ['publishedAt,desc', 'publishedAt,asc', 'title,asc', 'title,desc', 'relevance'];
const uuid = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;
export const isDatasetId = (value: string) => uuid.test(value);
export function parsePage(value: string | string[] | undefined): number {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return 0;
  const number = Number(value);
  return Number.isSafeInteger(number) && number <= 100000 ? number : 0;
}
export function parseQuery(params: SearchParams): CatalogQuery {
  const ids = (value: string | string[] | undefined) => [...new Set((Array.isArray(value) ? value : value ? [value] : []).filter(id => uuid.test(id)))].slice(0, 20);
  return { page: parsePage(params.page), q: typeof params.q === 'string' ? params.q.trim().slice(0, 200) : '',
    organizationId: ids(params.organizationId), categoryId: ids(params.categoryId), regionId: ids(params.regionId), topicId: ids(params.topicId), hubId: ids(params.hubId),
    sort: typeof params.sort === 'string' && sorts.includes(params.sort) ? params.sort : 'publishedAt,desc' };
}
export function queryString(query: CatalogQuery, page = query.page): string {
  const params = new URLSearchParams();
  if (page) params.set('page', String(page));
  if (query.q) params.set('q', query.q);
  if (query.sort !== 'publishedAt,desc') params.set('sort', query.sort);
  filterKeys.forEach(key => query[key].forEach(id => params.append(key, id)));
  return params.toString();
}
export class CatalogError extends Error {
  constructor(public readonly status: number) { super('카탈로그를 불러오지 못했습니다.'); }
}
export async function catalogFetch<T>(path: string, headers?: HeadersInit): Promise<T> {
  const base = process.env.SERVER_API_URL || 'http://127.0.0.1:8080';
  const response = await fetch(new URL(path, base), { cache: 'no-store', redirect: 'error', headers, signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new CatalogError(response.status);
  return response.json() as Promise<T>;
}
export function getDatasets(query: CatalogQuery): Promise<CatalogSearchPage> {
  // A fresh request nonce prevents reuse of another search's one-time feedback offer. It is never a user ID.
  return catalogFetch('/api/catalog/v1/search?' + queryString(query) + '&size=10', { 'X-Search-Nonce': crypto.randomUUID() });
}
export function getFacets(query: CatalogQuery): Promise<CatalogFacets> {
  const params = new URLSearchParams(queryString(query, 0)); params.delete('sort');
  return catalogFetch('/api/catalog/v1/search/facets?' + params);
}
export function getDataset(id: string, publicationNo?: number): Promise<DatasetDetail> {
  return catalogFetch('/api/catalog/v1/datasets/' + id + (publicationNo ? '/versions/' + publicationNo : ''));
}
export function getVersions(id: string): Promise<DatasetVersion[]> {
  return catalogFetch('/api/catalog/v1/datasets/' + id + '/versions');
}
export function getConsumerDocs(id: string): Promise<ConsumerDocsAvailability> {
  return catalogFetch('/api/docs/v1/datasets/' + id);
}
export const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium' }).format(new Date(value));
