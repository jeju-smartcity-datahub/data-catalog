import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import ts from '../node_modules/typescript/lib/typescript.js';

const source = await readFile(new URL('../src/lib/definitionDiagnostics.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const { safeDefinitionFailure } = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
const id = '12345678-1234-1234-1234-123456789abc';
const diagnostics = () => ({ ruleVersion: 'explicit-field-evidence-v1', status: 'FAILED', inputFieldCount: 2, generatedFieldCount: 0,
  unextractedFields: [{ sourcePointer: '/fields/0', reasonCode: 'DOCUMENT_REJECTED', message: 'DISALLOWED_SOURCE_VALUE', properties: [] },
    { sourcePointer: '/fields/1', reasonCode: 'REQUIRED_PROPERTY_MISSING', message: 'DISALLOWED_SOURCE_VALUE', properties: ['physicalName'] }], assessments: [] });
const problem = value => ({ code: 'DEFINITION_PARSE_FAILED', analysisRunId: id, detail: 'DISALLOWED_SOURCE_VALUE', debug: 'DISALLOWED_SOURCE_VALUE', definitionDiagnostics: value });
const checks = [];
const accepted = safeDefinitionFailure(problem(diagnostics()));
assert.equal(accepted.analysisRunId, id); assert.equal(accepted.diagnostics.unextractedFields.length, 2);
assert.ok(!JSON.stringify(accepted).includes('DISALLOWED_SOURCE_VALUE')); checks.push('fixed-reasons-no-reflected-source-or-debug');
const document = diagnostics(); document.inputFieldCount = 0; document.unextractedFields = [{ sourcePointer: '/', reasonCode: 'INVALID_JSON', properties: [] }];
assert.equal(safeDefinitionFailure(problem(document)).diagnostics.unextractedFields[0].sourcePointer, '/'); checks.push('document-level-position');
const limit = diagnostics(); limit.inputFieldCount = 500; limit.unextractedFields = Array.from({ length: 500 }, (_, index) => ({ sourcePointer: `/fields/${index}`, reasonCode: 'DOCUMENT_REJECTED', properties: [] }));
assert.equal(safeDefinitionFailure(problem(limit)).diagnostics.unextractedFields.length, 500); checks.push('500-item-supported-boundary');
const invalid = [
  { ruleVersion: 'DISALLOWED_SOURCE_VALUE' }, { status: 'COMPLETE' }, { inputFieldCount: 501 }, { inputFieldCount: -1 }, { inputFieldCount: '2' },
  { generatedFieldCount: 1 }, { assessments: [{}] }, { unextractedFields: [] }, { unextractedFields: new Array(501).fill({}) },
  { unextractedFields: [{ sourcePointer: '/fields/500', reasonCode: 'DOCUMENT_REJECTED', properties: [] }] },
  { unextractedFields: [{ sourcePointer: '/fields/0/DISALLOWED_SOURCE_VALUE', reasonCode: 'DOCUMENT_REJECTED', properties: [] }] },
  { unextractedFields: [{ sourcePointer: '/fields/0', reasonCode: '__proto__', properties: [] }] },
  { unextractedFields: [{ sourcePointer: '/fields/0', reasonCode: 'DOCUMENT_REJECTED', properties: ['DISALLOWED_SOURCE_VALUE'] }] },
];
invalid.forEach((change, index) => { const result = safeDefinitionFailure(problem({ ...diagnostics(), ...change })); assert.equal(result.diagnostics, null); assert.equal(result.analysisRunId, id); checks.push(`reject-malformed-diagnostic-${index}`); });
for (const value of [undefined, null, [], {}, { code: 'OTHER', definitionDiagnostics: diagnostics() }]) { assert.equal(safeDefinitionFailure(value), null); checks.push('reject-unrelated-problem'); }
const untrustedId = safeDefinitionFailure({ ...problem(diagnostics()), analysisRunId: 'DISALLOWED_SOURCE_VALUE' });
assert.equal(untrustedId.analysisRunId, null); assert.ok(untrustedId.diagnostics); checks.push('reject-untrusted-analysis-reference');
const output = new URL('../test-results/definition-r13/', import.meta.url); await mkdir(output, { recursive: true });
await writeFile(new URL('diagnostics-safety.json', output), JSON.stringify({ status: 'pass', checks: checks.length, recordedAt: new Date().toISOString(), sourceOrDebugValuesReturned: false, actualServerOrFieldClaim: false }, null, 2) + '\n');
console.log(JSON.stringify({ status: 'pass', checks: checks.length, output: new URL('diagnostics-safety.json', output).pathname }));
