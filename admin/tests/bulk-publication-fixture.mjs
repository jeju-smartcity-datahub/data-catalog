/** Isolated browser contract checks. API responses here are fixtures, not hub integration evidence. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '../../web/node_modules/playwright/index.mjs';
import { expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';
import { assertControlContrast } from './control-contrast.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const output = path.join(root, 'admin/test-results/bulk-publication');
await mkdir(output, { recursive: true });
const server = spawn(process.execPath, [path.join(root, 'admin/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '3016', '--strictPort'], { cwd: path.join(root, 'admin'), windowsHide: true, stdio: 'pipe' });
let serverLog = ''; server.stdout.on('data', value => serverLog += value); server.stderr.on('data', value => serverLog += value);
const origin = 'http://127.0.0.1:3016';
let browser;
const passed = [];
const contrast = [];
try {
  for (let count = 0; count < 100; count++) {
    if (server.exitCode !== null) throw new Error(serverLog);
    try { if ((await fetch(origin)).ok) break; } catch { /* Vite startup */ }
    if (count === 99) throw new Error(serverLog);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  const org = '10000000-0000-0000-0000-000000000001', connectionId = '20000000-0000-0000-0000-000000000001';
  const id = number => `30000000-0000-0000-0000-${String(number).padStart(12, '0')}`;
  let total = 10, editorOnly = false, policyConflict = false, lostResponse = false;
  let previewCalls = 0, publishCalls = 0, policyWrites = 0;
  let policy = { connectionId, enabled: false, department: '교통정책과', license: null, keywords: [], defaultCategoryId: null, lockVersion: 0 };
  let history = [];
  let lastKey, lastBody;
  const errors = [], unexpected = [];
  let savedMetadata = null;
  const draft = { id: id(200), slug: 'jeju-parking', ownerOrganizationId: org, revisionId: id(201), revisionNo: 1, state: 'DRAFT', title: '공영주차장 현황', description: '공영주차장별 주차면 현황', providerOrganizationId: org, primaryCategoryId: '40000000-0000-0000-0000-000000000001', visibility: 'PUBLIC', lockVersion: 1, currentPublicationId: null, reviewRequestId: null, reviewDecisionId: null, regionIds: [id(202)], hubIds: [id(203)], topicIds: [id(204)], details: { department: '교통정책과', license: '공공누리 제1유형', keywords: ['주차장', '주차면'], updateFrequency: '1일', spatialCoverage: '시험 지역', temporalStart: '2026-01-01', temporalEnd: '2026-10-28' } };
  const candidates = () => Array.from({ length: total }, (_, index) => ({ id: id(index + 1), connectionId, externalId: `parking-${index + 1}`, title: `주차장 현황 ${index + 1}`, revisionNo: 1, state: 'NEW', datasetId: null, updatedAt: '2026-10-28T01:00:00Z' }));
  const connection = { id: connectionId, organizationId: org, name: '시험 데이터허브', adapterCode: 'KETI_DATACORE', endpointConfigured: true, credentialConfigured: false, enabled: true, timeoutSeconds: 10, maxAttempts: 3, maxPages: 100, lockVersion: 0, adapterAvailable: true, capabilities: ['TEST_CONNECTION', 'DISCOVER_DATASETS'], updatedAt: '2026-10-28T01:00:00Z' };
  await context.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method();
    const response = (data, status = 200, headers = {}) => route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(data) });
    const suffix = url.pathname.replace(`/api/admin/v1/connections/${connectionId}`, '');
    if (url.pathname === '/api/catalog/v1/datasets') return response({ items: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } });
    if (url.pathname === '/api/catalog/v1/dcat') return route.fulfill({ contentType: 'application/ld+json', headers: { 'X-Total-Count': '10' }, body: JSON.stringify({ '@graph': Array.from({ length: 10 }, (_, index) => ({ '@id': `urn:catalog:fixture:${index}`, '@type': 'dcat:Dataset', 'dct:title': `공개 데이터셋 ${index + 1}` })) }, null, 2) });
    if (url.pathname === '/api/admin/v1/audit-events') return response({ items: [], totalElements: 0, page: 0, size: 20 });
    if (url.pathname === '/api/admin/v1/session') return response({ userId: '90000000-0000-0000-0000-000000000001', organizationIds: [org], authorities: editorOnly ? ['ROLE_ORG_EDITOR'] : ['ROLE_ORG_EDITOR', 'ROLE_ORG_REVIEWER', 'ROLE_ORG_PUBLISHER'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'fixture-only' });
    if (url.pathname === '/api/admin/v1/metadata-options') return response({ organizations: [{ id: org, name: '시험 지역' }], categories: [{ id: '40000000-0000-0000-0000-000000000001', name: '교통물류' }], regions: [{ id: id(202), name: '시험 지역' }], hubs: [{ id: id(203), name: '시험 데이터허브' }], topics: [{ id: id(204), name: '주차', parentId: '40000000-0000-0000-0000-000000000001' }] });
    if (url.pathname === '/api/admin/v1/datasets') return response({ items: [draft], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } });
    if (url.pathname === `/api/admin/v1/datasets/${draft.id}/draft`) {
      if (method === 'PATCH') { assert.equal(request.headers()['if-match'], `"dataset:${draft.id}:1"`); savedMetadata = request.postDataJSON(); }
      return response(draft, 200, { ETag: `"dataset:${draft.id}:1"` });
    }
    if (url.pathname === `/api/admin/v1/datasets/${draft.id}/draft/fields`) return response({ revisionId: draft.revisionId, lockVersion: 1, fields: [] }, 200, { ETag: `"dataset:${draft.id}:1"` });
    if (url.pathname === `/api/admin/v1/datasets/${draft.id}/data-access`) return response({ datasetId: draft.id, configured: false, connectionId: null, enabled: false, ready: false, lockVersion: 0, mode: 'KETI_DATASET', queryPath: '/entities', scopeField: 'datasetId', scopeValue: 'parking', scopeParameter: 'datasetId', fixedQuery: {}, checkedAt: null }, 200, { ETag: `"data-access:${draft.id}:0"` });
    if (url.pathname === '/api/admin/v1/connections') return response([connection]);
    if (suffix === '/sync-runs' || suffix === '/audit-events') return response([]);
    if (suffix === '/schedule') return response({ connectionId, enabled: false, intervalSeconds: 3600, lockVersion: 0, workerEnabled: true }, 200, { ETag: `"schedule:${connectionId}:0"` });
    if (suffix === '/publication-policy') {
      if (method === 'PUT') {
        assert.equal(request.headers()['if-match'], `"publication-policy:${connectionId}:${policy.lockVersion}"`);
        assert.equal(request.headers()['x-csrf-token'], 'fixture-only');
        if (policyConflict) return response({ code: 'PRECONDITION_FAILED' }, 412);
        policyWrites++; policy = { ...policy, ...request.postDataJSON(), lockVersion: policy.lockVersion + 1 };
      }
      return response(policy, 200, { ETag: `"publication-policy:${connectionId}:${policy.lockVersion}"` });
    }
    if (suffix === '/publication-results') return response({ items: history });
    if (suffix === '/candidates') {
      assert.equal(url.searchParams.get('size'), '10');
      const page = Number(url.searchParams.get('page'));
      return response({ items: candidates().slice(page * 10, page * 10 + 10), page, size: 10, totalElements: total });
    }
    if (suffix === '/publication-preview') {
      previewCalls++;
      const selected = request.postDataJSON().candidateIds;
      return response({ policy, items: selected.map(candidateId => ({ candidateId, title: candidates().find(item => item.id === candidateId).title, etag: `"candidate:${candidateId}:1:0"`, targetEtag: null, publishable: Boolean(policy.license) && candidateId !== id(10), missingFields: candidateId === id(10) ? ['설명'] : policy.license ? [] : ['라이선스'], categoryId: '40000000-0000-0000-0000-000000000001', categoryName: '교통물류', alreadyPublished: false })) });
    }
    if (suffix === '/publications') {
      publishCalls++;
      const body = request.postDataJSON(), key = request.headers()['idempotency-key'];
      assert.equal(body.policyVersion, policy.lockVersion);
      assert.ok(key); assert.equal(request.headers()['x-csrf-token'], 'fixture-only');
      assert.ok(body.items.every(item => item.candidateId !== id(10)));
      assert.ok(body.items.every(item => item.etag === `"candidate:${item.candidateId}:1:0"`));
      if (lostResponse) {
        if (!lastKey) { lastKey = key; lastBody = body; return route.abort('failed'); }
        assert.equal(key, lastKey); assert.deepEqual(body, lastBody);
      }
      const items = body.items.map((item, index) => ({ candidateId: item.candidateId, datasetId: item.candidateId, publicationId: item.candidateId, status: index < 2 ? 'FAILED' : 'PUBLISHED', code: index < 2 ? 'PRECONDITION_FAILED' : null, message: index < 2 ? '변경 내용을 다시 확인하세요.' : '게시했습니다.' }));
      history = items.map(item => ({ ...item, title: candidates().find(candidate => candidate.id === item.candidateId).title, mode: 'MANUAL', occurredAt: '2026-10-28T00:59:59Z' }));
      return response({ items, published: Math.max(0, items.length - 2), skipped: 0, failed: Math.min(2, items.length) });
    }
    unexpected.push(`${method} ${url.pathname}`); return response({ code: 'UNEXPECTED_TEST_REQUEST' }, 500);
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${origin}/hubs`);
  await expect(page.getByRole('heading', { name: '수집한 데이터', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '선택 게시', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: '현재 목록 전체 선택' }).check();
  await expect(page.getByRole('status').filter({ hasText: '10건 선택 · 현재 목록 10건' })).toBeVisible();
  await page.getByRole('button', { name: '선택 게시', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: '선택 항목 게시' });
  await expect(dialog.getByLabel(/^라이선스/)).toBeVisible();
  await expect(dialog.getByLabel('담당부서', { exact: true })).toHaveCount(0);
  await expect(dialog.getByLabel('키워드', { exact: true })).toHaveCount(0);
  await expect(dialog.getByText('설명', { exact: true })).toBeVisible();
  contrast.push(await assertControlContrast(dialog.getByLabel(/^라이선스/), '일괄 게시 라이선스 입력'));
  contrast.push(await assertControlContrast(dialog.getByRole('button', { name: '취소', exact: true }), '보조 버튼'));
  await dialog.getByLabel(/^라이선스/).fill('공공누리 제1유형');
  await auditAccessibility(page, 'batch-publication-required');
  await dialog.screenshot({ path: path.join(output, 'batch-missing-license.png') });
  await dialog.getByRole('button', { name: '선택 게시', exact: true }).click();
  await expect(dialog.getByRole('status').filter({ hasText: '게시 7건 · 제외 1건 · 실패 2건' })).toBeVisible();
  assert.equal(policyWrites, 1); assert.equal(policy.enabled, false); assert.equal(policy.department, '교통정책과'); assert.equal(policy.license, '공공누리 제1유형');
  assert.equal(publishCalls, 1); assert.ok(previewCalls >= 2);
  await expect(dialog.getByText('확인 필요', { exact: true })).toHaveCount(1);
  await dialog.screenshot({ path: path.join(output, 'batch-result.png') });
  await dialog.getByRole('button', { name: '닫기', exact: true }).click();
  await expect(page.getByText('0건 선택 · 현재 목록 10건')).toBeVisible();
  await expect(page.getByText(/^최근 선택 게시: 성공 ·/)).toHaveCount(7);
  await expect(page.getByText(/^최근 선택 게시: 실패 ·/)).toHaveCount(2);
  await expect(page.getByRole('region', { name: '수집 후보 표' }).getByText('신규', { exact: true })).toHaveCount(10);
  await expect(page.getByText('게시 완료', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('region', { name: '수집 후보 표' }).locator('time[datetime="2026-10-28T00:59:59Z"]')).toHaveCount(9);
  passed.push('현재 페이지 10건 선택, 누락 라이선스만 공통 입력, 9건 게시 요청 및 부분 실패 표시, 현재 수집 상태와 최근 게시 이력 분리');

  await page.getByRole('tab', { name: '설정', exact: true }).click();
  await page.getByRole('button', { name: '게시 설정 수정' }).click();
  dialog = page.getByRole('dialog', { name: '게시 설정', exact: true });
  await expect(dialog.getByRole('checkbox', { name: '수집 후 바로 게시' })).not.toBeChecked();
  await expect(dialog.getByLabel('기본 분류')).not.toBeVisible();
  await dialog.getByRole('checkbox', { name: '수집 후 바로 게시' }).check();
  await auditAccessibility(page, 'publication-policy');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  assert.equal(policy.enabled, true);
  await page.reload();
  await page.getByRole('tab', { name: '설정', exact: true }).click();
  await expect(page.getByText('수집 후 바로 게시', { exact: true })).toBeVisible();
  passed.push('자동 게시 기본 해제, 체크 후 ETag 저장, 새로고침 후 유지');

  policyConflict = true;
  await page.getByRole('button', { name: '게시 설정 수정' }).click();
  dialog = page.getByRole('dialog', { name: '게시 설정', exact: true });
  await dialog.getByRole('checkbox', { name: '수집 후 바로 게시' }).uncheck();
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('다른 담당자가 내용을 변경');
  await expect(dialog.getByRole('checkbox', { name: '수집 후 바로 게시' })).not.toBeChecked();
  assert.equal(policy.enabled, true);
  page.once('dialog', prompt => prompt.accept());
  await dialog.getByRole('button', { name: '닫기', exact: true }).click();
  policyConflict = false;
  passed.push('정책 동시 변경 412 시 실패 표시 및 입력 유지, 저장 성공으로 표시하지 않음');

  lostResponse = true;
  await page.getByRole('tab', { name: '수집 데이터', exact: true }).click();
  await page.getByRole('checkbox', { name: '주차장 현황 3 선택', exact: true }).check();
  await page.getByRole('button', { name: '선택 게시', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '선택 항목 게시' });
  await dialog.getByRole('button', { name: '선택 게시', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('서버 응답을 확인하지 못했습니다');
  await dialog.getByRole('button', { name: '게시 결과 다시 확인' }).click();
  await expect(dialog.getByText('게시 0건 · 제외 0건 · 실패 1건', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '닫기', exact: true }).click();
  passed.push('게시 응답 유실 시 동일 요청 ID와 본문으로 결과 재확인');

  total = 11; await page.reload();
  await page.getByRole('checkbox', { name: '현재 목록 전체 선택' }).check();
  await expect(page.getByText('10건 선택 · 현재 목록 10건')).toBeVisible();
  await page.getByRole('navigation', { name: '수집 데이터 페이지' }).getByRole('button', { name: '수집 데이터 다음 페이지' }).click();
  await expect(page.getByText('0건 선택 · 현재 목록 1건')).toBeVisible();
  await page.getByRole('checkbox', { name: '주차장 현황 11 선택', exact: true }).check();
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await expect(page.getByText('1건 선택 · 현재 목록 1건')).toBeVisible();
  passed.push('10건 페이지 선택 범위 명시, 페이지 변경 시 선택 초기화 및 새로고침 시 선택 유지');

  editorOnly = true; await page.reload();
  await expect(page.getByRole('button', { name: '선택 게시', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '게시 설정 수정' })).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: '현재 목록 전체 선택' })).toBeDisabled();
  passed.push('편집 권한 단독 계정에 일괄 게시·자동 게시 설정 비활성화');
  await page.goto(`${origin}/metadata?datasetId=${draft.id}`);
  dialog = page.getByRole('dialog', { name: '메타데이터 상세', exact: true });
  await expect(dialog.getByRole('status').filter({ hasText: '필수 7개 중 7개 입력 · 입력 완료' })).toBeVisible();
  const basicTab = dialog.getByRole('tab', { name: '기본정보' });
  await basicTab.focus();
  await basicTab.press('ArrowRight');
  await expect(dialog.getByRole('tab', { name: /^필드정보/ })).toBeFocused();
  await expect(dialog.getByRole('tab', { name: /^필드정보/ })).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('End');
  await expect(dialog.getByRole('tab', { name: '데이터 이용 경로' })).toBeFocused();
  await page.keyboard.press('Home');
  await expect(basicTab).toBeFocused();
  await expect(basicTab).toHaveAttribute('aria-selected', 'true');
  passed.push('메타데이터 탭의 화살표·Home·End 이동 및 선택 상태 유지');
  contrast.push(await assertControlContrast(dialog.getByRole('textbox', { name: /^데이터셋 이름/ }), '메타데이터 이름 입력'));
  contrast.push(await assertControlContrast(dialog.getByRole('textbox', { name: /^데이터 설명/ }), '메타데이터 설명 입력'));
  contrast.push(await assertControlContrast(dialog.getByRole('combobox', { name: /^제공기관/ }), '제공기관 선택'));
  for (const name of ['데이터셋 이름', '데이터 설명', '담당부서', '라이선스', '키워드']) await expect(dialog.getByRole('textbox', { name: new RegExp(`^${name}`) })).toBeVisible();
  for (const name of ['제공기관', '공개 범위']) await expect(dialog.getByRole('combobox', { name: new RegExp(`^${name}`) })).toBeVisible();
  await expect(dialog.getByRole('combobox', { name: '분야', exact: true })).not.toBeVisible();
  await expect(dialog.getByLabel('데이터 갱신 주기', { exact: true })).not.toBeVisible();
  await dialog.getByText('추가 정보 (선택)', { exact: true }).click();
  await expect(dialog.getByRole('combobox', { name: '분야', exact: true })).toHaveValue(draft.primaryCategoryId);
  await expect(dialog.getByRole('textbox', { name: /^데이터 갱신 주기/ })).toHaveValue('1일');
  await expect(dialog.getByLabel('기간 시작일', { exact: true })).toHaveValue('2026-01-01');
  contrast.push(await assertControlContrast(dialog.locator('summary').filter({ hasText: '시험 데이터허브' }), '연결 허브 선택'));
  await dialog.getByText('추가 정보 (선택)', { exact: true }).click();
  await dialog.getByRole('textbox', { name: /^데이터셋 이름/ }).fill('공영주차장 현황 수정');
  await basicTab.focus();
  await basicTab.press('ArrowRight');
  await expect(basicTab).toHaveAttribute('aria-selected', 'true');
  await expect(dialog.getByRole('alert')).toContainText('변경한 내용을 먼저 저장');
  await dialog.screenshot({ path: path.join(output, 'metadata-required-fields.png') });
  await dialog.getByRole('button', { name: '변경 저장', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  assert.equal(savedMetadata.title, '공영주차장 현황 수정');
  assert.equal(savedMetadata.primaryCategoryId, draft.primaryCategoryId);
  assert.deepEqual(savedMetadata.regionIds, draft.regionIds); assert.deepEqual(savedMetadata.hubIds, draft.hubIds); assert.deepEqual(savedMetadata.topicIds, draft.topicIds);
  assert.deepEqual(savedMetadata.details, draft.details);
  passed.push('메타데이터 필수 7개 표시·선택 필드 기본 접기·기존 분류/지역/주제/기간/갱신주기 저장 유지');
  await expect(page.getByRole('textbox', { name: '검색', exact: true })).toBeVisible();
  contrast.push(await assertControlContrast(page.getByRole('textbox', { name: '검색', exact: true }), '메타데이터 검색'));
  await page.setViewportSize({ width: 768, height: 1080 });
  const table = page.getByRole('region', { name: '접근 가능한 기관의 메타데이터 표' });
  await table.focus();
  await table.press('ArrowRight');
  await expect.poll(() => table.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  passed.push('메타데이터 표의 이름 있는 스크롤 영역에서 키보드 가로 이동');
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.goto(`${origin}/access`);
  await expect(page.getByRole('textbox', { name: '작업 검색', exact: true })).toBeVisible();
  contrast.push(await assertControlContrast(page.getByRole('textbox', { name: '작업 검색', exact: true }).locator('..'), '작업 이력 검색'));
  await auditAccessibility(page, 'admin-search-labels');
  await page.goto(`${origin}/dcat`);
  await expect(page.getByRole('button', { name: 'JSON-LD', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('status').filter({ hasText: 'JSON-LD 조회 완료' })).toHaveText('JSON-LD 조회 완료 · 공개 제공 대상 10개 · 1페이지');
  contrast.push(await assertControlContrast(page.getByRole('button', { name: 'JSON-LD', exact: true }), 'DCAT 선택 형식', 'inset'));
  const dcatPreview = page.getByRole('region', { name: 'JSON-LD 공개 메타데이터 원문', exact: true });
  await expect(dcatPreview).toBeVisible();
  assert.equal(JSON.parse(await dcatPreview.locator('pre > code').textContent())['@graph'].length, 10);
  await dcatPreview.focus(); await expect(dcatPreview).toBeFocused();
  await dcatPreview.press('ArrowDown');
  await expect.poll(() => dcatPreview.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  await auditAccessibility(page, 'admin-dcat-selected-format');
  passed.push('검색 입력의 접근 가능한 이름, 입력·선택·보조 버튼 경계 3:1 이상 및 DCAT 선택 표시');
  assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
  await writeFile(path.join(output, 'result.json'), JSON.stringify({ passed, contrast, browserErrors: errors, unexpected }, null, 2));
  console.log(JSON.stringify({ passed, contrast }, null, 2));
} finally { if (browser) await browser.close(); server.kill(); }
