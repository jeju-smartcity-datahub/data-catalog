import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { root } from './dev-env.mjs';

// Deliberately bounded local measurement; no external target or deployment SLO assumed.
const base = process.env.CATALOG_SMOKE_URL || 'http://127.0.0.1:8080';
const target = new URL(base);
if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) || target.username || target.password || target.search || target.hash || !['http:', 'https:'].includes(target.protocol)) throw new Error('Only a local URL without credentials is accepted.');
const samples = [];
let issued = 0;
const started = performance.now();
async function worker() {
  while (issued++ < 100) {
    const start = performance.now();
    let status = 0;
    try {
      const response = await fetch(new URL('/api/catalog/v1/datasets?size=10', target), { signal: AbortSignal.timeout(5000) });
      status = response.status;
      const data = await response.json();
      if (!Array.isArray(data.items) || !data.page) status = -1;
    } catch { status = 0; }
    samples.push({ ms: performance.now() - start, status });
  }
}
await Promise.all(Array.from({ length: 5 }, worker));
const durations = samples.map(sample => sample.ms).sort((a, b) => a - b);
const result = { measuredAt: new Date().toISOString(), scope: 'local catalog smoke only; not production load certification', requests: samples.length, concurrency: 5, failures: samples.filter(sample => sample.status !== 200).length, durationMs: Math.round(performance.now() - started), p50Ms: Math.round(durations[49]), p95Ms: Math.round(durations[94]), maxMs: Math.round(durations.at(-1)) };
mkdirSync(join(root, '.runtime/verification'), { recursive: true });
writeFileSync(join(root, '.runtime/verification/load-smoke.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify(result));
if (result.failures) process.exitCode = 1;
