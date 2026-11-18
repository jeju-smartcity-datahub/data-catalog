import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

const adminUrl = 'http://127.0.0.1:3001/dcat';
const apiUrl = 'http://127.0.0.1:8080/api/catalog/v1/dcat?page=0&size=10';

test('DCAT admin shows the real public total and JSON-LD projection', async ({ page, request }, testInfo) => {
  const api = await request.get(apiUrl, { headers: { Accept: 'application/ld+json' } });
  expect(api.ok()).toBeTruthy();
  const total = Number(api.headers()['x-total-count']);
  expect(Number.isSafeInteger(total)).toBeTruthy();
  const expected = await api.json();

  await page.setViewportSize({ width: 1920, height: 1256 });
  await page.goto(adminUrl);
  const preview = page.getByLabel('JSON-LD 공개 메타데이터 원문');
  await expect(preview).toBeVisible();
  expect(JSON.parse((await preview.textContent()) ?? '')).toEqual(expected);
  await expect(page.getByRole('region', { name: '외부 메타데이터 제공' }).getByText(total.toLocaleString('ko-KR'), { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'JSON-LD', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('switch')).toHaveCount(0);
  await expect(page.getByText('읽기 전용', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('p5-dcat-desktop.png'), fullPage: true });
});

test('DCAT format buttons work by keyboard and Turtle matches the real endpoint', async ({ page, request }) => {
  const api = await request.get(apiUrl, { headers: { Accept: 'text/turtle' } });
  expect(api.ok()).toBeTruthy();
  const expected = await api.text();
  await page.goto(adminUrl);
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  const turtleButton = page.getByRole('button', { name: 'Turtle', exact: true });
  await turtleButton.focus();
  await page.keyboard.press('Enter');
  await expect(turtleButton).toHaveAttribute('aria-pressed', 'true');
  const preview = page.getByLabel('Turtle 공개 메타데이터 원문');
  await expect(preview).toHaveText(expected);
  await preview.focus();
  await expect(preview).toBeFocused();
  await page.getByRole('button', { name: 'JSON-LD', exact: true }).click();
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  await expect(page.getByLabel('Turtle 공개 메타데이터 원문')).toHaveCount(0);
});

test('DCAT downloads refetch the real server and contain the selected page in both formats', async ({ page }) => {
  await page.goto(adminUrl);
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  for (const format of ['jsonld', 'turtle'] as const) {
    await page.getByRole('button', { name: format === 'jsonld' ? 'JSON-LD' : 'Turtle', exact: true }).click();
    await expect(page.getByLabel(`${format === 'jsonld' ? 'JSON-LD' : 'Turtle'} 공개 메타데이터 원문`)).toBeVisible();
    const responsePromise = page.waitForResponse(response => response.url().includes('/api/catalog/v1/dcat/download?')
      && response.url().includes(`format=${format}`));
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '이 페이지 다운로드', exact: true }).click();
    const [response, download] = await Promise.all([responsePromise, downloadPromise]);
    expect(response.ok()).toBeTruthy();
    expect(response.headers()['cache-control']).toContain('no-store');
    expect(await download.failure()).toBeNull();
    expect(download.suggestedFilename()).toBe(`catalog-1.${format === 'jsonld' ? 'jsonld' : 'ttl'}`);
    const path = await download.path();
    expect(path).not.toBeNull();
    if (!path) throw new Error('The browser did not save the download');
    const actual = await readFile(path, 'utf8');
    const source = await response.text();
    if (format === 'jsonld') expect(JSON.parse(actual)).toEqual(JSON.parse(source));
    else expect(actual).toBe(source);
    await expect(page.getByRole('button', { name: '이 페이지 다운로드', exact: true })).toBeEnabled();
    await expect(page.getByRole('status').filter({ hasText: '현재 공개 상태를 다시 확인' })).toBeVisible();
  }
});

test('DCAT browser network failure clears the old preview and retries against the real server', async ({ page, context }) => {
  await page.goto(adminUrl);
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  try {
    // This interrupts actual browser networking; no request is fulfilled with a synthetic response.
    await context.setOffline(true);
    await page.getByRole('button', { name: '새로고침', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('공개 피드를 불러오지 못했습니다');
    await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '이 페이지 다운로드', exact: true })).toBeDisabled();
  } finally { await context.setOffline(false); }
  await page.getByRole('button', { name: '다시 시도', exact: true }).click();
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('DCAT mobile preserves the original check assets and fits its viewport', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(adminUrl);
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  const icons = page.getByRole('region', { name: '외부 메타데이터 제공' }).getByRole('list').locator('img');
  await expect(icons).toHaveCount(3);
  for (const icon of await icons.all()) {
    await expect(icon).toHaveJSProperty('naturalWidth', 16);
    await expect(icon).toHaveJSProperty('naturalHeight', 16);
    const box = await icon.boundingBox();
    expect(box?.width).toBe(16);
    expect(box?.height).toBe(16);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath('p5-dcat-mobile.png'), fullPage: true });
});
