/** Isolated UI contract checks only: every /api response is a test fixture, never real SSO or hub success. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '../../web/node_modules/playwright/index.mjs';
import { expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const output = path.join(root, 'admin/test-results/hubs');
await mkdir(output, { recursive: true });
const server = spawn(process.execPath, [path.join(root, 'admin/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '3004', '--strictPort'], { cwd: path.join(root, 'admin'), windowsHide: true, stdio: 'pipe' });
let serverError = '';
server.stderr.on('data', chunk => { serverError += chunk.toString(); });
server.stdout.on('data', chunk => { serverError += chunk.toString(); });
server.on('error', error => { serverError += error.message; });
const origin = 'http://127.0.0.1:3004';
let browser;
const passed = [];
try {
  for (let count = 0; count < 100; count++) {
    if (server.exitCode !== null) throw new Error(`UI test server exited: ${serverError}`);
    try { const response = await fetch(origin); if (response.ok) break; } catch { /* waiting for the isolated local dev server */ }
    if (count === 99) throw new Error(`UI test server did not start: ${serverError}`);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  const org = '10000000-0000-0000-0000-000000000001';
  const connectionId = '20000000-0000-0000-0000-000000000001';
  const candidateId = '30000000-0000-0000-0000-000000000001';
  const datasetId = '40000000-0000-0000-0000-000000000001';
  let anonymous = false, forbidden = false, empty = false, conflict = false;
  let writes = 0;
  const unexpected = [], errors = [];
  const connection = { id: connectionId, organizationId: org, name: '[UI 계약 시험] 시험 연결', adapterCode: 'UNCONTRACTED', endpointConfigured: false, metadataEndpoint: null, recordsEndpoint: null, credentialConfigured: true, enabled: false, timeoutSeconds: 10, maxAttempts: 3, maxPages: 20, lockVersion: 0, adapterAvailable: false, capabilities: [], updatedAt: '2026-10-16T08:00:00Z' };
  const candidate = { id: candidateId, connectionId, externalId: 'test-source-1', title: '[UI 계약 시험] 수집 후보', revisionNo: 1, state: 'NEW', datasetId: null, updatedAt: '2026-10-16T08:01:00Z' };
  await context.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method();
    const response = (data, status = 200, headers = {}) => route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(data) });
    if (url.pathname === '/api/catalog/v1/datasets') return response({ items: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } });
    if (url.pathname === '/api/admin/v1/session') return anonymous ? response({ code: 'AUTHENTICATION_REQUIRED' }, 401) : response({ userId: '90000000-0000-0000-0000-000000000001', organizationIds: [org], authorities: ['ROLE_ORG_EDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'ui-fixture-csrf-only' });
    if (url.pathname === '/api/admin/v1/metadata-options') return response({ organizations: [{ id: org, name: '[UI 계약 시험] 시험 기관' }], categories: [] });
    if (url.pathname === '/api/admin/v1/connections') return forbidden ? response({ code: 'ACCESS_DENIED' }, 403) : response(empty ? [] : [connection]);
    if (url.pathname === `/api/admin/v1/connections/${connectionId}`) {
      if (method === 'PATCH') {
        const body = request.postDataJSON();
        assert.equal(request.headers()['if-match'], `"connection:${connectionId}:0"`);
        assert.equal(request.headers()['x-csrf-token'], 'ui-fixture-csrf-only');
        assert.equal(body.secretReference, 'fixture/reference-only');
        connection.name = body.name; connection.lockVersion++; writes++;
      }
      return response(connection, 200, { ETag: `"connection:${connectionId}:${connection.lockVersion}"` });
    }
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/schedule`) return response({ connectionId, enabled: false, intervalSeconds: 3600, lockVersion: 0, workerEnabled: true }, 200, { ETag: `"schedule:${connectionId}:0"` });
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/publication-policy`) return response({ connectionId, enabled: false, department: null, license: null, keywords: [], defaultCategoryId: null, lockVersion: 0 }, 200, { ETag: `"publication-policy:${connectionId}:0"` });
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/publication-results`) return response({ items: [], pendingCount: 0, runningCount: 0 });
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/sync-runs`) return response([]);
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/audit-events`) return response([{ id: '50000000-0000-0000-0000-000000000001', actorId: '90000000-0000-0000-0000-000000000001', action: 'CONNECTION_CREATED', requestId: 'ui-fixture-request', configurationVersion: 0, occurredAt: '2026-10-16T08:00:00Z' }]);
    if (url.pathname === `/api/admin/v1/connections/${connectionId}/candidates`) return response({ items: [candidate], page: 0, size: 20, totalElements: 1 });
    if (url.pathname === `/api/admin/v1/candidates/${candidateId}`) return response({ candidate, description: '테스트 설명', sourceHash: 'a'.repeat(64), etag: `"candidate:${candidateId}:1:0"`, targetEtag: null, targetState: null, conflict, changes: [{ field: 'title', previousSourceValue: null, currentValue: conflict ? '수동 편집값' : null, sourceValue: candidate.title, sourceChanged: true, manuallyChanged: conflict, conflict, action: conflict ? 'CONFLICT' : 'APPLY_SOURCE' }, { field: 'description', previousSourceValue: null, currentValue: null, sourceValue: '테스트 설명', sourceChanged: true, manuallyChanged: false, conflict: false, action: 'APPLY_SOURCE' }] });
    if (url.pathname === `/api/admin/v1/candidates/${candidateId}/applications` && method === 'POST') {
      assert.equal(request.headers()['if-match'], `"candidate:${candidateId}:1:0"`);
      assert.ok(request.headers()['idempotency-key']); assert.equal(request.postDataJSON().targetEtag, null);
      writes++; candidate.state = 'APPLIED'; candidate.datasetId = datasetId;
      return response({ id: '60000000-0000-0000-0000-000000000001', candidateId, sourceHash: 'a'.repeat(64), datasetId, revisionId: '70000000-0000-0000-0000-000000000001', lockVersion: 0, appliedAt: '2026-10-16T08:02:00Z' });
    }
    unexpected.push(`${method} ${url.pathname}`); return response({ code: 'UNEXPECTED_TEST_REQUEST' }, 500);
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${origin}/hubs`);
  await expect(page.getByRole('heading', { name: '카탈로그 연결 설정', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toHaveAccessibleDescription(/서버에 해당 연동 기능이 등록되어 있지 않아/);
  await expect(page.getByRole('list', { name: '메타데이터 수집과 검토' }).getByRole('listitem')).toHaveCount(3);
  await expect(page.getByText('서버에 해당 연동 기능이 등록되어 있지 않아', { exact: false })).toBeVisible();
  await expect(page.getByText('fixture/reference-only')).toHaveCount(0);
  const assetSizes = [[20, 20], [20, 20], [20, 20], [36, 36], [27, 27], [15, 20], [15, 20], [16, 16]];
  await expect(page.locator('main img')).toHaveCount(assetSizes.length);
  for (const [index, [width, height]] of assetSizes.entries()) {
    const img = page.locator('main img').nth(index);
    await expect(img).toBeVisible();
    const shape = await img.evaluate(element => ({ width: element.width, height: element.height, naturalWidth: element.naturalWidth, naturalHeight: element.naturalHeight }));
    assert.equal(shape.width, width, `asset ${index}`); assert.equal(shape.height, height, `asset ${index}`);
    assert.equal(shape.naturalWidth, width, `asset ${index}`); assert.equal(shape.naturalHeight, height, `asset ${index}`);
  }
  await auditAccessibility(page, 'hubs-desktop');
  await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
  passed.push('contract-only connection state and local asset dimensions');

  const edit = page.getByRole('button', { name: '설정 수정', exact: true });
  await edit.click(); await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').getByRole('heading', { name: connection.name, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  await expect(page.getByLabel('서버 비밀 참조', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('서버 비밀 참조', { exact: true })).toHaveAccessibleDescription(/서버에 등록한 참조명만 입력/);
  await expect(page.getByLabel(/^메타데이터 API 주소/)).toHaveAccessibleDescription(/비워 두면 기존 설정을 유지/);
  await auditAccessibility(page, 'hubs-edit-empty');
  await expect(page.getByRole('button', { name: '취소', exact: true })).toBeInViewport();
  await page.screenshot({ path: path.join(output, 'edit-empty.png') });
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible(); await expect(edit).toBeFocused();
  await edit.click();
  await page.getByLabel(/^연결 이름/).fill('[UI 계약 시험] 변경한 연결');
  await page.getByLabel(/^서버 비밀 참조/).fill('fixture/reference-only');
  await page.getByLabel(/^메타데이터 API 주소/).fill('https://ui-fixture.invalid/metadata');
  await expect(page.getByLabel('연결 프로토콜', { exact: true })).toHaveValue('https');
  await expect(page.getByLabel(/^메타데이터 API 주소/)).toHaveValue('ui-fixture.invalid/metadata');
  const clear = page.getByRole('button', { name: '입력한 연결 주소 지우기', exact: true });
  const clearGeometry = await clear.boundingBox();
  assert.equal(clearGeometry.width, 44); assert.equal(clearGeometry.height, 44);
  const clearImage = await clear.locator('img').boundingBox();
  assert.equal(clearImage.width, 12.5); assert.equal(clearImage.height, 12.5);
  await auditAccessibility(page, 'hubs-edit-filled');
  await page.screenshot({ path: path.join(output, 'edit-filled.png') });
  await clear.focus(); await page.keyboard.press('Enter');
  await expect(page.getByLabel(/^메타데이터 API 주소/)).toBeFocused();
  await expect(page.getByLabel(/^메타데이터 API 주소/)).toHaveValue('');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible(); assert.equal(writes, 1);
  passed.push('write-only reference, labelled field help, address clear geometry/focus, If-Match and CSRF, Escape focus restore');

  await page.getByRole('button', { name: '변경 비교', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('region', { name: '허브 변경 비교 표', exact: true }).focus();
  await expect(page.getByRole('region', { name: '허브 변경 비교 표', exact: true })).toBeFocused();
  await auditAccessibility(page, 'hubs-candidate-comparison');
  await page.screenshot({ path: path.join(output, 'candidate-comparison.png'), fullPage: true });
  await page.getByRole('button', { name: '비공개 초안 생성', exact: true }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByRole('link', { name: '메타데이터에서 확인' })).toHaveAttribute('href', `/metadata?datasetId=${datasetId}`); assert.equal(writes, 2);
  conflict = true; candidate.state = 'UPDATED';
  await page.getByRole('button', { name: '변경 비교', exact: true }).click();
  await expect(page.getByRole('button', { name: '허브 변경을 초안에 적용' })).toBeDisabled();
  await page.keyboard.press('Escape');
  passed.push('candidate comparison, explicit application request and manual-conflict guard');

  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 390), 'mobile document overflow');
  await auditAccessibility(page, 'hubs-mobile');
  await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
  await edit.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  assert.ok(await page.getByRole('dialog').evaluate(element => element.scrollWidth <= element.clientWidth), 'mobile editor overflow');
  await auditAccessibility(page, 'hubs-mobile-editor');
  await expect(page.getByRole('button', { name: '취소', exact: true })).toBeInViewport();
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeInViewport();
  await page.screenshot({ path: path.join(output, 'mobile-editor.png') });
  await page.keyboard.press('Escape');
  passed.push('390px page and editor have no horizontal document overflow');

  empty = true; await page.reload(); await expect(page.getByRole('heading', { name: '등록된 허브 연결이 없습니다' })).toBeVisible();
  forbidden = true; await page.getByRole('button', { name: '새로고침', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('권한');
  anonymous = true; await page.reload(); await expect(page.getByRole('heading', { name: '관리자 로그인이 필요합니다' })).toBeVisible();
  passed.push('empty, forbidden and anonymous session states stay explicit');
  assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
  await writeFile(path.join(output, 'result.json'), JSON.stringify({ kind: 'ISOLATED_UI_CONTRACT_FIXTURES', realSso: false, realHub: false, passed, browserErrors: errors, unexpectedApiRequests: unexpected }, null, 2));
  console.log(JSON.stringify({ result: 'PASS', checks: passed.length, output, realSso: false, realHub: false }));
} finally {
  await browser?.close();
  server.kill();
}
