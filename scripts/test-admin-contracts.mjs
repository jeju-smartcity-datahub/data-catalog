import { spawn } from 'node:child_process';
import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { stripVTControlCharacters } from 'node:util';
import { root } from './dev-env.mjs';

// Browser response fixtures are UI contracts, never proof of SSO or DB writes.
// Use an owned Vite process so CI does not depend on a user's running dev server.
const origin = 'http://127.0.0.1:3011';
const output = join(root, '.runtime', 'verification');
const scripts = (await readdir(join(root, 'admin', 'tests'))).filter(name => name.endsWith('-fixture.mjs')).sort();
if (!scripts.length) throw new Error('No admin UI contract fixtures found.');
await mkdir(output, { recursive: true });
const server = spawn(process.execPath, [join(root, 'admin', 'node_modules', 'vite', 'bin', 'vite.js'), '--host', '127.0.0.1', '--port', '3011', '--strictPort'], {
  cwd: join(root, 'admin'), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
});
let serverOutput = '';
let serverFailure;
let running;
const results = [];
const capture = chunk => { serverOutput = (serverOutput + chunk.toString()).slice(-8000); };
server.stdout.on('data', capture); server.stderr.on('data', capture);
server.on('error', error => { serverFailure = error; });
const cleanup = () => { running?.kill(); server.kill(); };
const interrupted = () => { cleanup(); process.exitCode = 130; };
process.once('SIGINT', interrupted); process.once('SIGTERM', interrupted);
try {
  for (let attempt = 0; ; attempt++) {
    if (serverFailure) throw serverFailure;
    if (server.exitCode !== null) throw new Error(`Isolated admin server exited: ${serverOutput}`);
    if (process.exitCode === 130) throw new Error('UI contract run interrupted.');
    if (stripVTControlCharacters(serverOutput).includes(origin)) {
      try { if ((await fetch(origin, { signal: AbortSignal.timeout(1000) })).ok) break; } catch { /* startup */ }
    }
    if (attempt >= 100) throw new Error(`Isolated admin server did not become ready: ${serverOutput}`);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  for (const script of scripts) {
    console.log(`UI contract: ${script}`);
    const started = Date.now();
    const exitCode = await new Promise((resolve, reject) => {
      running = spawn(process.execPath, [join(root, 'admin', 'tests', script)], {
        cwd: root, env: { ...process.env, ADMIN_UI_ORIGIN: origin }, windowsHide: true, stdio: 'inherit',
      });
      const timeout = setTimeout(() => { running?.kill(); reject(new Error(`${script} exceeded 60 seconds.`)); }, 60000);
      running.once('error', error => { clearTimeout(timeout); reject(error); });
      running.once('exit', code => { clearTimeout(timeout); running = undefined; resolve(code); });
    });
    results.push({ script, exitCode, durationMs: Date.now() - started });
    if (exitCode !== 0) throw new Error(`${script} failed (${exitCode}).`);
  }
  console.log(`Passed ${results.length} isolated UI contract files. Actual SSO and server mutations were not tested.`);
} finally {
  cleanup();
  await writeFile(join(output, 'admin-contracts.json'), JSON.stringify({ recordedAt: new Date().toISOString(), kind: 'ISOLATED_UI_CONTRACT_FIXTURES', realSso: false, realServerMutation: false, expectedFiles: scripts.length, results }, null, 2) + '\n');
  process.removeListener('SIGINT', interrupted); process.removeListener('SIGTERM', interrupted);
}
