import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests', fullyParallel: true, retries: process.env.CI ? 1 : 0,
  testIgnore: '**/local-auth*.spec.ts',
  use: { baseURL: 'http://127.0.0.1:3000', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    { command: 'npm run start', url: 'http://127.0.0.1:3000', reuseExistingServer: !process.env.CI, timeout: 30000 },
    { command: 'npm --prefix ../admin run dev', url: 'http://127.0.0.1:3001', reuseExistingServer: !process.env.CI, timeout: 30000 },
    { command: 'node node_modules/next/dist/bin/next start --hostname 127.0.0.1 --port 3002', url: 'http://127.0.0.1:3002', env: { SERVER_API_URL: 'http://127.0.0.1:9' }, reuseExistingServer: !process.env.CI, timeout: 30000 },
    { command: 'node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 3003 --strictPort', cwd: '../admin', url: 'http://127.0.0.1:3003', reuseExistingServer: !process.env.CI, timeout: 30000 },
  ],
});
