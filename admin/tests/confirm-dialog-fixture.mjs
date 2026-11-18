import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

// Test-only responses. These checks do not establish real SSO or server authorization.
const output = fileURLToPath(new URL('../test-results/shared/', import.meta.url));
await mkdir(output, { recursive: true });
const categoryId = '11111111-1111-4111-8111-111111111111';
const category = { id: categoryId, parentId: null, code: 'qa_category', name: '검수 분야', description: '', active: true, ordinal: 0, usageCount: 0, lockVersion: 1 };
const etag = `"category:${categoryId}:1"`;
const errors = []; const unexpected = []; const writes = []; const passed = [];
let deleted = false; let deleteStatus = 409; let releaseDelete; let releaseRefresh;
const browser = await chromium.launch({ headless: true });
let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname; const method = request.method();
    const json = (body, headers = {}) => route.fulfill({ status: 200, contentType: 'application/json', headers, body: JSON.stringify(body) });
    if (path === '/api/admin/v1/session' && method === 'GET') return json({ userId: '22222222-2222-4222-8222-222222222222', organizationIds: [], authorities: ['ROLE_PLATFORM_ADMIN'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-confirm-token' });
    if (path === '/api/admin/v1/categories' && method === 'GET') {
      if (deleted) await new Promise(resolve => { releaseRefresh = resolve; });
      return json(deleted ? [] : [category]);
    }
    if (path === `/api/admin/v1/categories/${categoryId}/usage` && method === 'GET') return json(category, { ETag: etag });
    if (path === `/api/admin/v1/categories/${categoryId}` && method === 'DELETE') {
      writes.push({ method, path, headers: request.headers() });
      await new Promise(resolve => { releaseDelete = resolve; });
      if (deleteStatus === 204) { deleted = true; return route.fulfill({ status: 204 }); }
      return route.fulfill({ status: deleteStatus, contentType: 'application/json', body: JSON.stringify({ code: 'CATEGORY_IN_USE' }) });
    }
    unexpected.push(`${method} ${path}`);
    return route.fulfill({ status: 500, contentType: 'application/json', body: '{"code":"UNEXPECTED_FIXTURE_REQUEST"}' });
  });
  page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/classifications`);
  await page.getByRole('button', { name: '분야 수정', exact: true }).click();
  const editor = page.getByRole('dialog', { name: '분야 수정', exact: true });
  const trigger = editor.getByRole('button', { name: '삭제', exact: true });
  await trigger.click();
  let confirmation = page.getByRole('dialog', { name: '삭제', exact: true });
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  assert.deepEqual(await confirmation.getByRole('button').allTextContents(), ['취소', '삭제']);
  assert.equal(await confirmation.getAttribute('aria-describedby') !== null, true);
  const geometry = await confirmation.evaluate(element => {
    const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
    return { width: rect.width, height: rect.height, padding: style.padding, borderRadius: style.borderRadius, buttons: [...element.querySelectorAll('button')].map(button => { const rect = button.getBoundingClientRect(); const style = getComputedStyle(button); return { width: rect.width, height: rect.height, color: style.color, background: style.backgroundColor }; }) };
  });
  assert.equal(geometry.width, 400); assert.equal(geometry.borderRadius, '16px');
  assert.ok(geometry.buttons.every(button => button.height >= 48));
  await auditAccessibility(page, 'confirmation-desktop');
  await confirmation.screenshot({ path: `${output}/delete-confirm-desktop.png` });
  await page.keyboard.press('Shift+Tab'); await expect(confirmation.getByRole('button', { name: '삭제', exact: true })).toBeFocused();
  await page.keyboard.press('Tab'); await expect(confirmation.getByRole('button', { name: '취소', exact: true })).toBeFocused();
  await page.keyboard.press('Escape'); await expect(confirmation).toHaveCount(0); await expect(trigger).toBeFocused();
  assert.equal(writes.length, 0);
  passed.push('400px confirmation, action order, safe initial focus, Tab wrap and Escape focus restoration');

  await trigger.click(); confirmation = page.getByRole('dialog', { name: '삭제', exact: true });
  await confirmation.getByRole('button', { name: '삭제', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  await expect(confirmation).toHaveAttribute('aria-busy', 'true');
  await expect(confirmation.getByRole('button', { name: '취소', exact: true })).toBeDisabled();
  await expect(confirmation.getByRole('button', { name: '삭제 중…', exact: true })).toBeDisabled();
  await expect(confirmation.getByRole('status')).toContainText('삭제 중');
  await page.keyboard.press('Escape'); await expect(confirmation).toBeVisible();
  await auditAccessibility(page, 'confirmation-busy');
  await confirmation.screenshot({ path: `${output}/delete-confirm-busy.png` });
  releaseDelete();
  await expect(confirmation.getByRole('alert')).toContainText('현재 상태');
  await expect(confirmation.getByRole('button', { name: '삭제', exact: true })).toBeEnabled();
  assert.equal(writes[0].headers['if-match'], etag); assert.equal(writes[0].headers['x-csrf-token'], 'isolated-confirm-token');
  await auditAccessibility(page, 'confirmation-error');
  await confirmation.screenshot({ path: `${output}/delete-confirm-error.png` });
  passed.push('busy locks both actions and Escape; 409 remains visible with real error state and strong request headers');

  await confirmation.getByRole('button', { name: '취소', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await trigger.click(); confirmation = page.getByRole('dialog', { name: '삭제', exact: true });
  const mobileBounds = await confirmation.boundingBox();
  assert.ok(mobileBounds.x >= 0 && mobileBounds.x + mobileBounds.width <= 390);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth) <= 390);
  await auditAccessibility(page, 'confirmation-mobile');
  await confirmation.screenshot({ path: `${output}/delete-confirm-mobile.png` });
  passed.push('390px confirmation remains inside viewport without horizontal document overflow');

  deleteStatus = 204;
  await confirmation.getByRole('button', { name: '삭제', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2); releaseDelete();
  await expect(confirmation).toHaveCount(0); await expect(editor).toHaveCount(0);
  await expect(page.getByRole('status').filter({ hasText: '미사용 분류를 삭제했습니다.' })).toHaveText('미사용 분류를 삭제했습니다.');
  await expect(page.getByRole('status').filter({ hasText: '분류를 불러오는 중입니다.' })).toBeVisible();
  await expect.poll(() => typeof releaseRefresh).toBe('function'); releaseRefresh();
  await expect(page.getByText('등록된 분야가 없습니다.', { exact: true })).toBeVisible();
  passed.push('explicit successful fixture response closes the dialogs and refreshes the empty list');
  assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
  await writeFile(`${output}/result.json`, JSON.stringify({ kind: 'ISOLATED_UI_CONTRACT_FIXTURES', realSso: false, realServerMutation: false, passed, geometry, errors, unexpected }, null, 2));
  console.log(`Shared confirmation isolated UI contract checks: PASS (${passed.length}). No real SSO or server mutation.`);
} catch (error) {
  if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: false });
  console.error({ errors, unexpected }); throw error;
} finally { releaseDelete?.(); releaseRefresh?.(); await browser.close(); }
