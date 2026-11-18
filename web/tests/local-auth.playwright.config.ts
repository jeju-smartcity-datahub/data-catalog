import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

// This suite never starts a service or seeds a database. The owner must first
// verify that the existing admin proxy targets the dedicated local-auth _test DB.
if (process.env.LOCAL_AUTH_E2E_CONFIRMED_TEST_DB !== '1') {
  throw new Error('Confirm the dedicated _test DB and existing process ownership before running local-auth E2E.');
}
if (!process.env.LOCAL_AUTH_E2E_ADMIN_URL) throw new Error('Set the owner-verified existing local-auth admin URL.');
const target = new URL(process.env.LOCAL_AUTH_E2E_ADMIN_URL);
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || target.username || target.password || target.search || target.hash) {
  throw new Error('Local-auth E2E requires an explicit loopback HTTP admin URL without credentials or query.');
}

export default defineConfig({
  testDir: '.',
  testMatch: 'local-auth.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 8_000 },
  outputDir: resolve(__dirname, '../../.runtime/local-auth-20261017/tests/results'),
  reporter: [['list'], ['json', { outputFile: resolve(__dirname, '../../.runtime/local-auth-20261017/tests/playwright-results.json') }]],
  use: {
    baseURL: target.href,
    // Do not retain request payloads, cookies or CSRF tokens in traces/videos.
    trace: 'off', screenshot: 'off', video: 'off',
  },
  projects: [{ name: 'local-auth-chromium', use: { ...devices['Desktop Chrome'] } }],
});
