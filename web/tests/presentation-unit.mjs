import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/lib/presentation.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { recordsRange, formatDateTime, isNumericColumn, recordValueText, collectionStatusLabel } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

test('records range distinguishes the displayed page from an upstream total', () => {
  assert.equal(recordsRange(10, 2, 12), '표시 11–12행 / 전체 12행');
  assert.equal(recordsRange(0, 10, null), '표시 1–10행 · 전체 행 수 미제공');
  assert.equal(recordsRange(0, 0, 0), '표시 0행 / 전체 0행');
  assert.equal(recordsRange(10, 0, 10), '표시 0행 / 전체 10행');
  for (const total of [-1, 2.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1]) assert.match(recordsRange(0, 10, total), /미제공$/);
});

test('missing timestamps cannot be substituted with publication or current time', () => {
  assert.equal(formatDateTime(null), '미제공');
  assert.equal(formatDateTime('not-a-date'), '미제공');
  assert.equal(formatDateTime('2026-11-08T00:01:02Z'), '2026. 11. 8. 9:01:02');
});

test('numeric formatting uses the actual value and preserves string identifiers', () => {
  for (const type of ['INTEGER', 'decimal(12,4)', 'Number', 'float']) assert.equal(isNumericColumn(type), true);
  for (const type of ['String', 'DateTime', null, 'Number[]']) assert.equal(isNumericColumn(type), false);
  assert.equal(recordValueText(1234.56789), '1,234.56789');
  assert.equal(recordValueText('001234'), '001234');
  assert.equal(recordValueText(null), '—');
  assert.equal(recordValueText({ value: 2 }), '{"value":2}');
});

test('collection status never substitutes a failure or unknown value with success', () => {
  assert.equal(collectionStatusLabel('SUCCEEDED'), '완료');
  assert.equal(collectionStatusLabel('PARTIAL_FAILED'), '일부 실패');
  assert.equal(collectionStatusLabel('FAILED'), '실패');
  assert.equal(collectionStatusLabel('UNKNOWN'), '상태 정보 없음');
  assert.equal(collectionStatusLabel('NOT_LINKED'), '연결 없음');
});
