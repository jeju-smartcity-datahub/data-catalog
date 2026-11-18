/** Browser tests for public record previews and API responses. */
import { createServer } from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { mkdir, open, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';

const web = fileURLToPath(new URL('../', import.meta.url));
const output = new URL('../../.runtime/records-and-access/', import.meta.url);
await mkdir(output, { recursive: true });
const id = '30000000-0000-0000-0000-000000000031';
const ref = { id: '10000000-0000-0000-0000-000000000031', name: 'UI 시험 기관' };
const fields = [{ fieldPath: 'waterLevel', physicalName: 'waterLevel', displayName: '수위', description: '수위', dataType: 'Number', unit: 'cm', nullable: true }];
const freshness = { firstPublishedAt: '2026-10-01T00:00:00Z', metadataCollectedAt: '2026-11-08T01:30:00Z', collectionStatus: 'SUCCEEDED' };
const current = { id, title: 'UI 시험 수위', description: '격리 UI 계약 시험', organization: ref, category: ref, regions: [], topics: [], hubs: [], details: { keywords: [], updateFrequency: '1시간' }, fields, publicationNo: 2, publishedAt: '2026-11-07T00:00:00Z', accessState: 'SERVICE_READY', freshness };
let totalKnown = true, upstreamError = false;
const results = [];
const requests = [];
const json = (response, body, status = 200, headers = {}) => { response.writeHead(status, { 'Content-Type': 'application/json', ...headers }); response.end(JSON.stringify(body)); };
const api = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  requests.push(url.pathname + url.search);
  if (request.method !== 'GET') return json(response, {}, 405);
  if (url.pathname.endsWith('/facets')) return json(response, { categories: [], organizations: [], regions: [], topics: [], hubs: [] });
  if (url.pathname.endsWith('/search')) return json(response, { items: [current], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 }, matches: [] });
  if (url.pathname.endsWith('/records')) {
    if (upstreamError) return json(response, { status: 502, code: 'DATA_SOURCE_UNAVAILABLE', detail: '허브의 데이터 조회 응답을 확인하지 못했습니다.' }, 502);
    const offset = Number(url.searchParams.get('offset') || 0);
    return json(response, { datasetId: id, publicationNo: 2, columns: [{ key: 'waterLevel', label: '수위', dataType: 'Number', unit: 'cm' }],
      items: Array.from({ length: 10 }, (_, index) => ({ waterLevel: 1234.56789 + offset + index })), offset, limit: 10, hasMore: offset === 0,
      totalCount: totalKnown ? 20 : null, fetchedAt: '2026-11-08T02:00:00Z', recordUpdatedAt: totalKnown ? '2026-11-08T01:58:00Z' : null, recordObservedAt: totalKnown ? '2026-11-08T01:57:00Z' : null });
  }
  if (url.pathname.startsWith('/api/docs/')) return json(response, { datasetId: id, documents: [`/api/catalog/v1/datasets/${id}/records`] });
  if (url.pathname.endsWith('/versions')) return json(response, [{ publicationNo: 2, publishedAt: current.publishedAt, current: true }, { publicationNo: 1, publishedAt: '2026-10-01T00:00:00Z', current: false }]);
  if (url.pathname.endsWith('/dcat')) return json(response, { '@context': { dcat: 'http://www.w3.org/ns/dcat#' }, '@graph': [] }, 200, { 'Content-Type': 'application/ld+json', 'X-Total-Count': '1' });
  if (url.pathname.endsWith('/versions/1')) return json(response, { ...current, publicationNo: 1, fields: [{ ...fields[0], unit: 'm' }] });
  return json(response, current);
});
let child, browser;
try {
  await new Promise((resolve, reject) => { api.once('error', reject); api.listen(0, '127.0.0.1', resolve); });
  const apiOrigin = `http://127.0.0.1:${api.address().port}`;
  const log = await open(new URL('next.log', output), 'w');
  child = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'dev', '--hostname', '127.0.0.1', '--port', '3008'], { cwd: web, env: { ...process.env, SERVER_API_URL: apiOrigin, NEXT_TELEMETRY_DISABLED: '1' }, stdio: ['ignore', log.fd, log.fd], windowsHide: true });
  await log.close();
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (child.exitCode !== null) throw new Error('Next dev exited before ready');
    try { if ((await fetch('http://127.0.0.1:3008/datasets')).ok) { ready = true; break; } } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  if (!ready) throw new Error('Next dev startup timeout');
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  await context.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    await route.fulfill({ response: await context.request.get(apiOrigin + url.pathname + url.search) });
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:3008/datasets');
  await expect(page.getByRole('searchbox', { name: '데이터 검색', exact: true })).toBeVisible();
  await expect(page.getByText('메타정보 수집', { exact: true })).toBeVisible();
  await expect(page.getByText('최근 수집 상태', { exact: true })).toBeVisible();
  results.push('목록 검색창·메타정보 수집 시각·수집 상태 표시');
  await page.goto(`http://127.0.0.1:3008/datasets/${id}?tab=preview&version=1`);
  await expect(page.getByRole('button', { name: '메타정보 복사', exact: true })).toBeVisible();
  await expect(page.getByText('현재 게시본 v2 기준 · 선택한 메타정보는 v1', { exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '수위 단위: cm' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '1,234.56789', exact: true })).toBeVisible();
  expect(await page.getByRole('cell', { name: '1,234.56789', exact: true }).evaluate(element => getComputedStyle(element).textAlign)).toBe('right');
  await expect(page.getByRole('status')).toContainText('표시 1–10행 / 전체 20행');
  await expect(page.getByText('조회 행 최근 수정', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '다음 10행', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('표시 11–20행 / 전체 20행');
  await expect(page.getByRole('button', { name: '다음 10행', exact: true })).toBeDisabled();
  await page.screenshot({ path: fileURLToPath(new URL('fixture-preview.png', output)), fullPage: true });
  results.push('과거 메타정보 조회 시에도 현재 게시본 단위 사용·숫자 오른쪽 정렬·마지막 10행 버튼 비활성화');
  totalKnown = false;
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('전체 행 수 미제공');
  await expect(page.locator('dt').filter({ hasText: '조회 행 최근 수정' }).locator('..').locator('dd')).toHaveText('미제공');
  await page.getByRole('button', { name: 'JSON', exact: true }).click();
  await expect(page.getByRole('region', { name: '조회 데이터 JSON', exact: true })).toContainText('1244.56789');
  results.push('미제공 전체 건수·데이터 시각을 추정하지 않음·JSON 원래 값 유지');
  await page.goto(`http://127.0.0.1:3008/datasets/${id}?tab=access&version=1`);
  await expect(page.getByText('공개 API · 로그인·API 키 불필요', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '데이터 조회 API', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '데이터 응답 조회', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '데이터 응답 조회', exact: true }).click();
  await expect(page.getByRole('region', { name: '데이터 API 응답', exact: true })).toContainText('"totalCount": null');
  await expect(page.getByText('HTTP 200 · 실제 조회 응답', { exact: true })).toBeVisible();
  await page.screenshot({ path: fileURLToPath(new URL('fixture-access.png', output)), fullPage: true });
  upstreamError = true;
  await page.getByRole('button', { name: '데이터 응답 조회', exact: true }).click();
  await expect(page.getByText('HTTP 502 · 실제 조회 응답', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '데이터 API 응답', exact: true })).toContainText('DATA_SOURCE_UNAVAILABLE');
  results.push('인증·매개변수·응답 안내 및 실제 성공/실패 응답 표시');
  expect(errors).toEqual([]);
  await writeFile(new URL('result.json', output), JSON.stringify({ purpose: 'RECORDS_AND_ACCESS_UI_TEST', results, errors, requests }, null, 2));
  console.log(JSON.stringify({ passed: results.length, results }, null, 2));
} finally {
  await browser?.close();
  if (child?.pid) {
    if (process.platform === 'win32') spawnSync('taskkill.exe', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true });
    else child.kill('SIGTERM');
  }
  await new Promise(resolve => api.close(resolve));
}
