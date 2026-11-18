/** Isolated SSR UI route fixtures. No runtime mocks, authentication, or DB writes. */
import { createServer } from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { mkdir, writeFile, open } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium, expect } from '@playwright/test';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

const workspace = fileURLToPath(new URL('../../', import.meta.url));
const web = fileURLToPath(new URL('../', import.meta.url));
const output = process.env.CATALOG_UI_OUTPUT ? pathToFileURL(process.env.CATALOG_UI_OUTPUT.replace(/[/\\]?$/, '/')) : new URL('../../.runtime/public-ui-completion/', import.meta.url);
await mkdir(output, { recursive: true });
const fixtureOnly = process.env.CATALOG_UI_FIXTURE_ONLY === 'true';
let mode = fixtureOnly ? 'populated' : 'live';
const datasetId = '30000000-0000-0000-0000-000000000031';
const organization = { id: '10000000-0000-0000-0000-000000000031', name: '[UI 시험 예시] 긴 기관명으로 줄바꿈과 필터 선택을 확인하는 데이터 제공기관' };
const category = { id: '20000000-0000-0000-0000-000000000031', name: '환경' };
const regions = [{ id: '50000000-0000-0000-0000-000000000031', name: '[UI 시험] 지역 가' }, { id: '50000000-0000-0000-0000-000000000032', name: '[UI 시험] 지역 나' }];
const topics = [{ id: '60000000-0000-0000-0000-000000000031', name: '[UI 시험] 수위 관측' }];
const hubs = [{ id: '70000000-0000-0000-0000-000000000031', name: '[UI 시험] 메타정보 허브 분류' }];
const details = { department: '[UI 시험] 환경 관측 부서', license: 'UI 상태 검수용 라이선스 표기 — 실제 제공 허가가 아님', updateFrequency: '일 1회', spatialCoverage: 'UI 시험 공간 범위', temporalStart: '2026-01-01', temporalEnd: null, keywords: ['UI 시험', '환경', '키워드가 길 때 줄바꿈 확인'] };
const categories = ['환경', '행정', '관광', '생활', '산업', '안전'].map((name, index) => ({ id: '20000000-0000-0000-0000-' + String(31 + index).padStart(12, '0'), name }));
const fields = [
  { fieldPath: 'observation.value', physicalName: 'observation_value', displayName: '관측값', description: 'UI 시험용 필드 설명입니다. 실제 관측값이나 허브 응답이 아닙니다.', dataType: 'DECIMAL', nullable: false, unit: '℃' },
  { fieldPath: 'observation.stationReference', physicalName: 'observation_station_reference_with_a_long_identifier', displayName: '긴 필드명과 단위가 없는 관측소 참조', description: '긴 설명이 표 안에서 줄바꿈되고 좁은 화면에서도 다른 열을 덮지 않는지 확인하는 시험용 설명입니다.', dataType: 'STRING', nullable: true, unit: null },
  { fieldPath: 'observation.recordedAt', physicalName: 'recorded_at', displayName: '기록 시각', description: null, dataType: 'TIMESTAMP', nullable: false, unit: null },
];
const detail = { id: datasetId, title: '[UI 시험 예시] 필드가 등록된 공개 메타정보', description: '시험용 route fixture입니다. 실제 데이터, 실제 허브 또는 데이터 제공 API가 아닙니다.\n게시된 메타정보의 긴 설명과 줄바꿈을 확인합니다.', organization, category, regions, topics, hubs, details, hubMetadata: { providingApiUri: 'https://hub.example.invalid/public/datasets/test' }, publishedAt: '2026-10-16T00:00:00Z', publicationNo: 2, accessState: 'METADATA_ONLY', fields, access: { publicationNo: 2, accessState: 'METADATA_ONLY', message: '실제 제공 계약이 없어 데이터를 제공하지 않습니다.', capabilities: [] } };
const groups = [], snapshots = [], errors = [], catalogRequests = [];
let redirectedRequests = 0;
let browser, child, api;
const json = (response, data, status = 200) => { response.writeHead(status, { 'content-type': 'application/json' }); response.end(JSON.stringify(data)); };
const luminance = rgb => rgb.match(/\d+/g).slice(0, 3).map(Number).map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
const contrast = (foreground, background) => (Math.max(luminance(foreground), luminance(background)) + .05) / (Math.min(luminance(foreground), luminance(background)) + .05);
try {
  api = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method !== 'GET') return json(response, { message: 'UI fixture accepts reads only' }, 405);
    if (url.pathname === '/redirect-target') { redirectedRequests++; return json(response, {}); }
    if (mode === 'redirect') { response.writeHead(302, { Location: '/redirect-target' }); response.end(); return; }
    if (mode === 'live') {
      try { const upstream = await fetch('http://127.0.0.1:8080' + request.url); response.writeHead(upstream.status, { 'content-type': 'application/json' }); response.end(await upstream.text()); }
      catch { json(response, { message: 'Live API unavailable' }, 503); }
      return;
    }
    if (mode === 'loading') await new Promise(resolve => setTimeout(resolve, 1800));
    if (mode === 'error') return json(response, { message: 'Isolated UI error fixture' }, 503);
    catalogRequests.push(url.pathname + url.search);
    if (url.pathname.endsWith('/facets')) {
      const facet = (values, key) => mode === 'empty' ? [] : values.map(value => ({ ...value, count: 25, selected: url.searchParams.getAll(key).includes(value.id) }));
      return json(response, { organizations: facet([organization], 'organizationId'), categories: facet(categories, 'categoryId'), regions: facet(regions, 'regionId'), topics: facet(topics, 'topicId'), hubs: facet(hubs, 'hubId'), countMode: 'EXCLUDE_OWN_GROUP' });
    }
    if (url.pathname === '/api/catalog/v1/datasets' || url.pathname === '/api/catalog/v1/search') {
      const page = Number(url.searchParams.get('page') || 0);
      const items = mode === 'empty' ? [] : categories.map((value, index) => ({ ...detail, id: index ? datasetId.slice(0, -2) + String(32 + index) : datasetId, category: value, title: index ? '[UI 시험 예시] ' + value.name + ' 메타정보' : detail.title }));
      const terms = url.searchParams.get('q') ? ['시험'] : [];
      const matches = items.map(item => ({ datasetId: item.id, summary: '[UI 시험 요약] ' + item.description, reasons: terms.map(term => ({ term, field: '제목', points: 4 })), score: terms.length ? 4 : 0, scoreRule: 'ISOLATED_UI_CONTRACT_FIXTURE', detailPath: mode === 'unsafe-link' ? 'https://outside.invalid/redirect' : '/datasets/' + item.id }));
      return json(response, { items, page: { number: page, size: 10, totalElements: mode === 'empty' ? 0 : 25, totalPages: mode === 'empty' ? 0 : 3 }, interpretation: { ruleVersion: 'ISOLATED_UI_CONTRACT_FIXTURE', terms, message: 'UI 계약 시험용 해석 응답입니다.' }, matches });
    }
    if (mode === 'notFound') return json(response, { message: 'Isolated UI missing publication' }, 404);
    if (url.pathname.includes('/records')) {
      if (mode === 'recordsUnavailable') return json(response, { code: 'DATA_ACCESS_UNAVAILABLE' }, 404);
      if (mode === 'recordsError') return json(response, { code: 'DATA_SOURCE_UNAVAILABLE', detail: '허브의 데이터 조회 응답을 확인하지 못했습니다.' }, 502);
      const offset = Number(url.searchParams.get('offset') || 0);
      const items = mode === 'recordsEmpty' ? [] : Array.from({ length: offset ? 2 : 10 }, (_, i) => ({ 'observation.value': 20 + offset + i, 'observation.stationReference': `station-${offset + i + 1}`, 'observation.recordedAt': '2026-11-02T09:00:00Z' }));
      if (url.pathname.endsWith('/download')) { response.writeHead(200, { 'content-type': 'text/csv', 'content-disposition': 'attachment; filename=records.csv' });response.end('observation.value\n20\n');return; }
      return json(response, { datasetId, publicationNo: 2, columns: fields.map(f => ({ key: f.fieldPath, label: f.displayName })), items, offset, limit: 10, hasMore: offset === 0 && items.length > 0 });
    }
    if (url.pathname.startsWith('/api/docs/')) return mode === 'partialError' ? json(response, {}, 503) : json(response, { datasetId, publicationId: '40000000-0000-0000-0000-000000000031', publicationVersion: 2, accessState: 'SERVICE_READY', documentKind: 'METADATA_API', documents: [`/api/catalog/v1/datasets/${datasetId}/records`] });
    if (url.pathname.endsWith('/versions')) return mode === 'partialError' ? json(response, {}, 503) : json(response, [{ publicationNo: 2, publishedAt: detail.publishedAt, current: true }, { publicationNo: 1, publishedAt: detail.publishedAt, current: false }]);
    return json(response, mode === 'noFields' ? { ...detail, description: '', category: null, fields: [], regions: [], hubs: [], topics: [], details: { department: null, license: null, updateFrequency: null, spatialCoverage: null, temporalStart: null, temporalEnd: null, keywords: [] } } : detail);
  });
  await new Promise((resolve, reject) => { api.once('error', reject); api.listen(0, '127.0.0.1', resolve); });
  const apiPort = api.address().port;
  const log = await open(new URL('next-dev.log', output), 'w');
  child = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'dev', '--hostname', '127.0.0.1', '--port', '3005'], { cwd: web, env: { ...process.env, SERVER_API_URL: 'http://127.0.0.1:' + apiPort, NEXT_TELEMETRY_DISABLED: '1' }, stdio: ['ignore', log.fd, log.fd], windowsHide: true });
  await log.close();
  let ready = false;
  for (let attempt = 0; attempt < 120; attempt++) {
    if (child.exitCode !== null) throw new Error('Isolated Next dev exited before ready');
    try { if ((await fetch('http://127.0.0.1:3005/datasets')).ok) { ready = true; break; } } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  if (!ready) throw new Error('Isolated Next dev was not ready');
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await context.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const response = await context.request.get('http://127.0.0.1:' + apiPort + url.pathname + url.search);
    await route.fulfill({ response });
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const goto = path => page.goto('http://127.0.0.1:3005' + path);
  async function capture(name) {
    // Next streams route metadata separately from the visible page content.
    await expect(page).toHaveTitle(/^(공개 데이터셋 \| |데이터 상세 \| )?데이터 카탈로그$/);
    await page.evaluate(() => document.fonts.ready);
    await auditAccessibility(page, 'public-' + name, mode === 'live' ? 'ACTUAL_LOCAL_API' : 'ISOLATED_UI_CONTRACT_FIXTURE');
    const metrics = await page.evaluate(() => ({ width: innerWidth, documentWidth: document.documentElement.scrollWidth, images: [...document.images].filter(image => image.getBoundingClientRect().width && getComputedStyle(image).visibility !== 'hidden').map(image => ({ src: new URL(image.src).pathname, width: image.getBoundingClientRect().width, height: image.getBoundingClientRect().height, expectedWidth: Number(image.getAttribute('width')), expectedHeight: Number(image.getAttribute('height')), loaded: image.complete && image.naturalWidth > 0 })) }));
    expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.width);
    for (const image of metrics.images) { expect(image.loaded).toBeTruthy(); expect(Math.abs(image.width - image.expectedWidth)).toBeLessThan(0.1); expect(Math.abs(image.height - image.expectedHeight)).toBeLessThan(0.1); }
    const fullPage = await page.locator('dialog[open]').count() === 0;
    await page.screenshot({ path: fileURLToPath(new URL(name + '.png', output)), fullPage });
    snapshots.push({ name, mode, fullPage, ...metrics });
  }
  await goto('/datasets');
  await expect(page.getByRole('heading', { name: fixtureOnly ? detail.title : '[개발 예시]', exact: fixtureOnly })).toBeVisible();
  await capture(fixtureOnly ? 'fixture-initial-list' : 'live-list'); groups.push(fixtureOnly ? 'isolated UI fixture list, local original assets, category color' : 'real API list, local original assets, category color');
  const controlColors = await page.getByRole('checkbox').first().evaluate(input => ({ border: getComputedStyle(input).borderTopColor, inner: getComputedStyle(input).backgroundColor, outer: getComputedStyle(input.closest('aside')).backgroundColor }));
  expect(contrast(controlColors.border, controlColors.inner)).toBeGreaterThanOrEqual(3);
  expect(contrast(controlColors.border, controlColors.outer)).toBeGreaterThanOrEqual(3);
  const placeholder = await page.getByRole('searchbox', { name: '데이터 검색', exact: true }).evaluate(input => ({ color: getComputedStyle(input, '::placeholder').color, background: getComputedStyle(input).backgroundColor }));
  expect(contrast(placeholder.color, placeholder.background)).toBeGreaterThanOrEqual(4.5);
  groups.push('unchecked filter boundaries exceed 3:1 against both adjacent backgrounds and search placeholder exceeds 4.5:1');
  const trigger = page.getByRole('button', { name: '데이터 검색 열기' });
  await trigger.click();
  await expect(page.getByRole('dialog').getByLabel('키워드 검색', { exact: true })).toBeFocused();
  await capture(fixtureOnly ? 'fixture-search-overlay' : 'live-search-overlay');
  await page.keyboard.press('Shift+Tab'); await expect(page.getByRole('button', { name: '데이터 검색 닫기' })).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(page.getByRole('dialog').getByRole('button', { name: '검색', exact: true })).toBeFocused();
  await page.keyboard.press('Escape'); await expect(trigger).toBeFocused(); groups.push('global search initial focus, wrap, Escape, restore');
  mode = 'populated';
  const selected = '?q=시험&sort=title%2Casc&organizationId=' + organization.id + '&categoryId=' + category.id + regions.map(region => '&regionId=' + region.id).join('') + '&topicId=' + topics[0].id + '&hubId=' + hubs[0].id;
  await goto('/datasets' + selected);
  await expect(page.getByRole('navigation', { name: '적용 필터' }).getByRole('link')).toHaveCount(7);
  await expect(page.locator('aside details')).toHaveCount(5);
  await expect(page.locator('input[name="regionId"][type="checkbox"]:checked')).toHaveCount(2);
  const requestQuery = new URL(catalogRequests.findLast(value => value.startsWith('/api/catalog/v1/search?')), 'http://fixture').searchParams;
  expect(requestQuery.getAll('regionId')).toEqual(regions.map(region => region.id));
  expect(requestQuery.getAll('topicId')).toEqual([topics[0].id]); expect(requestQuery.getAll('hubId')).toEqual([hubs[0].id]);
  await page.reload(); await expect(page.getByRole('navigation', { name: '적용 필터' }).getByRole('link')).toHaveCount(7);
  await capture('fixture-selected-list');
  await expect(page.getByRole('status').filter({ hasText: '검색 주제어:' })).toContainText('검색 주제어: 시험');
  await expect(page.getByText('검색 일치 항목', { exact: true })).toHaveCount(6);
  await expect(page.locator('article > p').first()).toContainText('[UI 시험 요약]');
  await page.getByRole('link', { name: '데이터 상세 바로가기', exact: true }).first().click();
  await expect(page).toHaveURL('http://127.0.0.1:3005/datasets/' + datasetId);
  await expect(page.getByRole('heading', { name: detail.title, exact: true })).toBeVisible();
  groups.push('isolated search interpretation, rule evidence, summary and published detail shortcut rendering');
  mode = 'unsafe-link';
  await goto('/datasets?q=시험');
  await expect(page.getByRole('link', { name: '데이터 상세 바로가기', exact: true }).first()).toHaveAttribute('href', '/datasets/' + datasetId);
  await expect(page.locator('a[href^="https://outside.invalid"]')).toHaveCount(0);
  groups.push('search shortcuts use the dataset identifier and ignore untrusted API link targets');
  mode = 'populated';
  await goto('/datasets' + selected);
  const filterDiagnostics = [];
  async function filterSnapshot(stage) {
    filterDiagnostics.push({ stage, at: new Date().toISOString(), url: page.url(), ...await page.locator('input[name="topicId"][type="checkbox"]').evaluate(input => ({ checked: input.checked, defaultChecked: input.defaultChecked, focusMarker: input.dataset.testFocusMarker, detailsOpen: input.closest('details')?.open, visible: input.checkVisibility(), focused: document.activeElement === input, activeTag: document.activeElement?.tagName, activeId: document.activeElement?.id, formData: [...new FormData(input.form)].filter(([key]) => key === 'topicId') })) });
    await writeFile(new URL('filter-diagnostics.json', output), JSON.stringify(filterDiagnostics, null, 2));
  }
  await filterSnapshot('before-toggle');
  const topicCheckbox = page.locator('input[name="topicId"][type="checkbox"]');
  // SSR can stream the new page in a hidden segment before it is revealed.
  // focus() does not wait for visibility; verify the actual keyboard target.
  await expect(topicCheckbox).toBeVisible();
  await topicCheckbox.focus();
  await expect(topicCheckbox).toBeFocused();
  await filterSnapshot('after-focus');
  await page.keyboard.press('Space');
  await expect(topicCheckbox).not.toBeChecked();
  await filterSnapshot('after-space');
  await page.getByRole('button', { name: '필터 적용', exact: true }).click();
  await filterSnapshot('after-apply');
  await expect(page).not.toHaveURL(/topicId=/); await expect(page).toHaveURL(/hubId=/); await expect(page).toHaveURL(/organizationId=/); await expect(page).toHaveURL(/categoryId=/);
  expect(new URL(page.url()).searchParams.getAll('regionId')).toEqual(regions.map(region => region.id));
  await goto('/datasets' + selected);
  const remove = page.getByRole('link', { name: '분야: 환경 필터 해제' }); await remove.focus(); await page.keyboard.press('Enter');
  await expect(page).not.toHaveURL(/categoryId=/); await expect(page).toHaveURL(/organizationId=/); await expect(page).toHaveURL(/q=/); await expect(page).toHaveURL(/sort=title%2Casc/);
  expect(new URL(page.url()).searchParams.getAll('regionId')).toHaveLength(2); await expect(page).toHaveURL(/hubId=/); await expect(page).toHaveURL(/topicId=/);
  await page.getByRole('link', { name: '2 페이지', exact: true }).click(); await expect(page).toHaveURL(/page=1/); await expect(page.getByRole('link', { name: '2 페이지', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('link', { name: '전체 해제', exact: true }).click(); await expect(page).not.toHaveURL(/organizationId=|categoryId=|regionId=|hubId=|topicId=|page=/); await expect(page).toHaveURL(/sort=title%2Casc/);
  groups.push('five independent filter groups; repeated UUIDs for within-group OR and cross-group AND forwarded to API; shared URL reload; keyboard checkbox apply; chips remove one/all with query/sort preserved; numbered pagination');
  await goto('/datasets/' + datasetId); await expect(page.getByText(details.department, { exact: true })).toBeVisible(); await expect(page.getByText('2026-01-01 ~ 종료 미지정', { exact: true })).toBeVisible(); await capture('fixture-rich-overview');
  await page.getByRole('link', { name: '이용정보', exact: true }).click(); await expect(page.getByText(details.license, { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '데이터셋 메타정보 API JSON 조회' })).toHaveAttribute('href', `/api/catalog/v1/datasets/${datasetId}/versions/2`);
  await expect(page.getByRole('button', { name: 'DCAT API 주소 복사', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '허브 API 열기' })).toHaveAttribute('href', detail.hubMetadata.providingApiUri);
  await expect(page.getByRole('link', { name: '데이터 조회 API JSON 조회' })).toHaveAttribute('href', `/api/catalog/v1/datasets/${datasetId}/records?limit=10&offset=0`);
  await capture('fixture-license-access'); groups.push('published metadata API and DCAT links; selected publication version; license metadata');
  await page.getByRole('link', { name: '데이터 미리보기', exact: true }).click();
  await expect(page.getByRole('table')).toBeVisible();await expect(page.getByRole('rowheader')).toHaveCount(10);
  await page.getByRole('button', { name: 'JSON', exact: true }).click();
  await expect(page.getByRole('region', { name: '조회 데이터 JSON' })).toContainText('station-1');
  await page.getByRole('button', { name: '다음 10행' }).click();
  await expect(page.getByRole('region', { name: '조회 데이터 JSON' })).toContainText('station-11');
  await expect(page.getByRole('button', { name: '다음 10행' })).toBeDisabled();
  await page.getByRole('button', { name: '이전 10행' }).click();
  await page.getByRole('button', { name: '표', exact: true }).click();
  await expect(page.getByRole('rowheader')).toHaveCount(10);
  const downloading = page.waitForEvent('download');await page.getByRole('button', { name: '현재 페이지 CSV' }).click();
  expect((await downloading).suggestedFilename()).toMatch(/\.csv$/);
  await expect(page.getByRole('status').filter({ hasText: 'CSV로 내려받았습니다' })).toBeVisible();
  await capture('fixture-records-table');
  mode = 'recordsError';await page.getByRole('button', { name: '새로고침' }).click();
  await expect(page.getByRole('region', { name: '데이터 미리보기', exact: true }).getByRole('alert')).toContainText('허브의 데이터 조회 응답');
  mode = 'recordsUnavailable';await page.getByRole('button', { name: '다시 조회' }).click();
  await expect(page.getByRole('region', { name: '데이터 미리보기', exact: true }).getByRole('alert')).toContainText('데이터 조회 연결이 등록되어 있지 않습니다');
  mode = 'recordsEmpty';await page.getByRole('button', { name: '다시 조회' }).click();
  await expect(page.getByText('조회된 데이터가 없습니다.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '현재 페이지 CSV' })).toBeDisabled();
  mode = 'populated';groups.push('records table/JSON, pagination, actual download response, unavailable/error/retry/empty handling');
  await goto('/datasets/' + datasetId + '?tab=fields&version=2');
  await expect(page.getByRole('table')).toBeVisible(); await expect(page.getByRole('columnheader')).toHaveCount(4); await expect(page.getByRole('rowheader')).toHaveCount(3);
  await capture('fixture-fields-desktop');
  await page.setViewportSize({ width: 390, height: 844 }); await capture('fixture-fields-mobile');
  await page.getByRole('region', { name: '공개 필드 목록' }).focus(); await expect(page.getByRole('region', { name: '공개 필드 목록' })).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => page.getByRole('region', { name: '공개 필드 목록' }).evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  await page.getByRole('button', { name: '메타정보 JSON 보기' }).click(); await expect(page.getByRole('region', { name: '공개 메타정보 JSON 내용' })).toBeFocused();
  await capture('fixture-json-mobile'); await expect(page.getByRole('region', { name: '공개 메타정보 JSON 내용' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect.poll(() => page.getByRole('region', { name: '공개 메타정보 JSON 내용' }).evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: '메타정보 JSON 보기' })).toBeFocused();
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    document.execCommand = command => command === 'copy' && document.activeElement instanceof HTMLTextAreaElement && JSON.parse(document.activeElement.value).publicationNo === 2;
  });
  await page.getByRole('button', { name: '메타정보 복사', exact: true }).click(); await expect(page.getByRole('status')).toContainText('메타정보를 복사했습니다.');
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('UI fixture permission denied')) } });
    document.execCommand = () => false;
  });
  await page.getByRole('button', { name: '메타정보 복사', exact: true }).click(); await expect(page.getByRole('status')).toContainText('복사하지 못했습니다');
  groups.push('filled field table semantic headers/caption; mobile long data; JSON scroll/focus; denied clipboard truthful feedback');
  await goto('/datasets' + selected); await capture('fixture-selected-mobile');
  await page.setViewportSize({ width: 1920, height: 1080 });
  mode = 'noFields'; await goto('/datasets/' + datasetId); await expect(page.getByText('등록된 설명이 없습니다.')).toBeVisible(); await expect(page.getByText('미지정', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: '필드정보', exact: false }).click(); await expect(page.getByText('공개된 필드정보가 없습니다.')).toBeVisible(); await capture('fixture-empty-fields'); groups.push('nullable category/description and empty fields');
  mode = 'empty'; await goto('/datasets?q=시험'); await expect(page.getByRole('heading', { name: '검색 결과가 없습니다' })).toBeVisible(); await capture('fixture-empty-search'); groups.push('empty filtered result');
  mode = 'partialError'; await goto('/datasets/' + datasetId + '?tab=access&version=2'); await expect(page.getByText('버전 목록을 불러오지 못했습니다.')).toBeVisible(); await expect(page.getByRole('link', { name: '데이터셋 메타정보 API JSON 조회' })).toHaveAttribute('href', `/api/catalog/v1/datasets/${datasetId}/versions/2`); await capture('fixture-partial-error'); groups.push('versions failure keeps valid detail and metadata API links');
  mode = 'error'; await goto('/datasets/' + datasetId + '?tab=fields&version=2'); await expect(page.locator('main').getByRole('alert')).toContainText('불러오지 못했습니다'); await expect(page.getByRole('link', { name: '다시 시도' })).toHaveAttribute('href', '/datasets/' + datasetId + '?tab=fields&version=2'); await capture('fixture-detail-error');
  await goto('/datasets' + selected); await expect(page.locator('main').getByRole('alert')).toContainText('불러오지 못했습니다'); await capture('fixture-list-error'); groups.push('failed detail/list and retry preserves pinned version/tab/query');
  mode = 'redirect'; await goto('/datasets');
  await expect(page.locator('main').getByRole('alert')).toContainText('불러오지 못했습니다');
  expect(redirectedRequests).toBe(0);
  groups.push('catalog server requests refuse upstream redirects without contacting the redirect destination');
  mode = 'notFound'; await goto('/datasets/' + datasetId); await expect(page.getByRole('heading', { name: '공개된 데이터셋을 찾을 수 없습니다' })).toBeVisible(); await capture('fixture-not-found'); groups.push('404 publication unavailable');
  mode = 'loading';
  await page.goto('http://127.0.0.1:3005/datasets/' + datasetId + '?tab=fields&version=2', { waitUntil: 'commit' });
  await expect(page.getByRole('status')).toContainText('불러오는 중'); await capture('fixture-loading');
  await expect(page.getByRole('table')).toBeVisible(); groups.push('SSR loading announcement resolves to populated table');
  expect(errors).toEqual([]);
  await writeFile(new URL('results.json', output), JSON.stringify({ verifiedAt: new Date().toISOString(), fixtureScope: 'isolated Next SSR upstream GET route fixtures; actual runtime unchanged', actualAuthenticatedSession: false, databaseWrites: false, groups, errors, snapshots }, null, 2));
  console.log(JSON.stringify({ groups: groups.length, screenshots: snapshots.length, errors }));
} finally {
  await browser?.close();
  if (child && child.exitCode === null) { if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); else child.kill('SIGTERM'); }
  if (api) await new Promise(resolve => api.close(resolve));
  await writeFile(new URL('cleanup.json', output), JSON.stringify({ capturedAt: new Date().toISOString(), ownedNextPid: child?.pid, fixtureServerClosed: !api?.listening, realServicesRestarted: false, workspace }, null, 2));
}
