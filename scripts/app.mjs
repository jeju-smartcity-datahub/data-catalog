import { spawn } from 'node:child_process';
import { devEnv } from './dev-env.mjs';
const [app, command] = process.argv.slice(2);
if (!['web', 'admin'].includes(app) || !['dev', 'start', 'preview'].includes(command)) {
  throw new Error('Use app.mjs web|admin dev|start|preview');
}
const config = devEnv();
const env = { ...process.env };
for (const key of ['SERVER_API_URL', 'VITE_PUBLIC_CATALOG_URL']) {
  if (config[key]) env[key] = config[key];
}
const child = spawn(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['--prefix', app, 'run', command], {
  env, stdio: 'inherit', shell: process.platform === 'win32',
});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
