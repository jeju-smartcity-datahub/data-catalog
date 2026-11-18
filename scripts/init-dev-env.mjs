import { existsSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
const file = new URL('../.env.dev', import.meta.url);
if (existsSync(file)) {
  console.log('.env.dev already exists; preserved.');
} else {
  writeFileSync(file, [
    '# Local development only; generated secrets must not be committed.',
    'POSTGRES_DB=datacatalog', 'POSTGRES_USER=datacatalog',
    `POSTGRES_PASSWORD=${randomBytes(24).toString('hex')}`,
    'POSTGRES_PORT=15432', 'POSTGRES_TEST_PORT=15433',
    'SERVER_PORT=8080',
    'SERVER_API_URL=http://127.0.0.1:8080', '',
  ].join('\n'), { flag: 'wx', mode: 0o600 });
  console.log('Created .env.dev with a random local database password.');
}
