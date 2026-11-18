import { expect, test, type APIRequestContext, type APIResponse, type BrowserContext, type Page } from '@playwright/test';

// Public test fixtures only. Never substitute a user's account/password here.
// Created by the owner-controlled fixture script against loopback 15433/_test.
const fixturePassword = 'Public-test-only-2026!';
const users = {
  admin: { username: 'e2e-local-admin', id: '7a000000-0000-4000-8000-000000000001' },
  editor: { username: 'e2e-local-editor', id: '7a000000-0000-4000-8000-000000000002' },
  auditor: { username: 'e2e-local-auditor', id: '7a000000-0000-4000-8000-000000000003' },
  disabled: { username: 'e2e-local-disabled', id: '7a000000-0000-4000-8000-000000000004' },
};
const ownOrganization = '7b000000-0000-4000-8000-000000000001';
const otherOrganization = '7b000000-0000-4000-8000-000000000002';
const api = '/api/admin/v1';
// Different local ports share a cookie host. Keep this test session isolated
// from the ordinary development server's JSESSIONID.
const cookieName = process.env.LOCAL_AUTH_E2E_SESSION_COOKIE_NAME || 'CATALOG_LOCAL_AUTH_TEST_SESSION';
type Csrf = { csrfHeaderName: string; csrfToken: string };
type Session = Csrf & { userId: string; organizationIds: string[]; authorities: string[] };

function target(path: string) {
  const base = process.env.LOCAL_AUTH_E2E_ADMIN_URL;
  if (!base || process.env.LOCAL_AUTH_E2E_CONFIRMED_TEST_DB !== '1') throw new Error('Run only with the dedicated local-auth Playwright configuration after owner verification.');
  const url = new URL(base);
  const prefix = url.pathname.replace(/\/$/, '');
  return new URL(path.startsWith('/api/') ? path : `${prefix}${path}`, url.origin).href;
}
function csrfHeaders(token: Csrf) { return { [token.csrfHeaderName]: token.csrfToken }; }
async function assertProblem(response: APIResponse, status: number, code: string) {
  expect(response.status()).toBe(status);
  const body = await response.json() as { code?: string };
  expect(body.code).toBe(code);
}
async function bootstrap(request: APIRequestContext): Promise<Csrf> {
  const response = await request.get(target(`${api}/auth`));
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toContain('no-store');
  const data = await response.json() as Csrf & { localLoginEnabled: boolean };
  expect(data.localLoginEnabled, 'The dedicated test server must explicitly enable local auth').toBe(true);
  expect(data.csrfHeaderName).toBe('X-CSRF-TOKEN');
  expect(typeof data.csrfToken === 'string' && data.csrfToken.length > 20).toBe(true);
  return data;
}
async function session(request: APIRequestContext): Promise<Session> {
  const response = await request.get(target(`${api}/session`));
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toContain('no-store');
  return response.json() as Promise<Session>;
}
async function login(request: APIRequestContext, account: keyof typeof users = 'admin') {
  const csrf = await bootstrap(request);
  const response = await request.post(target(`${api}/auth/login`), {
    headers: csrfHeaders(csrf), data: { username: users[account].username, password: fixturePassword },
  });
  expect(response.status(), 'Only the designated public _test fixture may authenticate').toBe(204);
  expect(response.headers()['cache-control']).toContain('no-store');
  const authenticated = await session(request);
  expect(authenticated.userId).toBe(users[account].id);
  return { before: csrf, authenticated };
}
async function sessionCookie(context: BrowserContext) {
  const cookie = (await context.cookies(target('/'))).find(item => item.name === cookieName);
  expect(Boolean(cookie), 'Expected a server-owned HttpOnly session cookie').toBe(true);
  return cookie!;
}
async function uiLogin(page: Page, returnTo = '/metadata') {
  await page.goto(target(`/login?returnTo=${encodeURIComponent(returnTo)}`));
  await expect(page.getByRole('heading', { name: '관리자 로그인', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '로컬 계정 로그인', exact: true })).toBeVisible();
  await page.getByLabel('아이디', { exact: true }).fill(users.admin.username);
  await page.getByLabel('비밀번호', { exact: true }).fill(fixturePassword);
  const submitted = page.waitForResponse(response => new URL(response.url()).pathname === `${api}/auth/login` && response.request().method() === 'POST');
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  expect((await submitted).status()).toBe(204);
}

