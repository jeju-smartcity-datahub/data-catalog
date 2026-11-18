import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';

// Browser API fixtures only: server authorization and persistence have separate integration tests.
const output = fileURLToPath(new URL('../test-results/identity-management/', import.meta.url));
await mkdir(output, { recursive: true });
const organizationId = '10000000-0000-4000-8000-000000000001';
const currentId = '20000000-0000-4000-8000-000000000001';
const accountId = '20000000-0000-4000-8000-000000000002';
const time = '2026-11-02T00:00:00Z';
const organizations = [{ id: organizationId, code: 'TEST_REGION', name: '시험 기관', active: true, lockVersion: 1, createdAt: time, updatedAt: time }];
const accounts = [{ id: currentId, username: 'admin', active: true, roles: ['PLATFORM_ADMIN'], organizationIds: [], lockVersion: 1, createdAt: time, updatedAt: time }, { id: accountId, username: 'editor', active: true, roles: ['ORG_EDITOR'], organizationIds: [organizationId], lockVersion: 2, createdAt: time, updatedAt: time }];
let platformAdmin = true;
let releaseUpdate;
let passwordConflict = true;
let managementReads = 0;
const writes = [], errors = [], unexpected = [], passed = [];
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1500, height: 1100 } });
  await context.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method();
    const json = (body, headers = {}, status = 200) => route.fulfill({ status, json: body, headers });
    if (path.endsWith('/session')) return json({ userId: currentId, organizationIds: [organizationId], authorities: [platformAdmin ? 'ROLE_PLATFORM_ADMIN' : 'ROLE_ORG_AUDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'fixture-csrf' });
    if (path.endsWith('/audit-events')) return json({ items: [], number: 0, size: 20, totalElements: 0, totalPages: 0 });
    if (method === 'GET' && /\/(accounts|organizations)(\/|$)/.test(path)) managementReads++;
    if (path === '/api/admin/v1/accounts' && method === 'GET') return json(accounts);
    if (path === '/api/admin/v1/organizations' && method === 'GET') return json(organizations);
    const account = accounts.find(value => path === `/api/admin/v1/accounts/${value.id}` || path === `/api/admin/v1/accounts/${value.id}/password`);
    const organization = organizations.find(value => path === `/api/admin/v1/organizations/${value.id}`);
    if (account && method === 'GET') return json(account, { ETag: `"account:${account.id}:${account.lockVersion}"` });
    if (organization && method === 'GET') return json(organization, { ETag: `"organization:${organization.id}:${organization.lockVersion}"` });
    if (method !== 'GET') {
      const body = request.postDataJSON();
      writes.push({ path, method, headers: request.headers(), keys: Object.keys(body) });
      assert.equal(request.headers()['x-csrf-token'], 'fixture-csrf');
      if (method === 'POST') assert.ok(request.headers()['idempotency-key']);
      if (account && method === 'PUT') {
        assert.equal(request.headers()['if-match'], `"account:${account.id}:${account.lockVersion}"`);
        await new Promise(resolve => { releaseUpdate = resolve; });
        Object.assign(account, body, { lockVersion: account.lockVersion + 1 });
        return json(account, { ETag: `"account:${account.id}:${account.lockVersion}"` });
      }
      if (account && path.endsWith('/password')) {
        assert.equal(request.headers()['if-match'], `"account:${account.id}:${account.lockVersion}"`);
        assert.equal(body.password.length >= 8, true);
        if (passwordConflict) return json({ code: 'VERSION_CONFLICT' }, {}, 412);
        account.lockVersion++;
        return json(account, { ETag: `"account:${account.id}:${account.lockVersion}"` });
      }
      if (path === '/api/admin/v1/accounts') {
        assert.equal(body.username, 'new.editor'); assert.deepEqual(body.organizationIds, [organizationId]);
        const created = { id: '20000000-0000-4000-8000-000000000003', username: body.username, active: body.active, roles: body.roles, organizationIds: body.organizationIds, lockVersion: 1, createdAt: time, updatedAt: time };
        accounts.push(created); return json(created, {}, 201);
      }
      if (path === '/api/admin/v1/organizations') {
        const created = { ...body, id: '10000000-0000-4000-8000-000000000002', lockVersion: 1, createdAt: time, updatedAt: time };
        organizations.push(created); return json(created, {}, 201);
      }
      if (organization && method === 'PUT') {
        assert.equal(request.headers()['if-match'], `"organization:${organization.id}:${organization.lockVersion}"`);
        if (!body.active) return json({ code: 'ORGANIZATION_IN_USE' }, {}, 409);
        Object.assign(organization, body, { lockVersion: organization.lockVersion + 1 }); return json(organization);
      }
    }
    unexpected.push(`${method} ${path}`); return json({ code: 'UNEXPECTED_REQUEST' }, {}, 500);
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  const origin = process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001';
  await page.goto(`${origin}/access`);
  await page.getByRole('button', { name: '계정 관리', exact: true }).click();
  await expect(page.getByRole('rowheader', { name: 'editor', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'editor 계정 수정', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: '계정 수정', exact: true });
  await dialog.getByRole('checkbox', { name: '게시 관리', exact: true }).check();
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '저장 중', exact: true })).toBeDisabled();
  await expect.poll(() => writes.length).toBe(1); releaseUpdate();
  await expect(dialog).toHaveCount(0); await expect(page.getByRole('table')).toContainText('게시 관리');
  passed.push('account roles saved with CSRF and If-Match; duplicate submission blocked');
  await page.getByRole('button', { name: 'editor 비밀번호 재설정', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '비밀번호 재설정', exact: true });
  await dialog.getByLabel('새 비밀번호', { exact: false }).fill('Fixture-Pass123');
  await dialog.getByLabel(/^비밀번호 확인(?:\s|\*|$)/).fill('Fixture-Pass123');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('다른 담당자');
  await expect(dialog.getByLabel('새 비밀번호', { exact: false })).toHaveValue('');
  await page.keyboard.press('Escape'); passwordConflict = false;
  await page.getByRole('button', { name: 'editor 비밀번호 재설정', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '비밀번호 재설정', exact: true });
  await dialog.getByLabel('새 비밀번호', { exact: false }).fill('Fixture-Pass456');
  await dialog.getByLabel(/^비밀번호 확인(?:\s|\*|$)/).fill('Fixture-Pass456');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0); await expect(page.getByRole('table')).not.toContainText('Fixture-Pass');
  passed.push('password reset preserves version checks, clears secret on error, and never renders password in listing');
  await page.getByRole('button', { name: '계정 등록', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '계정 등록', exact: true });
  await dialog.getByLabel(/^계정 ID(?:\s|\*|$)/).fill('new.editor');
  await dialog.getByLabel('새 비밀번호', { exact: false }).fill('Fixture-Pass789');
  await dialog.getByLabel(/^비밀번호 확인(?:\s|\*|$)/).fill('Fixture-Pass789');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('기관 범위');
  await dialog.getByRole('checkbox', { name: '시험 기관', exact: true }).check();
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0); await expect(page.getByRole('rowheader', { name: 'new.editor', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'admin 계정 수정', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '계정 수정', exact: true });
  await expect(dialog.getByRole('checkbox', { name: '계정 사용', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('checkbox', { name: '전체 관리', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await page.screenshot({ path: `${output}/accounts.png`, fullPage: true });
  passed.push('account registration validates scoped organization and self-admin controls are disabled');
  await page.getByRole('button', { name: '기관 관리', exact: true }).click();
  await page.getByRole('button', { name: '기관 등록', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '기관 등록', exact: true });
  await dialog.getByLabel(/^기관 코드(?:\s|\*|$)/).fill('TEST'); await dialog.getByLabel(/^기관명(?:\s|\*|$)/).fill('검수 기관');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0); await expect(page.getByRole('cell', { name: '검수 기관', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '시험 기관 기관 수정', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '기관 수정', exact: true });
  await expect(dialog.getByLabel(/^기관 코드(?:\s|\*|$)/)).toBeDisabled();
  await dialog.getByRole('checkbox', { name: '기관 사용', exact: true }).uncheck(); await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('현재 상태'); await page.keyboard.press('Escape');
  passed.push('organization registration and in-use status rejection; immutable organization code');
  const reads = managementReads; platformAdmin = false; await page.reload();
  await expect(page.getByRole('button', { name: '현재 계정 권한', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '계정 관리', exact: true })).toHaveCount(0); await expect(page.getByRole('button', { name: '기관 관리', exact: true })).toHaveCount(0);
  assert.equal(managementReads, reads); assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
  passed.push('non-platform session sees existing permissions/audit only and sends no management API calls');
  await writeFile(`${output}/results.json`, JSON.stringify({ kind: 'ISOLATED_UI_CONTRACT_FIXTURE', passed, requestCount: writes.length, errors, unexpected }, null, 2));
  console.log(JSON.stringify({ passed: passed.length, requests: writes.length, errors }));
} finally { await browser.close(); }
