import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

// Test-only responses. No real login, authorization, production taxonomy or mutation.
const output = fileURLToPath(new URL('../test-results/discovery-taxonomies/', import.meta.url));
await mkdir(output, { recursive: true });
const hubId = '11111111-2222-4333-8444-555555555555';
const createdId = '22222222-3333-4444-8555-666666666666';
const rows = [{ id: hubId, kind: 'HUB', code: 'fixture_hub', name: '검수용 공개 허브', active: true, lockVersion: 4, referenceCount: 2 }];
const writes = []; const errors = []; const unexpected = []; const passed = []; const accessibility = []; const geometry = {};
let administrator = true; let listFailure = false; let patchConflict = true; let deleteConflict = true; let lookupReads = 0; let holdList; let releaseList; let holdDelete; let releaseDelete;
const browser = await chromium.launch({ headless: true });
let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method();
    const json = (body, status = 200, headers = {}) => route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify(body) });
    const etag = row => ({ ETag: `"discovery-lookup:${row.id}:${row.lockVersion}"` });
    if (method === 'GET' && path === '/api/admin/v1/session') return json({ userId: '33333333-3333-4333-8333-333333333333', organizationIds: [], authorities: [administrator ? 'ROLE_PLATFORM_ADMIN' : 'ROLE_ORG_AUDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-discovery-token' });
    if (method === 'GET' && path === '/api/admin/v1/categories') return json([]);
    if (path === '/api/admin/v1/discovery-lookups') {
      if (method === 'GET') {
        lookupReads++; if (holdList) await holdList;
        if (listFailure) return json({ code: 'ISOLATED_FAILURE' }, 500);
        assert.ok(['REGION', 'HUB'].includes(url.searchParams.get('kind')));
        return json(rows.filter(row => row.kind === url.searchParams.get('kind')));
      }
      if (method === 'POST') {
        const body = request.postDataJSON(); writes.push({ method, body, headers: request.headers() });
        const row = { ...body, id: createdId, lockVersion: 0, referenceCount: 0 }; rows.push(row);
        return json(row, 201, etag(row));
      }
    }
    const row = rows.find(row => path === `/api/admin/v1/discovery-lookups/${row.id}`);
    if (row && method === 'GET') { lookupReads++; return json(row, 200, etag(row)); }
    if (row && method === 'PATCH') {
      writes.push({ method, body: request.postDataJSON(), headers: request.headers() });
      if (patchConflict) return json({ code: 'VERSION_CONFLICT' }, 412);
      Object.assign(row, request.postDataJSON(), { lockVersion: row.lockVersion + 1 });
      return json(row, 200, etag(row));
    }
    if (row && method === 'DELETE') {
      writes.push({ method, headers: request.headers() }); if (holdDelete) await holdDelete;
      if (deleteConflict) return json({ code: 'LOOKUP_IN_USE' }, 409);
      row.active = false; row.lockVersion++; return json(row, 200, etag(row));
    }
    unexpected.push(`${method} ${path}`); return json({ code: 'UNEXPECTED_FIXTURE_REQUEST' }, 500);
  });
  page = await context.newPage(); page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
  await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/classifications`);
  const section = page.getByRole('region', { name: '공개 탐색 분류 설정', exact: true });
  await expect(section.getByRole('heading', { name: '공개 탐색 분류', exact: true })).toBeVisible();
  assert.equal(lookupReads, 0);
  await expect(section).toContainText('허브 접속 주소·인증 정보·수집 연결은 이곳에서 설정하지 않습니다');
  holdList = new Promise(resolve => { releaseList = resolve; });
  await section.getByRole('button', { name: '지역·허브 분류 관리', exact: true }).click();
  await expect(section.getByRole('status')).toContainText('지역 분류를 불러오는 중');
  releaseList(); holdList = undefined;
  await expect(section.getByText('등록된 지역 공개 분류가 없습니다.', { exact: true })).toBeVisible();
  listFailure = true; await section.getByRole('button', { name: '허브', exact: true }).click();
  await expect(section.getByRole('alert')).toContainText('요청을 처리하지 못했습니다');
  await expect(section.getByRole('table')).toHaveCount(0);
  listFailure = false; await section.getByRole('button', { name: '분류 목록 다시 시도', exact: true }).click();
  await expect(section.getByRole('cell', { name: '검수용 공개 허브', exact: true })).toBeVisible();
  accessibility.push(await auditAccessibility(page, 'discovery-taxonomies-list'));
  await section.screenshot({ path: `${output}/list-desktop.png` });
  passed.push('platform disclosure lazily loads actual response; separate public-label explanation, loading/empty/error/retry and kind filter');

  await section.getByRole('button', { name: '지역', exact: true }).click();
  await section.getByRole('button', { name: '지역 분류 추가', exact: true }).click();
  let editor = page.getByRole('dialog', { name: '지역 공개 분류 추가', exact: true });
  await expect(editor.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  await editor.getByRole('textbox', { name: /^분류 코드/ }).fill('INVALID CODE');
  await editor.getByRole('textbox', { name: /^분류명/ }).fill('검수용 지역');
  await editor.getByRole('button', { name: '저장', exact: true }).click(); assert.equal(writes.length, 0);
  assert.equal(await editor.getByRole('textbox', { name: /^분류 코드/ }).evaluate(element => element.validity.patternMismatch), true);
  await editor.getByRole('textbox', { name: /^분류 코드/ }).fill('fixture_region');
  await editor.getByRole('textbox', { name: /^분류명/ }).fill('   ');
  await editor.getByRole('button', { name: '저장', exact: true }).click(); assert.equal(writes.length, 0);
  await editor.getByRole('textbox', { name: /^분류명/ }).fill('검수용 지역');
  accessibility.push(await auditAccessibility(page, 'discovery-taxonomies-create'));
  await editor.screenshot({ path: `${output}/create-desktop.png` });
  await editor.getByRole('button', { name: '저장', exact: true }).click(); await expect(editor).toHaveCount(0);
  await expect(section.getByRole('status')).toContainText('공개 탐색 분류를 저장했습니다');
  await expect(section.getByRole('status')).toBeFocused();
  await expect(section.getByRole('cell', { name: '검수용 지역', exact: true })).toBeVisible();
  assert.deepEqual(writes[0].body, { kind: 'REGION', code: 'fixture_region', name: '검수용 지역', active: true });
  assert.equal(writes[0].headers['x-csrf-token'], 'isolated-discovery-token'); assert.ok(writes[0].headers['idempotency-key']);
  passed.push('create validates code and nonblank name before POST, sends exact kind/body and CSRF, then reloads response and focuses success');

  await section.getByRole('button', { name: '허브', exact: true }).click();
  await section.getByRole('button', { name: '검수용 공개 허브 공개 분류 수정', exact: true }).click();
  editor = page.getByRole('dialog', { name: '허브 공개 분류 수정', exact: true });
  await expect(editor.getByRole('textbox', { name: /^분류 코드/ })).toHaveAttribute('readonly', '');
  await expect(editor.getByRole('option', { name: '비활성', exact: true })).toHaveAttribute('disabled', '');
  await expect(editor.getByRole('button', { name: '분류 비활성화', exact: true })).toBeDisabled();
  await editor.getByRole('textbox', { name: /^분류명/ }).fill('수정한 공개 허브');
  await editor.getByRole('button', { name: '저장', exact: true }).click();
  await expect(editor.getByRole('alert')).toContainText('입력 내용은 유지됩니다'); await expect(editor.getByRole('alert')).toBeFocused();
  await expect(editor.getByRole('textbox', { name: /^분류명/ })).toHaveValue('수정한 공개 허브');
  assert.deepEqual(writes[1].body, { name: '수정한 공개 허브', active: true }); assert.equal(writes[1].headers['if-match'], `"discovery-lookup:${hubId}:4"`);
  accessibility.push(await auditAccessibility(page, 'discovery-taxonomies-edit-conflict'));
  await editor.screenshot({ path: `${output}/edit-conflict.png` });
  await editor.getByRole('button', { name: '최신 분류 확인', exact: true }).click();
  await expect(editor.getByRole('textbox', { name: /^분류명/ })).toHaveValue('검수용 공개 허브');
  patchConflict = false; await editor.getByRole('textbox', { name: /^분류명/ }).fill('수정한 공개 허브');
  await editor.getByRole('button', { name: '저장', exact: true }).click(); await expect(editor).toHaveCount(0);
  await expect(section.getByRole('cell', { name: '수정한 공개 허브', exact: true })).toBeVisible();
  passed.push('referenced entry cannot deactivate, immutable code and If-Match preserved; 412 keeps form and focuses alert, explicit reload then rename succeeds');

  await section.getByRole('button', { name: '지역', exact: true }).click();
  await section.getByRole('button', { name: '검수용 지역 공개 분류 수정', exact: true }).click();
  editor = page.getByRole('dialog', { name: '지역 공개 분류 수정', exact: true });
  await editor.getByRole('button', { name: '분류 비활성화', exact: true }).click();
  let confirmation = page.getByRole('dialog', { name: '공개 분류 비활성화', exact: true });
  await expect(confirmation.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  await expect(confirmation).toContainText('기존 이력은 보존');
  holdDelete = new Promise(resolve => { releaseDelete = resolve; });
  await confirmation.getByRole('button', { name: '비활성화', exact: true }).click();
  await expect(confirmation.getByRole('button', { name: '취소', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape'); await expect(confirmation).toBeVisible();
  releaseDelete(); holdDelete = undefined;
  await expect(confirmation.getByRole('alert')).toContainText('현재 상태에서는');
  assert.equal(writes[3].method, 'DELETE'); assert.equal(writes[3].headers['if-match'], `"discovery-lookup:${createdId}:0"`); assert.equal(writes[3].headers['x-csrf-token'], 'isolated-discovery-token');
  await confirmation.getByRole('button', { name: '취소', exact: true }).click();
  await editor.getByRole('button', { name: '최신 분류 확인', exact: true }).click();
  deleteConflict = false; await editor.getByRole('button', { name: '분류 비활성화', exact: true }).click();
  confirmation = page.getByRole('dialog', { name: '공개 분류 비활성화', exact: true });
  await confirmation.getByRole('button', { name: '비활성화', exact: true }).click();
  await expect(confirmation).toHaveCount(0); await expect(editor).toHaveCount(0);
  await expect(section.getByRole('status')).toContainText('비활성화했습니다. 기존 이력은 유지됩니다');
  await expect(section.getByRole('row').filter({ hasText: '검수용 지역' })).toContainText('비활성');
  passed.push('unused deactivation confirmation has cancel focus and busy guard; DELETE CSRF/ETag, honest 409 and explicit refresh, retained inactive row after success');

  await page.setViewportSize({ width: 390, height: 844 });
  await section.getByRole('button', { name: '검수용 지역 공개 분류 수정', exact: true }).click();
  editor = page.getByRole('dialog', { name: '지역 공개 분류 수정', exact: true });
  await expect(editor.getByRole('button', { name: '분류 비활성화', exact: true })).toBeDisabled();
  geometry.mobileEditor = await editor.boundingBox(); assert.ok(geometry.mobileEditor.x >= 0 && geometry.mobileEditor.x + geometry.mobileEditor.width <= 390);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 390));
  await editor.getByRole('button', { name: '취소', exact: true }).focus(); await page.keyboard.press('Tab');
  await expect(editor.getByRole('button', { name: '지역 공개 분류 수정 닫기', exact: true })).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(editor.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  accessibility.push(await auditAccessibility(page, 'discovery-taxonomies-editor-mobile'));
  await editor.screenshot({ path: `${output}/editor-mobile.png` }); await page.keyboard.press('Escape');
  await expect(section.getByRole('button', { name: '검수용 지역 공개 분류 수정', exact: true })).toBeFocused();
  const scroll = section.getByRole('region', { name: '지역 공개 분류 표', exact: true }); await scroll.focus(); await page.keyboard.press('ArrowRight');
  assert.ok(await scroll.evaluate(element => element.scrollWidth > element.clientWidth));
  accessibility.push(await auditAccessibility(page, 'discovery-taxonomies-list-mobile'));
  await section.screenshot({ path: `${output}/list-mobile.png` });
  administrator = false; const priorReads = lookupReads; await page.reload();
  await expect(page.getByRole('heading', { name: '분야 0', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '공개 탐색 분류 설정', exact: true })).toHaveCount(0); assert.equal(lookupReads, priorReads);
  passed.push('390px editor/table stay within viewport with keyboard scroll, Tab trap and Escape restoration; non-platform role neither sees nor fetches protected lookup API');
  assert.deepEqual(errors, []); assert.deepEqual(unexpected, []); assert.equal(writes.length, 5);
  await writeFile(`${output}/result.json`, JSON.stringify({ kind: 'ISOLATED_UI_CONTRACT_FIXTURES', realSso: false, realServerMutation: false, passed, geometry, accessibility, errors, unexpected }, null, 2));
  console.log(`Discovery taxonomy isolated UI contract checks: PASS (${passed.length}). No real SSO or server mutation.`);
} catch (error) { if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: false }); console.error({ passed, errors, unexpected }); throw error; }
finally { releaseList?.(); releaseDelete?.(); await browser.close(); }
