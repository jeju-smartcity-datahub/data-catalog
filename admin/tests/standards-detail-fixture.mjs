import assert from 'node:assert/strict';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';

// Browser-only fixtures. These assertions do not authenticate against the server,
// import real public standards, or prove persistence/authorization/SSO workflows.
const output = fileURLToPath(new URL('../../.runtime/admin-ui-fixtures/', import.meta.url));
await mkdir(output, { recursive: true });
const org = '11111111-1111-4111-8111-111111111111';
const dictionaryId = '22222222-2222-4222-8222-222222222222';
const wordId = '33333333-3333-4333-8333-333333333333';
const domainId = '44444444-4444-4444-8444-444444444444';
const termId = '55555555-5555-4555-8555-555555555555';
const dictionary = { id: dictionaryId, code: 'fixture', name: '격리 시험 공공사전', scope: 'PUBLIC', organizationId: null, versionLabel: 'fixture-v1', sourceUri: 'https://example.invalid/fixture-standards', sourceChecksum: null };
const entry = (id, kind, name) => ({ id, dictionaryId, kind, code: `fixture_${kind}`, name, definition: '실제 공공표준 원본이 아닌 격리 화면 검수 데이터입니다.', dataType: 'NUMBER', state: 'APPROVED', lockVersion: 1, createdBy: org, updatedBy: org, submittedBy: null, decidedBy: null, decisionReason: null });
const word = entry(wordId, 'WORD', '금액');
const domain = entry(domainId, 'DOMAIN', '금액 정수 도메인');
const term = entry(termId, 'TERM', '금액 합계');
const byId = new Map([word, domain, term].map(item => [item.id, item]));
const relationships = id => ({ entryId: id, kind: byId.get(id).kind, lockVersion: 1,
  word: id === wordId ? { englishName: 'Amount', abbreviation: 'AMT', synonyms: ['금액 값', '통화액'] } : null,
  domain: id === domainId ? { maxLength: null, precision: 9, scale: 0, format: '정수', unit: '원', allowedValues: ['0', '1', '100'] } : null,
  term: id === termId ? { physicalName: 'amount_total', domainEntryId: domainId, wordEntryIds: [wordId, wordId] } : null });
let failDomain = true;
const unexpected = [];
const errors = [];
const browser = await chromium.launch({ headless: true });
let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/admin/v1/**', async route => {
    const url = new URL(route.request().url()); const path = url.pathname.replace('/api/admin/v1', '');
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', headers: { ETag: '"fixture:1"' }, body: JSON.stringify(body) });
    if (route.request().method() !== 'GET') { unexpected.push(`${route.request().method()} ${path}`); return json({}, 500); }
    if (path === '/session') return json({ userId: org, organizationIds: [org], authorities: ['ROLE_ORG_AUDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-fixture' });
    if (path === '/metadata-options') return json({ organizations: [{ id: org, name: '검증 기관' }], categories: [] });
    if (path === '/dictionaries') return json([dictionary]);
    if (path === `/dictionaries/${dictionaryId}/entries`) return json([url.searchParams.get('kind') === 'WORD' ? word : url.searchParams.get('kind') === 'DOMAIN' ? domain : term]);
    const match = path.match(/^\/dictionary-entries\/([^/]+)(\/relationships)?$/);
    if (match && byId.has(match[1])) {
      if (match[2] && match[1] === domainId && failDomain) return json({ title: '격리 시험 속성 조회 실패', status: 500 }, 500);
      return json(match[2] ? relationships(match[1]) : byId.get(match[1]));
    }
    unexpected.push(`GET ${path}`); return json({}, 500);
  });
  page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto((process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001') + '/standards');
  await expect(page.getByRole('button', { name: '공공 표준 사전', exact: true })).toHaveCSS('border-bottom-color', 'rgb(8, 57, 139)');
  await page.getByRole('button', { name: '표준 단어', exact: true }).click();
  const opener = page.getByRole('button', { name: word.name, exact: true });
  await opener.click();
  let modal = page.getByRole('dialog', { name: '표준사전 항목 상세', exact: true });
  await expect(modal.locator('dd').filter({ hasText: /^Amount$/ })).toBeVisible();
  await expect(modal.locator('dd').filter({ hasText: /^AMT$/ })).toBeVisible();
  await expect(modal).toContainText('금액 값, 통화액');
  await expect(modal).toContainText('fixture-v1');
  await expect(modal.getByRole('link', { name: /공식 자료 보기/ })).toHaveAttribute('href', dictionary.sourceUri);
  await expect(modal.locator('input,textarea,select')).toHaveCount(0);
  await auditAccessibility(page, 'standards-word-detail'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-word-detail.png` });
  await page.keyboard.press('Escape'); await expect(modal).toHaveCount(0); await expect(opener).toBeFocused();
  await page.getByRole('button', { name: '표준 도메인', exact: true }).click();
  await page.getByRole('button', { name: domain.name, exact: true }).click();
  modal = page.getByRole('dialog', { name: '표준사전 항목 상세', exact: true });
  await expect(modal.getByRole('alert')).toBeVisible();
  failDomain = false;
  await modal.getByRole('button', { name: '속성 다시 불러오기', exact: true }).click();
  await expect(modal.getByRole('alert')).toHaveCount(0);
  await expect(modal.locator('dl > div').filter({ has: page.locator('dt', { hasText: /^소수 자릿수$/ }) }).locator('dd')).toHaveText('0');
  await expect(modal.locator('dl > div').filter({ has: page.locator('dt', { hasText: /^단위$/ }) }).locator('dd')).toHaveText('원');
  await auditAccessibility(page, 'standards-domain-detail'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-domain-detail.png` });
  await modal.getByRole('button', { name: '닫기', exact: true }).click();
  await page.getByRole('button', { name: '표준 용어', exact: true }).click();
  await page.getByRole('button', { name: term.name, exact: true }).click();
  modal = page.getByRole('dialog', { name: '표준사전 항목 상세', exact: true });
  await expect(modal).toContainText('amount_total'); await expect(modal).toContainText(domain.name);
  await expect(modal.locator('ol li')).toHaveText([word.name, word.name]);
  await auditAccessibility(page, 'standards-term-detail'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-term-detail.png` });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.keyboard.press('Tab');
  assert.equal(await modal.evaluate(element => element.contains(document.activeElement)), true);
  assert.ok(await modal.evaluate(element => element.scrollWidth <= element.clientWidth));
  const bounds = await modal.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
  await auditAccessibility(page, 'standards-term-detail-mobile'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-term-detail-mobile.png` });
  await page.keyboard.press('Escape'); await expect(modal).toHaveCount(0);
  assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
  console.log('Standards read-only UI fixtures: WORD attributes/source, DOMAIN error/retry/zero, TERM domain/ordered repeated words, keyboard focus/ESC, mobile overflow: 5 passed. Browser fixtures only; no real SSO or public source data tested.');
} catch (error) { if (page) await page.screenshot({ path: `${output}/standards-detail-failure.png` }); console.error({ errors, unexpected }); throw error; }
finally { await browser.close(); }
