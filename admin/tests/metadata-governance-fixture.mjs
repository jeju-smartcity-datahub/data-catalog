import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

// Isolated browser contract fixtures only. This never signs in to the server and does
// not establish that SSO, authorization, persistence, or authenticated E2E works.
const output = fileURLToPath(new URL('../../.runtime/admin-ui-fixtures/', import.meta.url));
await mkdir(output, { recursive: true });
const org = '11111111-1111-4111-8111-111111111111';
const categoryId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const topicId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const regionId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const hubId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const dataset = '22222222-2222-4222-8222-222222222222';
const version = '33333333-3333-4333-8333-333333333333';
const termId = '44444444-4444-4444-8444-444444444444';
const wordId = '55555555-5555-4555-8555-555555555555';
const domainId = '66666666-6666-4666-8666-666666666666';
const importId = '77777777-7777-4777-8777-777777777777';
const extractedId = '88888888-8888-4888-8888-888888888888';
const user = '99999999-9999-4999-8999-999999999999';
const field = { fieldPath: 'name', physicalName: 'name', displayName: '명칭', description: '검증용 필드', dataType: 'STRING', nullable: false, maxLength: 200, unit: null, ordinal: 0, publicVisible: true, approvedTermId: null, mappingEvidence: null };
const draft = { id: dataset, slug: 'ui-contract-fixture', ownerOrganizationId: org, revisionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', revisionNo: 1, state: 'DRAFT', title: '계약 검증 데이터셋', description: '브라우저 격리 시험의 데이터입니다.', providerOrganizationId: org, primaryCategoryId: categoryId, visibility: 'PUBLIC', lockVersion: 1, currentPublicationId: null, reviewRequestId: null, reviewDecisionId: null, updatedAt: '2026-10-16T09:00:00Z', regionIds: [regionId], hubIds: [hubId], topicIds: [topicId], details: { department: '데이터 담당', license: '제공 계약 검토 필요', updateFrequency: '매일', spatialCoverage: '시험 지역', temporalStart: '2026-01-01', temporalEnd: '2026-12-31', keywords: ['교통', '공영주차장'] } };
const dictionary = { id: version, scope: 'ORGANIZATION', organizationId: org, code: 'ui_fixture', name: '검증 사전', versionLabel: 'test-only', sourceUri: null, sourceChecksum: null };
const entry = (id, kind, name, state = 'APPROVED') => ({ id, dictionaryId: version, kind, code: kind.toLowerCase(), name, definition: '격리 UI 계약 시험', dataType: 'STRING', state, lockVersion: 1, createdBy: user, updatedBy: user, submittedBy: null, decidedBy: null, decisionReason: null });
const term = entry(termId, 'TERM', '검증 용어', 'DRAFT');
const words = [entry(wordId, 'WORD', '검증 단어')];
const domains = [entry(domainId, 'DOMAIN', '검증 도메인')];
let relation = null;
const writes = [];
const browserErrors = [];
const unexpected = [];
let docsMode = 'ready';
let dataAccess = { datasetId: dataset, configured: true, connectionId: hubId, enabled: false, mode: 'JSON_ARRAY', queryPath: '/entities', scopeField: 'datasetId', scopeValue: 'A', scopeParameter: 'datasetId', fixedQuery: {}, lockVersion: 1, ready: false, checkedAt: null };
let listMode = 'ready'; let releaseList; let draftMode = 'ready'; let releaseDraft; let evidenceMode = 'ready'; let fieldsMode = 'ready'; let auditMode = 'ready'; let listPages = false;
const browser = await chromium.launch({ headless: true });
let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/docs/v1/datasets/*', route => route.fulfill({
    status: docsMode === 'error' ? 503 : docsMode === 'not-public' ? 404 : 200,
    contentType: 'application/json', body: JSON.stringify({ datasetId: dataset, publicationId: version,
      publicationVersion: 2, accessState: 'METADATA_ONLY', documentKind: 'METADATA_API',
      message: '게시된 메타정보와 필드정보를 JSON API로 조회할 수 있습니다.',
      documents: [`/api/catalog/v1/datasets/${dataset}`, '/api/catalog/v1/dcat', 'https://unexpected.invalid/private'],
    }),
  }));
  await context.route('**/api/admin/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname.replace('/api/admin/v1', ''); const method = request.method();
    const json = (body, etag, status = 200) => route.fulfill({ status, contentType: 'application/json', headers: etag ? { ETag: etag } : {}, body: JSON.stringify(body) });
    if (method !== 'GET') { writes.push({ path, method, headers: request.headers(), body: request.headers()['content-type']?.includes('application/json') ? request.postDataJSON() : null }); }
    if (path === '/session') return json({ userId: user, organizationIds: [org], authorities: ['ROLE_ORG_EDITOR', 'ROLE_ORG_PUBLISHER', 'ROLE_ORG_AUDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-fixture-token' });
    if (path === '/connections' && method === 'GET') return json([{ id: hubId, organizationId: org, name: '검증 허브', enabled: true, endpointConfigured: true }]);
    if (path === `/datasets/${dataset}/data-access` && method === 'GET') return json(dataAccess, `"data-access:${dataset}:${dataAccess.lockVersion}"`);
    if (path === `/datasets/${dataset}/data-access` && method === 'PUT') { dataAccess = { ...dataAccess, ...request.postDataJSON(), lockVersion: dataAccess.lockVersion + 1, ready: false }; return json(dataAccess, `"data-access:${dataset}:${dataAccess.lockVersion}"`); }
    if (path === `/datasets/${dataset}/data-access/check` && method === 'POST') { assert.ok(request.headers()['idempotency-key']); assert.equal(request.headers()['if-match'], `"data-access:${dataset}:${dataAccess.lockVersion}"`); dataAccess = { ...dataAccess, lockVersion: dataAccess.lockVersion + 1, ready: true, checkedAt: '2026-11-02T04:00:00Z' }; return json({ settings: dataAccess, recordCount: 1 }, `"data-access:${dataset}:${dataAccess.lockVersion}"`); }
    if (path === '/metadata-options') return json({ organizations: [{ id: org, name: '검증 기관' }], categories: [{ id: categoryId, name: '교통' }], topics: [{ id: topicId, name: '주차·이동', parentId: categoryId }], regions: [{ id: regionId, name: '시험 지역' }], hubs: [{ id: hubId, name: '검증 허브' }] });
    if (path === `/publications/${version}` && method === 'GET') return json({ id: version, datasetId: dataset, revisionId: draft.revisionId, publicationNo: 2, state: 'PUBLISHED', accessState: 'METADATA_ONLY', publishedAt: '2026-11-02T04:00:00Z', lockVersion: 1 }, `"publication:${version}:1"`);
    if (path === '/datasets' && method === 'GET') { if (listMode === 'loading') await new Promise(resolve => { releaseList = resolve; }); if (listMode === 'error') return json({ code: 'SERVICE_UNAVAILABLE' }, undefined, 503); const number = Number(url.searchParams.get('page') || 0); return json({ items: listMode === 'empty' ? [] : listPages ? Array.from({ length: number === 0 ? 10 : 1 }, (_, index) => ({ ...draft, id: index === 0 ? dataset : `${index}-fixture`, title: index === 0 ? draft.title : `검증 데이터 ${number * 10 + index + 1}` })) : [draft], page: { number, size: 10, totalElements: listMode === 'empty' ? 0 : listPages ? 11 : 1, totalPages: listMode === 'empty' ? 0 : listPages ? 2 : 1 } }); }
    if (path === '/datasets' && method === 'POST') return json(draft, `"dataset:${dataset}:1"`, 201);
    if (path === `/datasets/${dataset}/draft` && method === 'GET') { if (draftMode === 'loading') await new Promise(resolve => { releaseDraft = resolve; }); if (draftMode === 'error') return json({}, undefined, 503); return json(draft, `"dataset:${dataset}:1"`); }
    if (path === `/datasets/${dataset}/draft` && method === 'PATCH') return json({ code: 'OPTIMISTIC_LOCK_CONFLICT' }, undefined, 412);
    if (path === `/datasets/${dataset}/draft/fields`) return json({ revisionId: draft.revisionId, lockVersion: 1, fields: fieldsMode === 'empty' ? [] : [field] }, `"dataset:${dataset}:1"`);
    if (path === `/datasets/${dataset}/audit-events`) return auditMode === 'error' ? json({}, undefined, 503) : json({ items: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } });
    if (path === '/definition-imports' && method === 'POST') return json({ id: importId, organizationId: org, datasetId: dataset, sourceHash: 'fixture-hash', sourceSize: 345, parserVersion: 'explicit-json-test', status: 'SUCCEEDED', fieldCount: 1, warnings: [], createdAt: '2026-10-16T09:00:00Z', fields: [{ id: extractedId, sourcePointer: '/fields/0', field, evidenceKind: 'EXPLICIT_JSON_FIELD' }], analysisRunId: importId }, undefined, 201);
    if (path === `/definition-imports/${importId}/evidence`) return evidenceMode === 'error' ? json({}, undefined, 503) : json([{ fieldPath: 'name', sourcePointer: '/fields/0', evidenceKind: 'EXPLICIT_JSON_FIELD', explanation: '명시적 JSON 필드 정의를 검증해 읽었습니다.', sourceHash: 'fixture-hash', field }]);
    if (path === `/definition-imports/${importId}/applications`) return json({ applicationId: extractedId, importId, datasetId: dataset, fields: { revisionId: draft.revisionId, lockVersion: 2, fields: [field] } }, undefined, 201);
    if (path === '/dictionaries') return json([dictionary]);
    if (path === `/dictionaries/${version}/entries`) return json(url.searchParams.get('kind') === 'DOMAIN' ? domains : url.searchParams.get('kind') === 'WORD' ? words : [term]);
    if (path === `/dictionary-entries/${termId}`) return json(term, `"dictionary-entry:${termId}:${term.lockVersion}"`);
    if (path === `/dictionary-entries/${wordId}`) return json(words[0], `"dictionary-entry:${wordId}:1"`);
    if (path === `/dictionary-entries/${domainId}`) return json(domains[0], `"dictionary-entry:${domainId}:1"`);
    if (path === `/dictionary-entries/${termId}/relationships`) return json({ entryId: termId, kind: 'TERM', lockVersion: term.lockVersion, word: null, domain: null, term: relation }, `"dictionary-entry:${termId}:${term.lockVersion}"`);
    if (path === `/dictionary-entries/${termId}/term` && method === 'PUT') { relation = request.postDataJSON(); term.lockVersion += 1; return json({ entryId: termId, kind: 'TERM', lockVersion: term.lockVersion, word: null, domain: null, term: relation }, `"dictionary-entry:${termId}:${term.lockVersion}"`); }
    if (path === `/dictionary-versions/${version}/exports`) return json({ id: importId, dictionaryId: version, kind: 'TERM', query: null, state: null, status: 'SUCCEEDED', entryCount: 1, createdAt: '2026-10-16T09:00:00Z', expiresAt: '2026-10-17T09:00:00Z', contentHash: 'fixture', fileName: 'dictionary-fixture.xlsx' }, undefined, 202);
    if (path === `/dictionary-exports/${importId}/file`) return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('isolated transport fixture, not a real workbook') });
    unexpected.push(`${method} ${path}`); return json({ error: 'Unexpected fixture request' }, undefined, 500);
  });
  page = await context.newPage(); page.on('pageerror', error => browserErrors.push(error.message));
  await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/metadata`);
  await expect(page.getByRole('button', { name: draft.title, exact: true })).toBeVisible();
  await expect(page.locator('#page-heading-actions').getByRole('button', { name: '정의서 등록', exact: true })).toBeVisible();
  const arrows = await page.locator('ol[aria-label="메타데이터 게시 절차"] img').evaluateAll(images => images.map(image => ({ width: image.getBoundingClientRect().width, height: image.getBoundingClientRect().height, complete: image.complete && image.naturalWidth > 0 }))); assert.equal(arrows.length, 2); assert.ok(arrows.every(image => image.complete && image.width === 14 && image.height === 14));
  await auditAccessibility(page, 'metadata-list'); await page.screenshot({ path: `${output}/metadata-list.png` });
  await page.getByRole('button', { name: '초안 등록', exact: true }).click();
  let modal = page.getByRole('dialog', { name: '메타데이터 초안 등록', exact: true });
  await modal.getByRole('textbox', { name: /^데이터셋 이름/ }).fill('등록 계약 검증');
  await modal.getByRole('textbox', { name: /^데이터 설명/ }).fill('격리 브라우저 시험');
  await modal.getByLabel('데이터셋 식별 이름').fill('ui-contract-create');
  await modal.getByRole('textbox', { name: /^담당부서/ }).fill('교통 담당부서');
  await modal.getByLabel('라이선스', { exact: false }).fill('별도 제공 조건 확인');
  await modal.getByText('추가 정보 (선택)', { exact: true }).click();
  await modal.getByLabel('데이터 갱신 주기', { exact: true }).fill('매일');
  await modal.getByLabel('공간 범위', { exact: true }).fill('시험 지역');
  await modal.getByLabel('기간 시작일', { exact: true }).fill('2026-01-01');
  await modal.getByLabel('기간 종료일', { exact: true }).fill('2026-12-31');
  await modal.getByRole('textbox', { name: /^키워드/ }).fill('주차\n공영주차장');
  await modal.getByRole('combobox', { name: '분야', exact: true }).selectOption(categoryId);
  for (const [label, name] of [['주제', '주차·이동'], ['지역', '시험 지역'], ['연결 허브', '검증 허브']]) { const group = modal.getByRole('group', { name: label, exact: true }); await group.locator('summary').click(); await group.getByRole('checkbox', { name, exact: true }).check(); await group.locator('summary').click(); }
  await modal.evaluate(element => { element.scrollTop = 0; });
  await auditAccessibility(page, 'metadata-create');
  await page.screenshot({ path: `${output}/metadata-create.png`, fullPage: false });
  await modal.getByLabel('기간 시작일', { exact: true }).scrollIntoViewIfNeeded(); await auditAccessibility(page, 'metadata-basic-details'); await page.screenshot({ path: `${output}/metadata-basic-details.png` });
  await modal.getByRole('button', { name: '데이터셋 이름 비우기', exact: true }).click(); await expect(modal.getByRole('textbox', { name: /^데이터셋 이름/ })).toBeFocused(); await modal.getByRole('textbox', { name: /^데이터셋 이름/ }).fill('등록 계약 검증');
  await modal.getByRole('textbox', { name: /^키워드/ }).fill('주차\n주차'); await modal.getByRole('button', { name: '초안 등록', exact: true }).click(); await expect(modal.getByRole('alert')).toContainText('키워드는 중복 없이'); assert.equal(writes.filter(item => item.path === '/datasets').length, 0); await modal.getByRole('textbox', { name: /^키워드/ }).fill('주차\n공영주차장');
  await modal.getByLabel('기간 종료일', { exact: true }).fill('2025-12-31'); await modal.getByRole('button', { name: '초안 등록', exact: true }).click(); assert.equal(writes.filter(item => item.path === '/datasets').length, 0); assert.equal(await modal.getByLabel('기간 종료일', { exact: true }).evaluate(element => element.validity.rangeUnderflow), true); await modal.getByLabel('기간 종료일', { exact: true }).fill('2026-12-31');
  await modal.getByRole('button', { name: '초안 등록', exact: true }).click();
  await expect(modal).toHaveCount(0);
  const create = writes.find(item => item.path === '/datasets');
  assert.equal(create.headers['x-csrf-token'], 'isolated-fixture-token'); assert.ok(create.headers['idempotency-key']); assert.equal(create.body.ownerOrganizationId, org); assert.equal(create.body.slug, 'ui-contract-create');
  assert.deepEqual(create.body.topicIds, [topicId]); assert.deepEqual(create.body.regionIds, [regionId]); assert.deepEqual(create.body.hubIds, [hubId]); assert.deepEqual(create.body.details, { department: '교통 담당부서', license: '별도 제공 조건 확인', updateFrequency: '매일', spatialCoverage: '시험 지역', temporalStart: '2026-01-01', temporalEnd: '2026-12-31', keywords: ['주차', '공영주차장'] });
  await page.getByRole('button', { name: draft.title, exact: true }).click();
  modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true });
  await modal.getByRole('textbox', { name: /^데이터셋 이름/ }).fill('충돌 후 보존할 내용');
  const basicTab = modal.getByRole('tab', { name: '기본정보', exact: true }); await basicTab.focus(); await page.keyboard.press('ArrowRight'); await expect(basicTab).toBeFocused(); await expect(basicTab).toHaveAttribute('aria-selected', 'true');
  await modal.getByRole('button', { name: '변경 저장', exact: true }).click();
  await expect(modal.getByRole('alert')).toContainText('다른 담당자가 내용을 변경했습니다');
  await expect(modal.getByRole('textbox', { name: /^데이터셋 이름/ })).toHaveValue('충돌 후 보존할 내용');
  assert.equal(writes.find(item => item.method === 'PATCH').headers['if-match'], `"dataset:${dataset}:1"`);
  page.once('dialog', dialog => dialog.accept()); await modal.getByRole('button', { name: '취소', exact: true }).click();
  await page.getByRole('button', { name: draft.title, exact: true }).click(); modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true });
  await modal.getByRole('tab', { name: '기본정보', exact: true }).focus(); await page.keyboard.press('ArrowRight'); await expect(modal.getByRole('tab', { name: /^필드정보/ })).toHaveAttribute('aria-selected', 'true');
  await expect(modal.getByRole('textbox', { name: /^물리명/ })).toHaveValue('name');
  const clearBounds = await modal.getByRole('button', { name: '필드 1 물리명 비우기', exact: true }).locator('img').boundingBox(); assert.equal(clearBounds.width, 12.5); assert.equal(clearBounds.height, 12.5);
  await auditAccessibility(page, 'metadata-fields'); await page.screenshot({ path: `${output}/metadata-fields.png` });
  await modal.getByRole('button', { name: '필드 추가', exact: true }).click(); const addedField = modal.getByRole('group', { name: '필드 2', exact: true }); await addedField.getByRole('textbox', { name: /^필드 경로/ }).fill('new_field'); await expect(addedField.getByRole('textbox', { name: /^필드 경로/ })).toBeVisible(); await addedField.getByRole('textbox', { name: /^물리명/ }).fill('new_field'); await addedField.getByRole('textbox', { name: /^표시이름/ }).fill('새 필드'); await addedField.getByLabel('최대 길이', { exact: true }).fill('0'); await addedField.locator('summary').click(); await modal.getByRole('button', { name: '변경 저장', exact: true }).click(); await expect(addedField.getByLabel('최대 길이', { exact: true })).toBeVisible(); await expect(addedField.getByLabel('최대 길이', { exact: true })).toBeFocused(); await modal.getByRole('button', { name: '필드 2 삭제', exact: true }).click();
  await modal.getByRole('tab', { name: /^필드정보/ }).focus(); await page.keyboard.press('ArrowRight'); await expect(modal.getByRole('tabpanel')).toContainText('현재 공개된 게시본이 없습니다.');
  await auditAccessibility(page, 'metadata-access'); await page.screenshot({ path: `${output}/metadata-access.png` });
  await modal.getByLabel('사용자 데이터 조회 제공').check();
  await modal.getByRole('button', { name: '저장 후 조회 시험', exact: true }).click();
  await expect(modal.getByRole('status').filter({ hasText: '조회 연결 확인 완료' })).toHaveText('조회 연결 확인 완료 · 1개 응답 확인');
  assert.equal(writes.filter(item => item.path.endsWith('/data-access/check')).length, 1);
  await page.screenshot({ path: `${output}/metadata-records-settings.png` });
  auditMode = 'error'; await modal.getByRole('tab', { name: '변경 이력', exact: true }).click(); await expect(modal.getByRole('alert')).toBeVisible(); auditMode = 'ready'; await modal.getByRole('button', { name: '변경 이력 다시 불러오기' }).click(); await expect(modal.getByRole('status')).toContainText('기록된 변경 이력이 없습니다.');
  await modal.getByRole('button', { name: '취소', exact: true }).click();
  draft.currentPublicationId = version;
  docsMode = 'error';
  await page.getByRole('button', { name: draft.title, exact: true }).click();
  modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true });
  await modal.getByRole('tab', { name: '데이터 이용 경로', exact: true }).click();
  await expect(modal.getByRole('alert')).toContainText('게시본의 API 정보를 불러오지 못했습니다.');
  docsMode = 'ready'; await modal.getByRole('button', { name: '다시 조회', exact: true }).click();
  await expect(modal.getByRole('link', { name: `/api/catalog/v1/datasets/${dataset}`, exact: true })).toHaveAttribute('href', `/api/catalog/v1/datasets/${dataset}`);
  await expect(modal.getByRole('link', { name: '/api/catalog/v1/dcat', exact: true })).toHaveAttribute('href', '/api/catalog/v1/dcat');
  await expect(modal.getByText('게시 버전 2', { exact: true })).toBeVisible();
  await expect(modal.locator('a[href*="unexpected.invalid"]')).toHaveCount(0);
  await modal.getByRole('button', { name: '취소', exact: true }).click();
  docsMode = 'not-public';
  await page.getByRole('button', { name: draft.title, exact: true }).click();
  modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true });
  await modal.getByRole('tab', { name: '데이터 이용 경로', exact: true }).click();
  await expect(modal.getByText('현재 공개된 게시본이 없습니다.', { exact: true })).toBeVisible();
  await expect(modal.getByRole('link')).toHaveCount(0);
  await modal.getByRole('button', { name: '취소', exact: true }).click();
  draft.currentPublicationId = null;
  await page.getByRole('button', { name: '정의서 등록', exact: true }).click();
  modal = page.getByRole('dialog', { name: '정의서 등록', exact: true });
  await modal.getByRole('combobox', { name: /^대상 초안/ }).selectOption(dataset);
  await auditAccessibility(page, 'metadata-import-before'); await page.screenshot({ path: `${output}/metadata-import-before.png` });
  await modal.getByLabel('정의서 파일').setInputFiles({ name: 'definition.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ fields: [field] })) });
  await modal.getByRole('button', { name: '파일 검증·필드 추출', exact: true }).click();
  await expect(modal.getByRole('button', { name: '선택한 필드 초안에 적용' })).toBeDisabled();
  const definitionPreview = modal.getByRole('region', { name: '서버에서 검증한 필드 정의 JSON' });
  await expect(definitionPreview).toBeVisible();
  assert.deepEqual(JSON.parse(await definitionPreview.locator('pre').textContent()), { fields: [field] });
  await definitionPreview.focus(); await expect(definitionPreview).toBeFocused();
  await definitionPreview.press('ArrowDown');
  await expect.poll(() => definitionPreview.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  evidenceMode = 'error'; await modal.getByRole('button', { name: '추출 근거 보기', exact: true }).click();
  const evidence = page.getByRole('dialog', { name: '정의서 추출 근거', exact: true }); await expect(evidence.getByRole('alert')).toBeVisible(); evidenceMode = 'ready'; await evidence.getByRole('button', { name: '추출 근거 다시 불러오기' }).click(); await expect(evidence).toContainText('/fields/0');
  await auditAccessibility(page, 'metadata-import-evidence'); await page.screenshot({ path: `${output}/metadata-import-evidence.png` });
  await evidence.getByRole('button', { name: '닫기', exact: true }).click();
  await modal.getByLabel('선택 필드로 초안의 기존 필드 전체를 교체하는 것을 확인했습니다.').check();
  await auditAccessibility(page, 'definition-import');
  await page.screenshot({ path: `${output}/definition-import.png`, fullPage: false });
  await modal.getByRole('button', { name: '선택한 필드 초안에 적용', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '메타데이터 상세', exact: true })).toBeVisible();
  const application = writes.find(item => item.path.endsWith('/applications'));
  assert.deepEqual(application.body.selectedFieldIds, [extractedId]); assert.equal(application.headers['if-match'], `"dataset:${dataset}:1"`); assert.ok(application.headers['idempotency-key']);
  await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/standards`);
  await page.getByRole('button', { name: '우리 기관 사전', exact: true }).click();
  const termSelection = page.getByRole('checkbox', { name: '검증 용어 선택', exact: true });
  const pageSelection = page.getByRole('checkbox', { name: '현재 페이지 전체 선택', exact: true });
  await expect(termSelection).toBeVisible();
  for (const control of [termSelection, pageSelection]) {
    assert.equal(await control.evaluate(element => element.labels.length), 1);
    assert.equal(await control.evaluate(element => element.labels[0].htmlFor), await control.getAttribute('id'));
  }
  await termSelection.focus(); await page.keyboard.press('Space');
  await expect(termSelection).toBeChecked(); await expect(pageSelection).toBeChecked();
  await pageSelection.focus(); await page.keyboard.press('Space');
  await expect(termSelection).not.toBeChecked(); await expect(pageSelection).not.toBeChecked();
  await auditAccessibility(page, 'dictionary-list');
  await page.getByRole('button', { name: '검증 용어', exact: true }).click();
  modal = page.getByRole('dialog', { name: '표준사전 항목 상세', exact: true });
  await expect(modal.getByRole('button', { name: '검토 요청', exact: true })).toBeDisabled();
  await modal.getByRole('button', { name: '속성 및 관계 편집', exact: true }).click();
  const relationship = page.getByRole('dialog', { name: '검증 용어 · 용어 구성', exact: true });
  await relationship.getByRole('textbox', { name: /^물리명/ }).fill('test_name');
  await relationship.getByRole('button', { name: '도메인 선택', exact: true }).click();
  await page.getByRole('dialog', { name: '승인 도메인 선택', exact: true }).getByRole('button', { name: '선택', exact: true }).click();
  await relationship.getByRole('button', { name: '단어 추가', exact: true }).click();
  await page.getByRole('dialog', { name: '승인 단어 선택', exact: true }).getByRole('button', { name: '선택', exact: true }).click();
  await auditAccessibility(page, 'dictionary-term');
  await page.screenshot({ path: `${output}/dictionary-term.png`, fullPage: false });
  await relationship.getByRole('button', { name: '속성 저장', exact: true }).click();
  await expect(relationship).toHaveCount(0); await expect(modal.getByRole('button', { name: '검토 요청', exact: true })).toBeEnabled();
  const relationWrite = writes.find(item => item.path.endsWith('/term')); assert.deepEqual(relationWrite.body.wordEntryIds, [wordId]); assert.equal(relationWrite.body.domainEntryId, domainId); assert.equal(relationWrite.headers['if-match'], `"dictionary-entry:${termId}:1"`);
  await modal.getByRole('button', { name: '닫기', exact: true }).click();
  const download = page.waitForEvent('download'); await page.getByRole('button', { name: '검색 조건 전체 XLSX', exact: true }).click(); await download;
  assert.equal(writes.find(item => item.path.endsWith('/exports')).body.kind, 'TERM');
  await page.getByRole('button', { name: '우리 기관 사전', exact: true }).click(); await page.getByRole('button', { name: '표준 용어', exact: true }).click();
  await expect(page.getByRole('button', { name: '검증 용어', exact: true })).toBeVisible();
  listMode = 'loading'; await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/metadata`);
  await expect(page.getByRole('status')).toContainText('목록을 불러오는 중입니다.'); await auditAccessibility(page, 'metadata-list-loading');
  listMode = 'error'; releaseList(); await expect(page.getByRole('alert')).toBeVisible(); await auditAccessibility(page, 'metadata-list-error'); await page.screenshot({ path: `${output}/metadata-list-error.png` });
  listMode = 'empty'; await page.getByRole('button', { name: '목록 다시 불러오기', exact: true }).click(); await expect(page.getByRole('status')).toContainText('조건에 맞는 메타데이터가 없습니다.'); await auditAccessibility(page, 'metadata-list-empty');
  listMode = 'ready'; listPages = true; await page.getByRole('button', { name: '새로고침', exact: true }).click(); await expect(page.getByRole('button', { name: '2 페이지', exact: true })).toBeVisible(); await page.getByRole('button', { name: '2 페이지', exact: true }).click(); await expect(page.getByRole('button', { name: '2 페이지', exact: true })).toHaveAttribute('aria-current', 'page'); await page.getByRole('button', { name: '첫 페이지', exact: true }).click(); await expect(page.getByRole('button', { name: '1 페이지', exact: true })).toHaveAttribute('aria-current', 'page'); listPages = false;
  draftMode = 'loading'; await page.getByRole('button', { name: draft.title, exact: true }).click(); modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true }); await expect(modal.getByRole('status')).toContainText('메타데이터를 불러오는 중입니다.'); await auditAccessibility(page, 'metadata-detail-loading'); draftMode = 'error'; releaseDraft(); await expect(modal.getByRole('alert')).toBeVisible(); await auditAccessibility(page, 'metadata-detail-error'); draftMode = 'ready'; fieldsMode = 'empty'; await modal.getByRole('button', { name: '최신 내용 불러오기', exact: true }).click(); await modal.getByRole('tab', { name: /^필드정보/ }).click(); await expect(modal).toContainText('등록된 필드가 없습니다.'); await auditAccessibility(page, 'metadata-fields-empty'); await modal.getByRole('button', { name: '취소', exact: true }).click(); fieldsMode = 'ready';
  await page.setViewportSize({ width: 390, height: 844 }); await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/metadata`);
  await expect(page.getByRole('button', { name: draft.title, exact: true })).toBeVisible(); await auditAccessibility(page, 'metadata-list-mobile'); await page.screenshot({ path: `${output}/metadata-list-mobile.png` });
  await page.getByRole('button', { name: '초안 등록', exact: true }).click();
  modal = page.getByRole('dialog', { name: '메타데이터 초안 등록', exact: true }); await expect(modal).toBeVisible();
  await expect.poll(() => modal.evaluate(element => element.contains(document.activeElement))).toBe(true);
  await page.keyboard.press('Tab'); await expect.poll(() => modal.evaluate(element => element.contains(document.activeElement))).toBe(true);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth) <= 390);
  const bounds = await modal.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
  await auditAccessibility(page, 'metadata-create-mobile');
  await page.screenshot({ path: `${output}/metadata-create-mobile.png`, fullPage: false });
  await modal.getByText('추가 정보 (선택)', { exact: true }).click();
  await modal.getByLabel('기간 종료일', { exact: true }).focus();
  const inputBounds = await modal.getByLabel('기간 종료일', { exact: true }).boundingBox(); const footerBounds = await modal.getByRole('button', { name: '초안 등록', exact: true }).boundingBox(); assert.ok(inputBounds.y + inputBounds.height <= footerBounds.y, 'Focused date must not be obscured by the sticky footer.');
  await page.keyboard.press('Escape'); await expect(modal).toHaveCount(0);
  await page.getByRole('button', { name: draft.title, exact: true }).click(); modal = page.getByRole('dialog', { name: '메타데이터 상세', exact: true }); await modal.getByRole('tab', { name: /^필드정보/ }).click(); await auditAccessibility(page, 'metadata-fields-mobile'); await page.screenshot({ path: `${output}/metadata-fields-mobile.png` });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth) <= 390); await modal.getByRole('button', { name: '취소', exact: true }).click();
  assert.deepEqual(browserErrors, []); assert.deepEqual(unexpected, []);
  console.log('Isolated admin UI fixtures: create headers and typed details, 412 input preservation, dirty/roving keyboard tabs, fields/access, audit retry, import confirmation/evidence retry/application, TERM relations/version, XLSX, repeat filter, list loading/error/empty/paging, detail loading/error/empty fields, mobile focus/scroll/sticky footer: 12 groups passed. No real SSO or server workflow tested.');
} catch (error) { if (page) { await page.screenshot({ path: `${output}/failure.png`, fullPage: false }); console.error('Fixture browser errors:', browserErrors); console.error('Fixture unexpected calls:', unexpected); } throw error; }
finally { await browser.close(); }

