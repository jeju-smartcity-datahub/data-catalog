import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const child = spawn(process.execPath, [fileURLToPath(new URL('./server.mjs', import.meta.url)), 'test'], {
  stdio: 'inherit', env: { ...process.env, CATALOG_EXTERNAL_TEST_DB: 'true' },
});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
