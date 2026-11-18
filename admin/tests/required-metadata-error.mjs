import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import ts from '../node_modules/typescript/lib/typescript.js';
const source = await readFile(new URL('../src/lib/requiredMetadataError.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const { safeRequiredMetadataMessage } = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
const labels = ['제목','설명','제공기관','공개등급','담당부서','라이선스','키워드'];
const message = values => '등록 필수항목을 입력하세요: ' + values.join(', ') + '. 초안은 보관할 수 있습니다.';
const problem = values => ({ code:'REQUIRED_METADATA_MISSING', detail:message(values), debug:'DISALLOWED_FIELD_VALUE' });
const checks = [];
for(let mask=1; mask<128; mask++) { const selected=labels.filter((_,i)=>mask & (1<<i)); assert.equal(safeRequiredMetadataMessage(422,problem([...selected].reverse())),message(selected)); checks.push(`allowed-label-subset-${mask}`); }
for(const status of [0,200,400,401,403,409,412,500]) { assert.equal(safeRequiredMetadataMessage(status,problem(['설명'])),null); checks.push(`reject-status-${status}`); }
const malicious = [null,[],{}, {code:'OTHER',detail:message(['설명'])}, {code:'REQUIRED_METADATA_MISSING',detail:'DISALLOWED_DETAIL_VALUE'}, problem(['설명','DISALLOWED_DETAIL_VALUE']), problem(['설명','설명']), problem([]), {code:'REQUIRED_METADATA_MISSING',detail:message(['설명'])+'debug'}, {code:'REQUIRED_METADATA_MISSING',detail:message(['설명']).replace('설명','설명\n')}, {code:'REQUIRED_METADATA_MISSING',detail:message(labels).replace(', ', ',')}, {code:'REQUIRED_METADATA_MISSING',detail:'x'.repeat(301)}];
malicious.forEach((value,i)=>{assert.equal(safeRequiredMetadataMessage(422,value),null);checks.push(`reject-untrusted-problem-${i}`);});
assert.ok(!safeRequiredMetadataMessage(422,problem(['설명'])).includes('DISALLOWED_FIELD_VALUE'));
const output = new URL('../test-results/privacy-ui/',import.meta.url); await mkdir(output,{recursive:true});
await writeFile(new URL('required-error-unit.json',output),JSON.stringify({result:'PASS',recordedAt:new Date().toISOString(),kind:'FIXED_METADATA_ERROR_WHITELIST',checks:checks.length,acceptedLabelSubsets:127,untrustedFieldValuesDisplayed:false},null,2)+'\n');
console.log(JSON.stringify({result:'PASS',checks:checks.length,acceptedLabelSubsets:127}));
