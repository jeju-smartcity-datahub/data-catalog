import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from '../node_modules/typescript/lib/typescript.js';

const source = await readFile(new URL('../src/lib/requestId.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const load = crypto => {
  const context = vm.createContext({ exports: {}, crypto });
  vm.runInContext(js, context);
  return context.exports.createRequestId;
};
const checks = [];
const check = (name, run) => { run(); checks.push({ name, passed: true }); };
const v4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

check('native randomUUID retains Crypto receiver', () => {
  const crypto = { randomUUID() { assert.equal(this, crypto); return '4f678237-3144-4fe4-a986-ce71f4991364'; } };
  assert.equal(load(crypto)(), '4f678237-3144-4fe4-a986-ce71f4991364');
});
check('HTTP fallback works without randomUUID and preserves version and variant', () => {
  const crypto = { getRandomValues(bytes) { assert.equal(this, crypto); bytes.fill(255); return bytes; } };
  assert.equal(load(crypto)(), 'ffffffff-ffff-4fff-bfff-ffffffffffff');
});
check('zero bytes retain padding and required UUID bits', () => {
  assert.equal(load({ getRandomValues: bytes => bytes })(), '00000000-0000-4000-8000-000000000000');
});
check('HTTP fallback uses cryptographic randomness with distinct valid request identifiers', () => {
  const create = load({ getRandomValues: bytes => webcrypto.getRandomValues(bytes) });
  const ids = Array.from({ length: 10000 }, () => create());
  ids.forEach(id => assert.match(id, v4));
  assert.equal(new Set(ids).size, ids.length);
});
check('missing secure randomness fails instead of using weak random identifiers', () => {
  assert.throws(() => load(undefined)(), /요청 식별자/);
});

const output = new URL('../test-results/request-id-http/', import.meta.url);
await mkdir(output, { recursive: true });
await writeFile(new URL('result.json', output), JSON.stringify({ result: 'PASS', checks, recordedAt: new Date().toISOString() }, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', checks: checks.length, output: output.pathname }));
