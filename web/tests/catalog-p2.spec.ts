import { expect, test } from '@playwright/test';

test('catalog search keeps filters in the URL and opens an immutable publication', async ({ page, request }) => {
  const data = await (await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets')).json();
  const dataset = data.items[0];
  await page.goto('/datasets');
  await page.getByRole('searchbox', { name: '데이터 검색', exact: true }).fill(dataset.title);
  await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page).toHaveURL(/q=/);
  await expect(page.getByRole('heading', { name: dataset.title, exact: true })).toBeVisible();
  await page.locator('input[type="checkbox"][value="' + dataset.organization.id + '"]').check();
  await page.getByRole('button', { name: '필터 적용', exact: true }).click();
  await expect(page).toHaveURL(/organizationId=/);
  await expect(page.locator('input[type="checkbox"][value="' + dataset.organization.id + '"]')).toBeChecked();
  await page.getByLabel('데이터 정렬').selectOption('title,asc');
  await page.getByRole('button', { name: '정렬', exact: true }).click();
  await expect(page).toHaveURL(/sort=title%2Casc/);
  await page.getByRole('link', { name: dataset.title, exact: true }).click();
  await expect(page.getByRole('heading', { name: dataset.title, exact: true })).toBeVisible();
  await page.getByRole('link', { name: '필드정보' }).click();
  await expect(page).toHaveURL(/version=\d+/);
  await expect(page.getByRole('heading', { name: '필드 구성과 의미' })).toBeVisible();
  await page.getByRole('link', { name: '데이터 미리보기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '데이터 미리보기', exact: true })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '데이터를 조회하고 있습니다.' })).toHaveCount(0);
  await page.getByRole('link', { name: '이용정보', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'API 이용정보' })).toBeVisible();
  const version = new URL(page.url()).searchParams.get('version');
  await expect(page.getByRole('link', { name: '데이터셋 메타정보 API JSON 조회' })).toHaveAttribute('href', `/api/catalog/v1/datasets/${dataset.id}/versions/${version}`);
  await expect(page.getByRole('link', { name: 'DCAT 카탈로그 API JSON 조회' })).toHaveAttribute('href', '/api/catalog/v1/dcat?page=0&size=100');
  await expect(page.getByText('데이터셋의 메타정보·필드 정의와 연결된 데이터 조회 API입니다.', { exact: true })).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    document.execCommand = command => command === 'copy' && document.activeElement instanceof HTMLTextAreaElement && document.activeElement.value.includes('/api/catalog/v1/datasets/');
  });
  await page.getByRole('button', { name: '데이터셋 메타정보 API 주소 복사', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('API 주소를 복사했습니다.');
});

test('metadata JSON dialog contains only public response and restores focus', async ({ page, request }) => {
  const data = await (await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets')).json();
  await page.goto('/datasets/' + data.items[0].id);
  const trigger = page.getByRole('button', { name: '메타정보 JSON 보기' });
  await trigger.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog')).toContainText('"publicationNo"');
  await expect(page.getByRole('dialog')).not.toContainText('mappingEvidence');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(trigger).toBeFocused();
});

test('header search works by keyboard and unknown public id has an explicit empty state', async ({ page }) => {
  await page.goto('/datasets/00000000-0000-0000-0000-000000000099');
  await expect(page.getByRole('heading', { name: '공개된 데이터셋을 찾을 수 없습니다' })).toBeVisible();
  const trigger = page.getByRole('button', { name: '데이터 검색 열기' });
  await trigger.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await trigger.click();
  await page.getByRole('dialog').getByLabel('키워드 검색').fill('결과가없는검색어-p2');
  await page.getByRole('dialog').getByRole('button', { name: '검색', exact: true }).click();
  await expect(page.getByRole('heading', { name: '검색 결과가 없습니다' })).toBeVisible();
});

test('public detail and search stay within the mobile viewport', async ({ page, request }) => {
  const data = await (await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets')).json();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/datasets/' + data.items[0].id);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  const icons = page.locator('main img');
  expect(await icons.count()).toBeGreaterThan(0);
  for (const icon of await icons.all()) {
    // Next images below the mobile fold are lazy loaded. Inspect each image only
    // after bringing it into view and observing successful resource completion.
    await icon.scrollIntoViewIfNeeded();
    await expect.poll(() => icon.evaluate(image =>
      (image as HTMLImageElement).complete && (image as HTMLImageElement).naturalWidth > 0,
    )).toBe(true);
    const dimensions = await icon.evaluate(image => ({
      expected: Number(image.getAttribute('width')), actual: image.getBoundingClientRect().width,
    }));
    expect(dimensions.actual).toBe(dimensions.expected);
  }
  await page.getByRole('link', { name: '이용정보', exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('keyboard filter selection and search text survive reload and browser history', async ({ page, request }) => {
  const data = await (await request.get('http://127.0.0.1:8080/api/catalog/v1/datasets')).json();
  const dataset = data.items[0];
  await page.goto('/datasets?q=' + encodeURIComponent(dataset.title));
  const checkbox = page.locator('input[type="checkbox"][value="' + dataset.organization.id + '"]');
  // Streaming can attach the filter inside a hidden segment before revealing it.
  // focus() alone does not wait until that element can receive keyboard input.
  await expect(checkbox).toBeVisible();
  await checkbox.focus();
  await expect(checkbox).toBeFocused();
  await page.keyboard.press('Space');
  await expect(checkbox).toBeChecked();
  const applyFilters = page.getByRole('button', { name: '필터 적용', exact: true });
  await expect(applyFilters).toBeVisible();
  await applyFilters.focus();
  await expect(applyFilters).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/organizationId=/);
  await page.reload();
  await expect(page.getByRole('searchbox', { name: '데이터 검색', exact: true })).toHaveValue(dataset.title);
  await expect(checkbox).toBeChecked();
  await page.getByRole('link', { name: '초기화', exact: true }).click();
  await expect(page).toHaveURL(/\/datasets$/);
  await expect(page.getByRole('searchbox', { name: '데이터 검색', exact: true })).toHaveValue('');
  await expect(checkbox).not.toBeChecked();
  await page.goBack();
  await expect(page.getByRole('searchbox', { name: '데이터 검색', exact: true })).toHaveValue(dataset.title);
  await expect(checkbox).toBeChecked();
});
