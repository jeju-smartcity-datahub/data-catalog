import assert from 'node:assert/strict';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';

// Isolated contract UI fixtures: no real SSO, real standard data, or backend writes.
const origin = process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001';
const output = fileURLToPath(new URL('../../.runtime/admin-ui-fixtures/', import.meta.url));
await mkdir(output, { recursive: true });
const org = '11111111-1111-4111-8111-111111111111';
const dictId = '22222222-2222-4222-8222-222222222222';
const termId = '33333333-3333-4333-8333-333333333333';
const wordId = '44444444-4444-4444-8444-444444444444';
const domainId = '55555555-5555-4555-8555-555555555555';
const datasetId = '66666666-6666-4666-8666-666666666666';
const exportId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const dictionary = { id: dictId, code: 'fixture', name: '격리 시험 기관사전', scope: 'ORGANIZATION', organizationId: org, versionLabel: 'fixture-v2', sourceUri: null, sourceChecksum: null };
const entry = (id, kind, name) => ({ id, dictionaryId: dictId, kind, code: `fixture_${kind}`, name, definition: '격리 화면 검수용 데이터', dataType: 'NUMBER', state: 'APPROVED', lockVersion: 1, createdBy: org, updatedBy: org, submittedBy: null, decidedBy: null, decisionReason: null });
const term = entry(termId, 'TERM', '기온'); const word = entry(wordId, 'WORD', '기온 단어'); const domain = entry(domainId, 'DOMAIN', '기온 도메인');
const draft = { id: datasetId, slug: 'fixture', ownerOrganizationId: org, revisionId, revisionNo: 1, state: 'DRAFT', title: '격리 검증 데이터셋', description: '화면 시험', providerOrganizationId: org, primaryCategoryId: null, visibility: 'PRIVATE', lockVersion: 1, currentPublicationId: null, reviewRequestId: null, reviewDecisionId: null };
const field = { fieldPath: 'temperature', physicalName: 'temperature', displayName: '기온', description: '기온 값', dataType: 'NUMBER', nullable: true, maxLength: null, unit: null, ordinal: 0, publicVisible: true, approvedTermId: null, mappingEvidence: null };
const candidate = { id: termId, dictionaryId: dictId, dictionaryName: dictionary.name, versionLabel: dictionary.versionLabel, scope: 'ORGANIZATION', code: 'temperature', name: '기온', definition: '격리 검증 정의', dataType: 'NUMBER', physicalName: 'temperature', evidenceCode: 'DISPLAY_NAME_EXACT', evidence: "저장된 필드 표시명 '기온'과 승인 용어명 '기온'이 앞뒤 공백과 대소문자를 제외하고 일치합니다." };
const unexpected = [], errors = [], reads = [], writes = [];
let missingSavedField = false, failOptions = false, connected = true, failUnlink = true, draftState = 'DRAFT', lock = 1;
let currentFields = [{ ...field, approvedTermId: termId, mappingEvidence: '기존 연결 근거' }, { ...field, fieldPath: 'other', physicalName: 'other', displayName: '다른 필드', ordinal: 1, approvedTermId: termId, mappingEvidence: '보존할 근거' }];
const browser = await chromium.launch({ headless: true }); let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/admin/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname.replace('/api/admin/v1', '');
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', headers: { ETag: `"dataset:${datasetId}:${lock}"` }, body: JSON.stringify(body) });
    if (request.method() === 'GET') reads.push(url); else writes.push({ path, method: request.method(), body: request.postDataJSON(), headers: request.headers() });
    if (path === '/session') return json({ userId: org, organizationIds: [org], authorities: ['ROLE_ORG_EDITOR'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'fixture-token' });
    if (path === '/metadata-options' && failOptions) return json({}, 503);
    if (path === '/metadata-options') return json({ organizations: [{ id: org, name: '검증 기관' }], categories: [] });
    if (path === '/dictionaries') return json([dictionary]);
    if (path === `/dictionaries/${dictId}/entries`) return json(url.searchParams.get('q') === '찾을수없음' ? [] : [term, { ...term, id: wordId, name: '별도 용어' }]);
    if (path === `/dictionary-entries/${termId}`) return json(term);
    if (path === `/dictionary-entries/${wordId}`) return json(word);
    if (path === `/dictionary-entries/${domainId}`) return json(domain);
    if (path === `/dictionary-entries/${termId}/relationships`) return json({ entryId: termId, kind: 'TERM', lockVersion: 1, word: null, domain: null, term: { physicalName: 'temperature', domainEntryId: domainId, wordEntryIds: [wordId] } });
    if (path === `/dictionary-entries/${domainId}/relationships`) return json({ entryId: domainId, kind: 'DOMAIN', lockVersion: 1, word: null, domain: { precision: 9, scale: 2, allowedValues: [] }, term: null });
    if (path === `/dictionary-entries/${termId}/field-usages`) { assert.equal(url.searchParams.get('organizationId'), org); return json({ items: connected ? [{ datasetId, revisionId, revisionNo: 2, title: draft.title, state: 'DRAFT', visibility: 'PRIVATE', fieldPath: field.fieldPath, physicalName: field.physicalName, displayName: field.displayName, dataType: field.dataType, primaryCategoryName: '기상', providerOrganizationName: '검증 제공기관', revisionUpdatedAt: '2026-10-16T10:00:00Z', latestPublication: { id: exportId, publicationNo: 1, state: 'PUBLISHED', revisionId: org, current: true, matchesManagementRevision: false } }] : [], page: 0, size: 20, hasNext: false, revisionScope: 'LATEST_MANAGEMENT_REVISION' }); }
    if (path === `/dictionary-versions/${dictId}/exports`) return json({ id: exportId, dictionaryId: dictId, kind: 'TERM', status: 'SUCCEEDED', entryCount: 1, createdAt: '2026-10-16T09:00:00Z', expiresAt: '2026-10-17T09:00:00Z', contentHash: 'fixture', fileName: 'selected-fixture.xlsx' }, 202);
    if (path === `/dictionary-exports/${exportId}/file`) return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('UI transport fixture only') });
    if (path === '/datasets') return json({ items: [draft], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } });
    if (path === `/datasets/${datasetId}/draft`) return json({ ...draft, state: draftState, lockVersion: lock });
    if (path === `/datasets/${datasetId}/draft/fields` && request.method() === 'PUT') {
      assert.equal(request.headers()['if-match'], `"dataset:${datasetId}:${lock}"`);
      const body = request.postDataJSON(); assert.equal(body.fields[0].approvedTermId, null); assert.equal(body.fields[0].mappingEvidence, null); assert.deepEqual(body.fields[1], currentFields[1]);
      if (failUnlink) { failUnlink = false; lock++; return json({}, 412); }
      currentFields = body.fields; connected = false; lock++; return json({ revisionId, lockVersion: lock, fields: currentFields });
    }
    if (path === `/datasets/${datasetId}/draft/fields`) return json({ revisionId, lockVersion: lock, fields: currentFields });
    if (path === '/dictionary-terms') { assert.equal(url.searchParams.get('organizationId'), org); assert.equal(url.searchParams.get('dataType'), 'NUMBER'); return json({ items: [{ ...candidate, evidenceCode: 'SEARCH_SELECTED', evidence: '승인 용어 검색에서 사용자가 의미와 자료형을 확인하여 선택합니다.' }], page: 0, size: 20, hasNext: false, basis: null }); }
    if (path === `/datasets/${datasetId}/draft/term-suggestions`) return missingSavedField ? json({}, 404) : json({ items: [candidate], page: 0, size: 20, hasNext: false, basis: { revisionId, lockVersion: 1, fieldPath: field.fieldPath, displayName: field.displayName, physicalName: field.physicalName, dataType: field.dataType } });
    unexpected.push(`${request.method()} ${path}`); return json({}, 500);
  });
  page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/standards'); await page.getByRole('button', { name: '우리 기관 사전', exact: true }).click();
  await page.getByLabel('사전 전체 검색').fill('기온'); await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page.getByRole('button', { name: '기온', exact: true })).toBeVisible(); assert.ok(reads.some(url => url.pathname.endsWith('/entries') && url.searchParams.get('q') === '기온'));
  await page.getByLabel('기온 선택', { exact: true }).check();
  assert.equal(await page.getByRole('checkbox', { name: '현재 페이지 전체 선택' }).evaluate(el => el.indeterminate), true);
  await auditAccessibility(page, 'standards-selected-search'); await page.screenshot({ path: `${output}/standards-selected-search.png` });
  const download = page.waitForEvent('download'); await page.getByRole('button', { name: '선택 항목 XLSX', exact: true }).click(); await download;
  assert.deepEqual(writes[0].body.entryIds, [termId]); assert.equal(writes[0].body.query, '기온'); assert.ok(writes[0].headers['idempotency-key']);
  await page.getByLabel('사전 전체 검색').fill('찾을수없음'); await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page.getByRole('button', { name: '선택 항목 XLSX', exact: true })).toBeDisabled(); await expect(page.getByText('이 페이지에 조건과 일치하는 항목이 없습니다.')).toBeVisible();
  await page.getByRole('button', { name: '검색 초기화', exact: true }).click(); await page.getByRole('button', { name: '기온', exact: true }).click();
  let modal = page.getByRole('dialog', { name: '표준사전 항목 상세', exact: true }); failOptions = true;
  await modal.getByRole('tab', { name: '기본 정보', exact: true }).focus(); await page.keyboard.press('ArrowRight');
  await expect(modal.getByRole('tab', { name: '연결 데이터셋', exact: true })).toBeFocused(); await expect(modal.getByRole('alert')).toBeVisible();
  failOptions = false; await modal.getByRole('button', { name: '연결 목록 다시 조회' }).click();
  await expect(modal).toContainText(draft.title); await expect(modal).toContainText('비공개'); await expect(modal).toContainText('최신 관리 버전');
  await expect(modal).toContainText('검증 제공기관'); await expect(modal).toContainText('기상'); await expect(modal).toContainText('게시 중'); await expect(modal).toContainText('별도 관리 버전');
  await auditAccessibility(page, 'standards-field-usages'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-field-usages.png` });
  await page.setViewportSize({ width: 390, height: 844 }); await modal.getByRole('region', { name: '연결 필드 표 가로 스크롤' }).focus(); await page.keyboard.press('ArrowRight');
  assert.ok(await modal.evaluate(el => el.scrollWidth <= el.clientWidth)); await auditAccessibility(page, 'standards-field-usages-mobile'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-field-usages-mobile.png` });
  await page.setViewportSize({ width: 1440, height: 1080 });
  const unlinkButton = modal.getByRole('button', { name: `${draft.title} 기온 연결 해제`, exact: true });
  draftState = 'APPROVED'; await unlinkButton.click(); let confirm = page.getByRole('dialog', { name: '용어 연결 해제', exact: true }); await expect(confirm.getByRole('alert')).toContainText('편집 가능한 초안이 아닙니다'); await expect(confirm.getByRole('button', { name: '초안 연결 해제' })).toBeDisabled(); await confirm.getByRole('button', { name: '취소' }).click(); await expect(unlinkButton).toBeFocused(); assert.equal(writes.length, 1);
  draftState = 'DRAFT'; await unlinkButton.click(); await expect(confirm.getByRole('button', { name: '초안 연결 해제' })).toBeEnabled(); await auditAccessibility(page, 'standards-unlink-confirm'); await expect(confirm).toBeVisible(); await page.screenshot({ path: `${output}/standards-unlink-confirm.png` });
  await confirm.getByRole('button', { name: '초안 연결 해제' }).click(); await expect(confirm.getByRole('alert')).toContainText('다른 담당자가 내용을 변경'); await expect(confirm.getByRole('button', { name: '초안 연결 해제' })).toBeDisabled();
  await confirm.getByRole('button', { name: '최신 상태 다시 확인' }).click(); await expect(confirm.getByRole('button', { name: '초안 연결 해제' })).toBeEnabled(); await confirm.getByRole('button', { name: '초안 연결 해제' }).click(); await expect(confirm).toHaveCount(0); await expect(modal).toContainText('연결을 해제했습니다'); await expect(modal).toContainText('연결된 필드가 없습니다'); assert.equal(writes.length, 3);
  await page.keyboard.press('Home'); await modal.getByRole('tab', { name: '연결 데이터셋' }).focus(); await page.keyboard.press('Home'); await expect(modal.getByRole('tab', { name: '기본 정보' })).toBeFocused(); await page.keyboard.press('End'); await expect(modal.getByRole('tab', { name: '연결 데이터셋' })).toBeFocused(); await page.keyboard.press('Escape');
  await page.goto(origin + '/metadata'); await page.getByRole('button', { name: draft.title, exact: true }).click();
  const editor = page.getByRole('dialog', { name: '메타데이터 상세', exact: true }); await editor.getByRole('tab', { name: /^필드정보/ }).click();
  await editor.getByRole('group', { name: '필드 1', exact: true }).getByRole('textbox', { name: /^표시이름/ }).fill('미저장 새 이름'); await editor.getByRole('button', { name: '필드 1 표준용어 연결', exact: true }).click();
  modal = page.getByRole('dialog', { name: '데이터셋 필드 연결', exact: true }); await modal.getByRole('tab', { name: '추천 연결', exact: true }).click();
  await expect(modal).toContainText('현재 입력과 저장 기준이 다릅니다'); await expect(modal).toContainText(candidate.evidence);
  await modal.getByRole('button', { name: '선택', exact: true }).click(); await expect(modal.getByRole('textbox', { name: /^연결 근거/ })).toHaveValue(candidate.evidence);
  await auditAccessibility(page, 'standards-term-suggestions'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-term-suggestions.png` }); await modal.getByRole('button', { name: '연결 적용', exact: true }).click();
  await expect(modal).toHaveCount(0); await expect(editor.getByRole('group', { name: '필드 1', exact: true }).getByRole('textbox', { name: /^표시이름/ })).toHaveValue('미저장 새 이름'); assert.equal(writes.length, 3, 'suggestion and apply must not write metadata');
  await editor.getByRole('button', { name: '필드 1 표준용어 연결', exact: true }).click(); modal = page.getByRole('dialog', { name: '데이터셋 필드 연결', exact: true });
  missingSavedField = true; await modal.getByRole('tab', { name: '추천 연결', exact: true }).click(); await expect(modal.getByRole('alert')).toContainText('저장되지 않은 필드');
  await modal.getByRole('tab', { name: '기본 연결', exact: true }).click(); await modal.getByLabel('승인 용어 검색').fill('temperature'); await modal.getByRole('button', { name: '용어 검색', exact: true }).click();
  await expect(modal.getByRole('button', { name: '선택', exact: true })).toBeVisible(); assert.ok(reads.some(url => url.pathname.endsWith('/dictionary-terms') && url.searchParams.get('q') === 'temperature'));
  await page.setViewportSize({ width: 390, height: 844 }); await page.keyboard.press('Tab');
  assert.equal(await modal.evaluate(element => element.contains(document.activeElement)), true); assert.ok(await modal.evaluate(element => element.scrollWidth <= element.clientWidth));
  await auditAccessibility(page, 'standards-term-search-mobile'); await expect(modal).toBeVisible(); await page.screenshot({ path: `${output}/standards-term-search-mobile.png` });
  assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
  console.log('Standards completion fixtures: server search/empty/reset, selected XLSX ids, scoped latest field usages, exact recommendation/unsaved preservation/no autosave, missing-field search fallback/mobile: 8 passed (including tab/partial selection semantics, provider/category/publication distinction, unlink conflict/immutability/full-field preservation). UI fixtures only.');
} catch (error) { if (page) await page.screenshot({ path: `${output}/standards-completion-failure.png` }); console.error({ errors, unexpected }); throw error; }
finally { await browser.close(); }