test.describe('Real local authentication on isolated PostgreSQL fixtures — not SSO acceptance', () => {
  test('LA-E2E-01 anonymous protected routes reject forged browser roles and organizations', async ({ request }) => {
    const response = await request.get(target(`${api}/session`), { headers: {
      'X-User-Id': users.admin.id, 'X-Role': 'PLATFORM_ADMIN', 'X-Roles': 'ROLE_PLATFORM_ADMIN', 'X-Organization-Id': ownOrganization,
    } });
    await assertProblem(response, 401, 'AUTHENTICATION_REQUIRED');
    await assertProblem(await request.get(target(`${api}/organizations/${ownOrganization}/access`)), 401, 'AUTHENTICATION_REQUIRED');
  });

  test('LA-E2E-02 bootstrap creates a no-store CSRF session with protected cookie attributes', async ({ context, page }) => {
    await bootstrap(context.request);
    const cookie = await sessionCookie(context);
    expect(cookie.httpOnly).toBe(true);
    expect(['Lax', 'Strict'].includes(cookie.sameSite)).toBe(true);
    await page.goto(target('/login'));
    expect(await page.evaluate(name => document.cookie.includes(`${name}=`), cookieName)).toBe(false);
    await assertProblem(await context.request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
  });

  test('LA-E2E-03 missing and forged CSRF never authenticate a correct fixture password', async ({ request }) => {
    const csrf = await bootstrap(request);
    const data = { username: users.admin.username, password: fixturePassword };
    await assertProblem(await request.post(target(`${api}/auth/login`), { data }), 403, 'ACCESS_DENIED');
    await assertProblem(await request.post(target(`${api}/auth/login`), { data, headers: { [csrf.csrfHeaderName]: 'forged-test-token' } }), 403, 'ACCESS_DENIED');
    await assertProblem(await request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
  });

  test('LA-E2E-04 browser shows a real failed login then authenticates the public test account', async ({ page, context }) => {
    await page.goto(target('/login?returnTo=%2Fmetadata%3Fview%3Dtest%23scope'));
    await page.getByLabel('아이디', { exact: true }).fill(users.admin.username);
    await page.getByLabel('비밀번호', { exact: true }).fill('Incorrect-public-test-value!');
    const failed = page.waitForResponse(response => new URL(response.url()).pathname === `${api}/auth/login` && response.request().method() === 'POST');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    expect((await failed).status()).toBe(401);
    await expect(page.getByRole('alert')).toBeVisible();
    await assertProblem(await context.request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
    await page.getByLabel('아이디', { exact: true }).fill(users.admin.username);
    await page.getByLabel('비밀번호', { exact: true }).fill(fixturePassword);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    await expect(page).toHaveURL(target('/metadata?view=test#scope'));
    expect((await session(context.request)).userId).toBe(users.admin.id);
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
    expect(await page.evaluate(() => localStorage.length)).toBe(0);
  });

  test('LA-E2E-05 authentication rotates the session ID and the prior cookie cannot be replayed', async ({ context, playwright }) => {
    await bootstrap(context.request);
    const before = await sessionCookie(context);
    await login(context.request);
    const after = await sessionCookie(context);
    // Compare booleans so session identifiers are never printed in an assertion.
    expect(before.value === after.value).toBe(false);
    const replay = await playwright.request.newContext({ extraHTTPHeaders: { Cookie: `${cookieName}=${before.value}` } });
    try { await assertProblem(await replay.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED'); }
    finally { await replay.dispose(); }
  });

  test('LA-E2E-06 pre-login CSRF is rejected and logout invalidates the authenticated session', async ({ context, playwright }) => {
    const { before, authenticated } = await login(context.request);
    const cookie = await sessionCookie(context);
    await assertProblem(await context.request.post(target(`${api}/auth/logout`), { headers: csrfHeaders(before) }), 403, 'ACCESS_DENIED');
    expect((await session(context.request)).userId).toBe(users.admin.id);
    const logout = await context.request.post(target(`${api}/auth/logout`), { headers: csrfHeaders(authenticated) });
    expect(logout.status()).toBe(204);
    expect(logout.headers()['cache-control']).toContain('no-store');
    await assertProblem(await context.request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
    const replay = await playwright.request.newContext({ extraHTTPHeaders: { Cookie: `${cookieName}=${cookie.value}` } });
    try { await assertProblem(await replay.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED'); }
    finally { await replay.dispose(); }
  });

  test('LA-E2E-07 organization editor scope comes from the server despite forged admin headers', async ({ request }) => {
    const { authenticated } = await login(request, 'editor');
    expect(authenticated.organizationIds).toEqual([ownOrganization]);
    expect(authenticated.authorities).toContain('ROLE_ORG_EDITOR');
    expect(authenticated.authorities).not.toContain('ROLE_PLATFORM_ADMIN');
    expect((await request.get(target(`${api}/organizations/${ownOrganization}/access`))).status()).toBe(200);
    await assertProblem(await request.get(target(`${api}/organizations/${otherOrganization}/access`), { headers: { 'X-Role': 'PLATFORM_ADMIN', 'X-Organization-Id': otherOrganization } }), 403, 'ACCESS_DENIED');
    await assertProblem(await request.get(target(`${api}/datasets?organizationId=${otherOrganization}`)), 403, 'ACCESS_DENIED');
  });

  test('LA-E2E-08 platform scope permits another organization but default-denies unknown endpoints', async ({ request }) => {
    const { authenticated } = await login(request);
    expect(authenticated.authorities).toContain('ROLE_PLATFORM_ADMIN');
    expect((await request.get(target(`${api}/organizations/${otherOrganization}/access`))).status()).toBe(200);
    await assertProblem(await request.get(target('/api/local-auth-unknown')), 403, 'ACCESS_DENIED');
  });

  test('LA-E2E-09 inactive and unknown accounts receive the same generic failure', async ({ request }) => {
    const csrf = await bootstrap(request);
    for (const username of [users.disabled.username, 'e2e-local-not-registered']) {
      const response = await request.post(target(`${api}/auth/login`), { headers: csrfHeaders(csrf), data: { username, password: fixturePassword } });
      await assertProblem(response, 401, 'LOCAL_LOGIN_FAILED');
      expect(response.headers()['cache-control']).toContain('no-store');
    }
    await assertProblem(await request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
  });

  test('LA-E2E-10 real server idle expiry restores the protected-route login gate', async ({ page, context }) => {
    const idleSeconds = Number(process.env.LOCAL_AUTH_E2E_SESSION_TIMEOUT_SECONDS || '60');
    // Embedded Tomcat rounds positive configured durations up to at least one
    // minute. Exercise its real expiry instead of adding a test-only bypass.
    expect(Number.isInteger(idleSeconds) && idleSeconds >= 60 && idleSeconds <= 120).toBe(true);
    test.setTimeout((idleSeconds + 30) * 1_000);
    await uiLogin(page);
    await expect(page).toHaveURL(target('/metadata'));
    await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
    await session(context.request);
    // An actual server timeout; no cookie deletion, fake clock or mocked 401.
    await page.waitForTimeout((idleSeconds + 2) * 1_000);
    await assertProblem(await context.request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
    await page.reload();
    await expect(page.getByRole('heading', { name: '관리자 로그인이 필요합니다', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인 화면으로 이동', exact: true })).toBeVisible();
  });

  test('LA-E2E-11 browser logout and refresh do not restore protected contents', async ({ page, context }) => {
    await uiLogin(page, '/hubs');
    await expect(page).toHaveURL(target('/hubs'));
    await page.getByRole('button', { name: '로그아웃', exact: true }).click();
    await expect(page).toHaveURL(target('/login?reason=logged-out'));
    await assertProblem(await context.request.get(target(`${api}/session`)), 401, 'AUTHENTICATION_REQUIRED');
    await page.goto(target('/hubs'));
    await page.reload();
    await expect(page.getByRole('heading', { name: '관리자 로그인이 필요합니다', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인 화면으로 이동', exact: true })).toBeVisible();
  });

  test('LA-E2E-12 unsafe returnTo falls back while approved internal query and hash are retained', async ({ page }) => {
    await uiLogin(page, 'https://example.invalid/untrusted');
    await expect(page).toHaveURL(target('/metadata'));
    for (const returnTo of ['//example.invalid/untrusted', '/\\example.invalid/untrusted', '/login', '/unknown-local-route']) {
      await page.goto(target(`/login?returnTo=${encodeURIComponent(returnTo)}`));
      await expect(page.getByRole('link', { name: '관리 화면으로 이동', exact: true })).toHaveAttribute('href', new URL(target('/metadata')).pathname);
    }
    const approved = new URL(target('/metadata?view=scope#selected'));
    await page.goto(target(`/login?returnTo=${encodeURIComponent(`${approved.pathname}${approved.search}${approved.hash}`)}`));
    await expect(page.getByRole('link', { name: '관리 화면으로 이동', exact: true })).toHaveAttribute('href', `${approved.pathname}${approved.search}${approved.hash}`);
  });

  test('LA-E2E-14 organization auditor can read scoped audit events without editor privileges', async ({ request }) => {
    const { authenticated } = await login(request, 'auditor');
    expect(authenticated.organizationIds).toEqual([ownOrganization]);
    expect(authenticated.authorities).toEqual(['ROLE_ORG_AUDITOR']);
    const ownAudit = await request.get(target(`${api}/audit-events?organizationId=${ownOrganization}`));
    expect(ownAudit.status()).toBe(200);
    expect(ownAudit.headers()['cache-control']).toContain('no-store');
    await assertProblem(await request.get(target(`${api}/audit-events?organizationId=${otherOrganization}`)), 403, 'ACCESS_DENIED');
    await assertProblem(await request.get(target(`${api}/organizations/${ownOrganization}/access`)), 403, 'ACCESS_DENIED');
  });

  test('LA-E2E-15 bootstrap network failure recovers through real retry with mobile keyboard controls', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 });
    const bootstrapUrl = target(`${api}/auth`);
    await page.route(bootstrapUrl, route => route.abort('connectionfailed'));
    await page.goto(target('/login'));
    await expect(page.getByRole('alert')).toContainText('로그인 설정을 불러오지 못했습니다');
    await expect(page.getByLabel('비밀번호', { exact: true })).toHaveCount(0);
    await page.unroute(bootstrapUrl);
    await page.getByRole('button', { name: '로그인 설정 다시 확인', exact: true }).click();
    const username = page.getByLabel('아이디', { exact: true });
    await expect(username).toBeVisible();
    await expect(username).toBeEnabled();
    await username.focus();
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('비밀번호', { exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  });

  test('LA-E2E-13 account throttling returns Retry-After and disables browser retries despite spoofed forwarding headers', async ({ request, page }) => {
    const limit = Number(process.env.LOCAL_AUTH_E2E_ACCOUNT_ATTEMPTS || '20');
    expect(Number.isInteger(limit) && limit >= 5 && limit <= 30).toBe(true);
    const csrf = await bootstrap(request);
    for (let attempt = 0; attempt <= limit; attempt += 1) {
      const response = await request.post(target(`${api}/auth/login`), {
        headers: { ...csrfHeaders(csrf), 'X-Forwarded-For': `192.0.2.${attempt + 1}`, 'X-Real-IP': `192.0.2.${attempt + 1}` },
        data: { username: 'e2e-local-rate-limit-unknown', password: fixturePassword },
      });
      if (attempt < limit) await assertProblem(response, 401, 'LOCAL_LOGIN_FAILED');
      else {
        await assertProblem(response, 429, 'LOGIN_RATE_LIMITED');
        expect(Number(response.headers()['retry-after'])).toBeGreaterThan(0);
        expect(response.headers()['cache-control']).toContain('no-store');
      }
    }
    await page.goto(target('/login'));
    await page.getByLabel('아이디', { exact: true }).fill('e2e-local-rate-limit-unknown');
    await page.getByLabel('비밀번호', { exact: true }).fill(fixturePassword);
    const limited = page.waitForResponse(response => new URL(response.url()).pathname === `${api}/auth/login` && response.request().method() === 'POST');
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    expect((await limited).status()).toBe(429);
    await expect(page.getByRole('alert')).toContainText('로그인 요청이 많습니다');
    await expect(page.getByText(/초 후 다시 로그인할 수 있습니다\./)).toBeVisible();
    await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeDisabled();
  });
});
