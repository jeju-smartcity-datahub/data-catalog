import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import ts from '../node_modules/typescript/lib/typescript.js';

const root = fileURLToPath(new URL('../src/', import.meta.url));
const modules = new Map();
async function moduleUrl(filename) {
  if (modules.has(filename)) return modules.get(filename);
  let source = ts.transpileModule(await readFile(filename, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
  for (const match of [...source.matchAll(/from ['"]([^'"]+)['"]/g)]) {
    const target = path.resolve(path.dirname(filename), `${match[1]}.ts`);
    source = source.replace(match[0], `from ${JSON.stringify(await moduleUrl(target))}`);
  }
  const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
  modules.set(filename, url);
  return url;
}
const endpoint = await import(await moduleUrl(path.join(root, 'features/hubs/endpointInput.ts')));
const progress = await import(await moduleUrl(path.join(root, 'features/hubs/runStatus.ts')));
const pagination = await import(await moduleUrl(path.join(root, 'features/hubs/hubPageWindow.ts')));
const errors = await import(await moduleUrl(path.join(root, 'features/hubs/hubError.ts')));
const { AdminApiError } = await import(await moduleUrl(path.join(root, 'lib/admin.ts')));

test('프로토콜 없는 주소는 HTTPS, HTTP 전체 주소의 포트·기본 경로는 보존', () => {
  assert.equal(endpoint.endpointValue('https', ' 192.0.2.42:8080/base '), 'https://192.0.2.42:8080/base');
  const pasted = endpoint.splitEndpoint(' HTTP://192.0.2.42:8080/base ', 'https');
  assert.deepEqual(pasted, { protocol: 'http', address: '192.0.2.42:8080/base' });
  assert.equal(endpoint.endpointValue(pasted.protocol, pasted.address), 'http://192.0.2.42:8080/base');
  assert.equal(endpoint.endpointValue('http', ''), '');
  assert.equal(endpoint.endpointError('', 'KETI_DATACORE', false), '');
});

test('주소 누락·형식·허브별 경로를 분리하고 입력값을 변형하지 않음', () => {
  assert.match(endpoint.endpointError('', 'KETI_DATACORE', true), /주소를 입력/);
  assert.match(endpoint.endpointError('http://host:999999', 'KETI_DATACORE', false), /포트를 확인/);
  assert.match(endpoint.endpointError('http://host/base/datasets/a', 'KETI_DATACORE', false), /\/datasets 앞/);
  assert.match(endpoint.endpointError('https://host/ngsi-ld/v1/entities', 'NGSI_LD', false), /\/entities 앞/);
  for (const value of ['https://http://host', 'https://user:password@host', 'ftp://host', 'https://host?q=secret', 'https://host/#token', 'https://bad host']) assert.notEqual(endpoint.endpointError(value, 'KETI_DATACORE', false), '');
  assert.equal(endpoint.endpointError('http://192.0.2.42:8080/base', 'KETI_DATACORE', true), '');
});

test('서버 주소 오류는 지정된 한국어 문구만 표시하며 응답 원문은 노출하지 않음', () => {
  const missing = new AdminApiError(400, 'request-1', { code: 'ENDPOINT_REQUIRED', detail: 'secret-value' });
  assert.equal(missing.code, 'ENDPOINT_REQUIRED');
  assert.equal(missing.message, '메타데이터 API 주소를 입력하세요.');
  assert.equal(missing.requestId, 'request-1');
  assert.match(new AdminApiError(400, undefined, { code: 'SOURCE_NOT_APPROVED' }).message, /허용 목록/);
  assert.match(new AdminApiError(409, undefined, { code: 'SYNC_ALREADY_ACTIVE' }).message, /진행 중인 작업/);
  assert.doesNotMatch(new AdminApiError(400, undefined, { code: 'unknown', detail: 'secret-value' }).message, /secret-value/);
});

test('완료 후 목록 갱신은 최초 전환에만 수행하고 정기 작업도 감지', () => {
  const finished = [{ id: 'a', status: 'SUCCEEDED' }];
  assert.equal(progress.completedSince(null, finished), false);
  assert.equal(progress.completedSince(new Map([['a', 'RUNNING']]), finished), true);
  assert.equal(progress.completedSince(new Map([['a', 'SUCCEEDED']]), finished), false);
  assert.equal(progress.completedSince(new Map([['a', 'SUCCEEDED']]), [...finished, { id: 'scheduled', status: 'SUCCEEDED' }]), true);
  assert.equal(progress.completedSince(new Map([['a', 'RUNNING']]), [{ id: 'a', status: 'RETRY_WAIT' }]), false);
  assert.equal(progress.activeRun({ status: 'PARTIAL_FAILED' }), false);
});

test('서버 단계·대기·재시도·종료 상태를 실제 상태대로 구분', () => {
  const run = { id: 'a', kind: 'CHECK', status: 'QUEUED', phase: 'PREPARING', createdAt: '2026-11-05T00:00:00Z', progressAt: '2026-11-05T00:00:00Z' };
  assert.equal(progress.runLabel(run), '검사 시작 준비');
  assert.equal(progress.runLabel({ ...run, kind: 'SYNC', status: 'RUNNING', phase: 'FETCHING_DETAILS' }), '상세·필드 조회 중');
  assert.equal(progress.runLabel({ ...run, status: 'RETRY_WAIT' }), '재시도 준비');
  assert.match(progress.runDelay(run, Date.parse('2026-11-05T00:01:01Z')), /작업 시작이 지연/);
  assert.equal(progress.runDelay({ ...run, status: 'SUCCEEDED' }, Date.parse('2026-11-05T00:01:01Z')), '');
});

test('최근 조회 목록을 10행씩 표시하며 자동 갱신 시 페이지를 유지·보정', () => {
  const rows = Array.from({ length: 53 }, (_, id) => ({ id }));
  assert.deepEqual(pagination.pageWindow(53, 1), { page: 1, pages: 6, start: 11, end: 20 });
  assert.equal(pagination.pageRows(rows, 1).items.length, 10);
  assert.equal(pagination.pageRows(rows, 1).items[0].id, 10);
  assert.equal(pagination.pageRows([{ id: -1 }, ...rows], 1).page, 1);
  assert.equal(pagination.pageRows(rows.slice(0, 11), 2).page, 1);
  assert.deepEqual(pagination.pageWindow(0, 9), { page: 0, pages: 1, start: 0, end: 0 });
  assert.equal(pagination.pageRows(rows, 5).items.length, 3);
});

test('오류 안내와 요청 ID를 분리하여 진단 펼침 영역에 전달', () => {
  const failure = errors.hubFailure(new AdminApiError(400, 'request-only-in-details', { code: 'INVALID_ENDPOINT', detail: 'hidden-credential' }));
  assert.equal(failure.requestId, 'request-only-in-details');
  assert.equal(failure.code, 'INVALID_ENDPOINT');
  assert.doesNotMatch(failure.message, /request-only-in-details|hidden-credential/);
});

test('같은 페이지 재조회는 유효한 선택만 유지하고 다른 페이지 성공 시 선택 해제', () => {
  assert.deepEqual(pagination.selectionAfterPage(['first', 'removed'], 0, 0, ['first', 'second']), ['first']);
  assert.deepEqual(pagination.selectionAfterPage(['first'], 0, 1, ['next']), []);
  assert.deepEqual(pagination.selectionAfterPage(['next'], 1, 1, ['next']), ['next']);
});
