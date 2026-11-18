import { chromium } from '../../web/node_modules/@playwright/test/index.mjs';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Actual anonymous browser UI only. No test login, route fulfillment, or fabricated API result.
const root = fileURLToPath(new URL('../../.runtime/admin-ui/', import.meta.url));
await mkdir(root, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
  page.on('pageerror', error => console.error('Browser error:', error.message));
  for (const path of ['metadata', 'classifications', 'standards', 'analytics', 'access']) {
    await page.goto(`http://127.0.0.1:3001/${path}`);
    try { await page.getByRole('heading', { name: '관리자 로그인이 필요합니다' }).waitFor(); }
    catch (error) { await page.screenshot({ path: `${root}/${path}-failure.png`, fullPage: true }); console.error('Visible page:', (await page.locator('body').innerText()).slice(0, 4000)); throw error; }
    if (await page.getByRole('button', { name: '초안 등록', exact: true }).count()) throw new Error('Anonymous editing control was visible');
    await page.screenshot({ path: `${root}/${path}-anonymous-desktop.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('http://127.0.0.1:3001/metadata');
  await page.getByRole('heading', { name: '관리자 로그인이 필요합니다' }).waitFor();
  if (await page.evaluate(() => document.documentElement.scrollWidth) > 390) throw new Error('Mobile viewport overflow');
  await page.screenshot({ path: `${root}/metadata-anonymous-mobile.png`, fullPage: true });
  console.log('Actual anonymous admin pages: 5 passed; mobile viewport: passed. No authenticated workflow tested.');
} finally {
  await browser.close();
}
