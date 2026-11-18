import { expect, test } from '@playwright/test';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

test('actual public catalog exposes named landmarks and accessible controls', async ({ page }) => {
  await page.goto('/datasets');
  await expect(page.getByRole('main')).toBeVisible();
  await auditAccessibility(page, 'actual-public-list', 'ACTUAL_LOCAL_API');
  await page.getByRole('link', { name: '본문으로 건너뛰기' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  await page.setViewportSize({ width: 320, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await auditAccessibility(page, 'actual-public-list-reflow', 'ACTUAL_LOCAL_API');
});

test('actual anonymous admin routes and live DCAT expose meaningful accessibility trees', async ({ page }) => {
  test.setTimeout(60000);
  for (const route of ['metadata', 'classifications', 'standards', 'hubs', 'analytics', 'access']) {
    await page.goto(`http://127.0.0.1:3001/${route}`);
    await expect(page.getByRole('heading', { name: '관리자 로그인이 필요합니다', exact: true })).toBeVisible();
    await expect(page).toHaveTitle(/데이터 카탈로그 관리/);
    await auditAccessibility(page, `actual-admin-${route}`, 'ACTUAL_ANONYMOUS_401');
  }
  await page.goto('http://127.0.0.1:3001/dcat');
  await expect(page.getByLabel('JSON-LD 공개 메타데이터 원문')).toBeVisible();
  await auditAccessibility(page, 'actual-dcat-jsonld', 'ACTUAL_LOCAL_API');
  await page.getByRole('button', { name: 'Turtle', exact: true }).click();
  await expect(page.getByLabel('Turtle 공개 메타데이터 원문')).toBeVisible();
  await page.setViewportSize({ width: 320, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await auditAccessibility(page, 'actual-dcat-turtle-reflow', 'ACTUAL_LOCAL_API');
});
