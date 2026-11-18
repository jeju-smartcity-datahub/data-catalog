/** Synthetic browser/API contract fixture. No real login, hub, DB or TLS claim. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '../../web/node_modules/playwright/index.mjs';
import { expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const output = path.join(root, 'admin/test-results/hub-onboarding-r6');
const origin = 'http://127.0.0.1:3014';
await mkdir(output, { recursive: true });
const server = spawn(process.execPath, [path.join(root, 'admin/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '3014', '--strictPort'], { cwd: path.join(root, 'admin'), windowsHide: true, stdio: 'pipe' });
let serverOutput = '', browser, outcome = 'FAIL';
server.stdout.on('data', chunk => { serverOutput = (serverOutput + chunk).slice(-4000); });
server.stderr.on('data', chunk => { serverOutput = (serverOutput + chunk).slice(-4000); });
const passed = [], errors = [], external = [], unexpected = [];
const org = '10000000-0000-0000-0000-000000000001';
const cid = '20000000-0000-0000-0000-000000000001';
const rid = '30000000-0000-0000-0000-000000000001';
let connection = null, runs = [], platform = true, worker = true, scheduleEnabled = false, interval = 3600, lock = 0;
let organizationAvailable = false, authenticated = true;
let scheduleWrites = 0, checkWrites = 0, syncWrites = 0, conflict = false, abortSave = false;
try {
  for (let count = 0; ; count++) {
    if (server.exitCode !== null) throw new Error(`Owned Vite exited: ${serverOutput}`);
    try { if ((await fetch(origin)).ok) break; } catch { /* owned startup */ }
    if (count >= 100) throw new Error(`Owned Vite startup timeout: ${serverOutput}`);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method();
    if (url.origin !== origin) { external.push(url.origin); return route.abort(); }
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const respond = (data, status = 200, headers = {}) => route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(data) });
    if (url.pathname === '/api/admin/v1/session') return authenticated ? respond({ userId: '90000000-0000-0000-0000-000000000001', organizationIds: platform ? [] : [org], authorities: [platform ? 'ROLE_PLATFORM_ADMIN' : 'ROLE_ORG_EDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'synthetic-ui-csrf' }) : respond({ code: 'UNAUTHORIZED' }, 401);
    if (url.pathname === '/api/admin/v1/auth') return respond({ localLoginEnabled: true, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'synthetic-ui-csrf' });
    if (url.pathname === '/api/catalog/v1/datasets') return respond({ items: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } });
    if (url.pathname === '/api/admin/v1/metadata-options') return respond({ organizations: organizationAvailable ? [{ id: org, name: '합성 시험 기관' }] : [], categories: [] });
    if (url.pathname === '/api/admin/v1/connections') {
      if (method === 'POST') {
        const body = request.postDataJSON();
        assert.equal(body.adapterCode, 'KETI_DATACORE'); assert.equal(body.enabled, true); assert.equal(body.maxPages, 100);
        assert.equal(body.endpoint, 'http://synthetic-hub.invalid:19000/data-manager');
        assert.equal(body.organizationId, org); assert.equal(body.secretReference, undefined);
        assert.equal(request.headers()['x-csrf-token'], 'synthetic-ui-csrf');
        assert.ok(request.headers()['idempotency-key']);
        connection = { id: cid, organizationId: org, name: body.name, adapterCode: body.adapterCode, endpointConfigured: true, credentialConfigured: false, enabled: true, timeoutSeconds: 10, maxAttempts: 3, maxPages: 20, lockVersion: 0, adapterAvailable: true, capabilities: ['TEST_CONNECTION', 'DISCOVER_DATASETS'], updatedAt: '2026-10-26T15:00:00Z' };
        return respond(connection, 201, { ETag: `"connection:${cid}:0"` });
      }
      return respond(connection ? [connection] : []);
    }
    if (url.pathname === `/api/admin/v1/connections/${cid}/schedule`) {
      if (method === 'PUT') {
        scheduleWrites++;
        assert.equal(platform, true); assert.equal(request.headers()['x-csrf-token'], 'synthetic-ui-csrf');
        assert.equal(request.headers()['if-match'], `"schedule:${cid}:${lock}"`);
        if (conflict) return respond({ code: 'STALE_SCHEDULE' }, 412);
        if (abortSave) return route.abort('failed');
        const body = request.postDataJSON();
        assert.ok(Number.isInteger(body.intervalSeconds) && body.intervalSeconds >= 60 && body.intervalSeconds <= 86400);
        scheduleEnabled = body.enabled; interval = body.intervalSeconds; lock++;
      }
      return respond({ connectionId: cid, enabled: scheduleEnabled, intervalSeconds: interval, lockVersion: lock, workerEnabled: worker, nextRunAt: scheduleEnabled ? '2026-10-26T15:00:00Z' : null, lastRequestedAt: null, lastErrorCode: null }, 200, { ETag: `"schedule:${cid}:${lock}"` });
    }
    if (url.pathname === `/api/admin/v1/connections/${cid}/checks` && method === 'POST' || url.pathname === `/api/admin/v1/connections/${cid}/sync-runs` && method === 'POST') {
      assert.equal(worker, true); assert.equal(request.headers()['x-csrf-token'], 'synthetic-ui-csrf'); assert.ok(request.headers()['idempotency-key']);
      const kind = url.pathname.endsWith('/checks') ? 'CHECK' : 'SYNC';
      if (kind === 'CHECK') checkWrites++; else syncWrites++;
      runs = [{ id: rid, connectionId: cid, kind, status: 'SUCCEEDED', attempt: 1, configurationVersion: 0, mappingVersion: 'synthetic', errorCode: null, createdAt: '2026-10-26T15:00:00Z' }];
      return respond({ ...runs[0], status: 'QUEUED' }, 202);
    }
    if (url.pathname === `/api/admin/v1/connections/${cid}/sync-runs`) return respond(runs);
    if (url.pathname === `/api/admin/v1/connections/${cid}/audit-events`) return respond([]);
    if (url.pathname === `/api/admin/v1/connections/${cid}/candidates`) return respond({ items: [], page: 0, size: 20, totalElements: 0 });
    if (url.pathname === `/api/admin/v1/connections/${cid}/publication-policy`) return respond({ connectionId: cid, enabled: false, department: null, license: null, keywords: [], defaultCategoryId: null, lockVersion: 0 }, 200, { ETag: `"publication-policy:${cid}:0"` });
    if (url.pathname === `/api/admin/v1/connections/${cid}/publication-results`) return respond({ items: [] });
    unexpected.push(`${method} ${url.pathname}`); return respond({ code: 'UNEXPECTED_SYNTHETIC_REQUEST' }, 500);
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${origin}/hubs`);
  await expect(page.getByText('등록된 활성 기관이 없습니다. 기관을 등록한 뒤 새로고침해 주세요.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '연결 등록', exact: true })).toBeDisabled();
  organizationAvailable = true;
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await expect(page.getByRole('button', { name: '연결 등록', exact: true })).toBeEnabled();
  passed.push('platform administrator without organization grants can register after an active organization is available and refreshed');
  await page.getByRole('button', { name: '연결 등록', exact: true }).click();
  await expect(page.getByRole('combobox', { name: /^연동 식별 코드/ })).toHaveValue('KETI_DATACORE');
  await expect(page.getByLabel('연결 활성화', { exact: true })).toBeEnabled();
  await page.getByRole('textbox', { name: /^연결 이름/ }).fill('합성 UI 등록');
  await page.getByLabel('연결 활성화', { exact: true }).check();
  await expect(page.getByRole('textbox', { name: /^메타데이터 API 주소/ })).toHaveAttribute('aria-required', 'true');
  await page.getByRole('textbox', { name: /^메타데이터 API 주소/ }).fill('http://synthetic-hub.invalid:19000/data-manager');
  await expect(page.getByLabel('서버 비밀 참조', { exact: true })).toHaveValue('');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByText('비밀 참조 미설정', { exact: true })).toBeVisible();
  passed.push('new KETI registration accepts HTTP address and activation without an authentication reference');

  await page.getByRole('button', { name: '연결 검사', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: '연결 확인 완료' })).toBeVisible();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '메타데이터 수집', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: '수집 완료' })).toBeVisible();
  assert.equal(checkWrites, 1); assert.equal(syncWrites, 1);
  passed.push('existing check and initial-collection buttons send CSRF/idempotent requests and display completion history');

  const schedule = page.getByRole('region', { name: '정기 메타데이터 수집', exact: true });
  await page.getByRole('tab', { name: '설정', exact: true }).click();
  await expect(schedule.getByLabel('정기 수집 사용', { exact: true })).not.toBeChecked();
  await schedule.getByRole('spinbutton', { name: /^수집 주기/ }).fill('59');
  await expect(schedule.getByRole('button', { name: '정기 수집 설정 저장' })).toBeDisabled();
  assert.equal(scheduleWrites, 0);
  await schedule.getByRole('spinbutton', { name: /^수집 주기/ }).fill('60');
  await schedule.getByLabel('정기 수집 사용', { exact: true }).check();
  await schedule.getByRole('button', { name: '정기 수집 설정 저장' }).click();
  await expect(schedule.getByText('정기 수집 켜짐', { exact: false })).toBeVisible();
  assert.equal(scheduleWrites, 1); assert.equal(interval, 60);
  await expect(schedule.getByText('첫 수집이 곧 접수', { exact: false })).toBeVisible();
  passed.push('schedule bounds, default off, ETag/CSRF PUT and immediate-first-run explanation');

  conflict = true;
  await schedule.getByRole('spinbutton', { name: /^수집 주기/ }).fill('120');
  await schedule.getByRole('button', { name: '정기 수집 설정 저장' }).click();
  await expect(schedule.getByRole('alert')).toContainText('다른 담당자');
  await expect(schedule.getByRole('spinbutton', { name: /^수집 주기/ })).toHaveValue('120');
  assert.equal(interval, 60); assert.equal(scheduleWrites, 2);
  conflict = false;
  page.once('dialog', dialog => dialog.accept());
  await schedule.getByRole('button', { name: '저장된 설정 다시 조회', exact: true }).click();
  await expect(schedule.getByRole('spinbutton', { name: /^수집 주기/ })).toHaveValue('60');
  passed.push('stale ETag retains edited values and refuses silent success or retry');

  abortSave = true;
  await schedule.getByRole('spinbutton', { name: /^수집 주기/ }).fill('120');
  await schedule.getByRole('button', { name: '정기 수집 설정 저장' }).click();
  await expect(schedule.getByRole('alert')).toContainText('서버 응답');
  await expect(schedule.getByRole('button', { name: '정기 수집 설정 저장' })).toBeDisabled();
  assert.equal(scheduleWrites, 3);
  abortSave = false;
  await schedule.getByRole('button', { name: '저장된 설정 다시 조회', exact: true }).click();
  await expect(schedule.getByRole('spinbutton', { name: /^수집 주기/ })).toHaveValue('60');
  await expect(schedule.getByRole('spinbutton', { name: /^수집 주기/ })).toBeEnabled();
  passed.push('uncertain mutation blocks repeat submission until explicit state refresh');

  worker = false;
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '연결 검사', exact: true })).toHaveAccessibleDescription(/서버 수집 작업자가 꺼져/);
  await schedule.getByLabel('정기 수집 사용', { exact: true }).uncheck();
  await schedule.getByRole('button', { name: '정기 수집 설정 저장' }).click();
  await expect(schedule.getByText('정기 수집 꺼짐', { exact: false })).toBeVisible();
  await expect(schedule.getByLabel('정기 수집 사용', { exact: true })).toBeDisabled();
  assert.equal(scheduleWrites, 4); assert.equal(checkWrites, 1); assert.equal(syncWrites, 1);
  passed.push('worker-off blocks manual collection and enabling schedules while allowing an existing schedule to stop');

  worker = true; platform = false;
  await page.reload();
  await page.getByRole('tab', { name: '설정', exact: true }).click();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toBeEnabled();
  await expect(schedule.getByRole('button', { name: '정기 수집 설정 저장' })).toHaveCount(0);
  await expect(schedule.getByText('정기 수집 설정은 플랫폼 관리자가 변경합니다.')).toBeVisible();
  passed.push('organization editor keeps manual collection but cannot configure system schedules');

  platform = true; runs[0].status = 'RUNNING';
  await page.reload();
  await expect(page.getByRole('button', { name: '메타데이터 수집', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '연결 검사', exact: true })).toBeDisabled();
  passed.push('active collection keeps both manual actions disabled');
  runs = [];
  await page.reload();
  await page.getByRole('tab', { name: '설정', exact: true }).click();
  await expect(schedule).toBeVisible();
  await auditAccessibility(page, 'hub-onboarding-r6-desktop');
  await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 390));
  await auditAccessibility(page, 'hub-onboarding-r6-mobile');
  await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
  passed.push('desktop/mobile accessibility, local assets and no horizontal overflow');
  authenticated = false;
  await page.goto(`${origin}/login`);
  await expect(page.getByRole('heading', { name: '직접 로그인', exact: true })).toBeVisible();
  await expect(page.getByLabel('아이디', { exact: true })).toBeEnabled();
  await expect(page.getByLabel('비밀번호', { exact: true })).toBeEnabled();
  await expect(page.getByText(/개발 환경 전용|개발용 로컬 로그인|로컬 계정 로그인/)).toHaveCount(0);
  passed.push('direct-login form has no development-only badge or login wording');
  assert.deepEqual(external, []); assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
  outcome = 'PASS';
  console.log(JSON.stringify({ result: outcome, checks: passed.length, output, realHub: false, realServer: false }));
} finally {
  await browser?.close(); server.kill();
  await writeFile(path.join(output, 'result.json'), JSON.stringify({ outcome, kind: 'SYNTHETIC_UI_CONTRACT_FIXTURES', recordedAt: new Date().toISOString(), realHub: false, realServer: false, realLogin: false, tlsValidation: false, passed, browserErrors: errors, externalRequests: external, unexpectedApiRequests: unexpected }, null, 2) + '\n');
}
