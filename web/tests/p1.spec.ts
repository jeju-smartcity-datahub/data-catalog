import { expect, test } from '@playwright/test';

test('public root and deep link render the real API dataset in server HTML', async ({ page, request }) => {
  const api = await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets?page=0&size=10');
  expect(api.ok()).toBeTruthy();
  const data = await api.json();
  expect(data.items.length, 'Publish a dataset in the isolated test database before running this suite').toBeGreaterThan(0);
  const html = await request.get('/datasets');
  expect(await html.text()).toContain(data.items[0].title);
  await page.goto('/');
  await expect(page).toHaveURL(/\/datasets$/);
  await expect(page.getByRole('heading', { name: data.items[0].title, exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: data.items[0].title, exact: true })).toBeVisible();
});

test('empty page gives a working first-page navigation', async ({ page }) => {
  await page.goto('/datasets?page=100000');
  await expect(page.getByText('이 페이지에 데이터가 없습니다')).toBeVisible();
  await page.getByRole('link', { name: '첫 페이지로' }).click();
  await expect(page).toHaveURL(/\/datasets$/);
});

test('server failure is explicit and never replaced by data', async ({ page }) => {
  await page.goto('http://127.0.0.1:3002/datasets');
  await expect(page.locator('main').getByRole('alert')).toContainText('데이터를 불러오지 못했습니다');
  await expect(page.locator('article')).toHaveCount(0);
});

test('admin deep link fetches real API through its proxy', async ({ page, request }) => {
  const api = await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets?page=0&size=10');
  const data = await api.json();
  await page.goto('http://127.0.0.1:3001/datasets');
  await expect(page.getByRole('cell', { name: data.items[0].title, exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('cell', { name: data.items[0].title, exact: true })).toBeVisible();
});

test('admin displays failed API responses and can retry', async ({ page }) => {
  await page.route('**/api/catalog/v1/datasets?*', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{}' }));
  await page.goto('http://127.0.0.1:3001/datasets');
  await expect(page.getByRole('alert')).toContainText('데이터를 불러오지 못했습니다');
  await page.unroute('**/api/catalog/v1/datasets?*');
  await page.getByRole('button', { name: '새로고침' }).click();
  await expect(page.getByRole('table')).toBeVisible();
});

test('admin production artifact supports deep links and hides development diagnostics', async ({ page }) => {
  await page.goto('http://127.0.0.1:3003/datasets');
  await expect(page.getByRole('table')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('link', { name: '개발환경 진단' })).toHaveCount(0);
  await page.goto('http://127.0.0.1:3003/diagnostics');
  await expect(page.getByRole('button', { name: '접근 권한 확인' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '페이지를 찾을 수 없습니다', exact: true })).toBeVisible();
});

test('admin empty response remains empty', async ({ page }) => {
  await page.route('**/api/catalog/v1/datasets?*', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } }) }));
  await page.goto('http://127.0.0.1:3001/datasets');
  await expect(page.getByText('아직 공개된 데이터가 없습니다.')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('anonymous admin gets server 401 and dialog restores focus with Escape', async ({ page }) => {
  await page.goto('http://127.0.0.1:3001/diagnostics');
  await page.getByLabel('기관 ID (UUID)').fill('00000000-0000-0000-0000-000000000001');
  await page.getByRole('button', { name: '접근 권한 확인' }).click();
  await expect(page.getByRole('status')).toContainText('로그인이 필요합니다. (401)');
  const trigger = page.getByRole('button', { name: '공통 모달 확인' });
  await trigger.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('button', { name: '닫기', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '닫기', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(trigger).toBeFocused();
});

test('catalog links and mobile page width', async ({ page }) => {
  await page.goto('/datasets');
  await expect(page.locator('header').getByRole('link', { name: '데이터 카탈로그 홈' })).toHaveAttribute('href', '/datasets');
  await page.goto('http://127.0.0.1:3001/datasets');
  await expect(page.getByRole('link', { name: '데이터 카탈로그', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.goto('/datasets');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
