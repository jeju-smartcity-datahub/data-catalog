import { test, expect } from '@playwright/test';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

// Isolated rendering contracts only. These routes never create a runtime login or seed the DB.
const organizationId = '10000000-0000-4000-8000-000000000001';
const run = { id: '20000000-0000-4000-8000-000000000001', organizationId, datasetRevisionId: null, kind: 'DEFINITION_IMPORT', engineVersion: 'json-definition-v1', requestedBy: 'ui-fixture-user', startedAt: '2026-10-16T03:00:00Z', finishedAt: '2026-10-16T03:00:01Z', status: 'SUCCEEDED', errorCode: null, fieldCount: 1, sourceHash: 'a'.repeat(64) };
const failureRun = { ...run, id: '20000000-0000-4000-8000-000000000002', startedAt: '2026-10-16T04:00:00Z', status: 'FAILED', errorCode: 'DEFINITION_PARSE_FAILED', fieldCount: 0 };
const report = { id: '40000000-0000-4000-8000-000000000001', kind: 'SEARCH', format: 'CSV', organizationId: null, status: 'SUCCEEDED', rowCount: 1, cutoff: '2026-10-16T10:00:00Z', createdAt: '2026-10-16T10:00:00Z', expiresAt: '2026-10-17T10:00:00Z', contentHash: 'b'.repeat(64), fileName: 'search-report.csv', filters: { from: '2026-10-15T15:00:00Z', to: '2026-10-16T15:00:00Z' } };
test.describe('Isolated operations UI contracts — not authenticated SSO tests', () => {
  test.beforeEach(async ({ page }) => {
    test.setTimeout(60000);
    await page.route('**/api/admin/v1/**', async route => {
      const path = new URL(route.request().url()).pathname;
      let body: unknown;
      if (path.endsWith('/session')) body = { userId: '30000000-0000-4000-8000-000000000001', organizationIds: [organizationId], authorities: ['ROLE_PLATFORM_ADMIN'], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-ui-fixture' };
      else if (path.endsWith('/audit-events')) body = { items: [{ id: 'audit-1', occurredAt: '2026-10-16T03:00:00Z', actorUserId: 'ui-fixture-user', action: 'DRAFT_UPDATED', targetType: 'DATASET', targetId: run.id, requestId: 'isolated-fixture-request', result: 'SUCCESS' }], number: 0, size: 20, totalElements: 1, totalPages: 1 };
      else if (path.endsWith('/search-statistics')) body = { searchCount: 12, zeroResultCount: 3, resultCountSum: 27, durationMsSum: 360 };
      else if (path.endsWith('/analysis-statistics')) body = { runCount: 2, succeededCount: 1, failedCount: 1, fieldCountSum: 1, evidenceCount: 1 };
      else if (path.endsWith('/search-history')) body = [{ id: 'search-1', requestId: 'isolated-fixture-search', occurredAt: '2026-10-16T03:00:00Z', hasQuery: true, normalizedFilters: { organizationId: [organizationId] }, resultCount: 2, durationMs: 30, searchScope: 'PUBLIC' }];
      else if (path.endsWith('/metadata-options')) body = { organizations: [{ id: organizationId, name: '화면 검증 기관' }], categories: [], topics: [], regions: [], hubs: [] };
      else if (path.endsWith('/analysis-runs')) body = [run, failureRun];
      else if (path.endsWith(`${failureRun.id}/evidence`)) body = [];
      else if (path.endsWith('/analytics/reports')) return route.fulfill({ status: 201, json: { ...report, ...route.request().postDataJSON() } });
      else if (path.endsWith('/file')) return route.fulfill({ status: 200, contentType: 'text/csv', body: 'id,resultCount\nsearch-1,2\n' });
      else if (path.endsWith('/evidence')) body = [{ fieldPath: 'capacity', sourcePointer: '/fields/0', evidenceKind: 'EXPLICIT_JSON', explanation: '원본에 명시된 필드', sourceHash: run.sourceHash, field: { fieldPath: 'capacity', physicalName: 'capacity', displayName: '수용량', description: '화면 검증용 필드', dataType: 'INTEGER', nullable: false, maxLength: null, unit: null, ordinal: 0, publicVisible: true, approvedTermId: null, mappingEvidence: null } }];
      else return route.fulfill({ status: 404, json: { title: 'Unconfigured isolated fixture' } });
      return route.fulfill({ status: 200, json: body });
    });
  });
  test('audit list and server-provided current account scope render', async ({ page }) => {
    await page.goto('http://127.0.0.1:3001/access');
    await expect(page.getByText('DRAFT_UPDATED', { exact: true })).toBeVisible();
    await expect(page.getByText('총 1건', { exact: true })).toBeVisible();
    await auditAccessibility(page, 'operations-audit');
    await page.screenshot({ path: '../.runtime/verification/audit-fixture-desktop.png', fullPage: true });
    await page.getByRole('button', { name: '현재 계정 권한' }).click();
    await expect(page.getByText('ROLE_PLATFORM_ADMIN', { exact: true })).toBeVisible();
    await expect(page.getByText(organizationId, { exact: true })).toBeVisible();
    await auditAccessibility(page, 'operations-current-account');
  });
  test('statistics use API numbers and evidence dialog supports keyboard dismissal', async ({ page }) => {
    await page.goto('http://127.0.0.1:3001/analytics');
    await expect(page.getByLabel('검색 통계')).toContainText('12건');
    await expect(page.getByLabel('검색 통계')).toContainText('3건');
    await expect(page.getByLabel('검색 통계')).toContainText('25.0%');
    await auditAccessibility(page, 'operations-search');
    await page.screenshot({ path: '../.runtime/verification/search-fixture-desktop.png', fullPage: true });
    await page.getByRole('button', { name: /검색 이력 상세/ }).click();
    await expect(page.getByRole('dialog', { name: '검색 이력 상세' })).toBeVisible();
    await expect(page.getByRole('dialog')).toContainText('2건');
    await expect(page.getByRole('dialog')).toContainText(organizationId);
    await auditAccessibility(page, 'operations-search-detail');
    await page.screenshot({ path: '../.runtime/verification/search-detail-fixture.png' });
    await page.keyboard.press('Escape');
    await page.getByRole('tab', { name: '메타데이터 분석 이력' }).click();
    await expect(page.getByLabel('분석 통계')).toContainText('2건');
    await expect(page.getByText('정의서 형식 확인 필요')).toBeVisible();
    await auditAccessibility(page, 'operations-analysis');
    const evidence = page.getByRole('button', { name: /확인 근거/ }).first();
    await evidence.click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('/fields/0', { exact: true })).toBeVisible();
    await auditAccessibility(page, 'operations-analysis-evidence');
    await page.screenshot({ path: '../.runtime/verification/analysis-evidence-fixture.png' });
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).not.toBeVisible();
    await expect(evidence).toBeFocused();
    await page.getByRole('button', { name: /확인 근거/ }).last().click();
    await expect(page.getByRole('dialog')).toContainText('DEFINITION_PARSE_FAILED');
    await expect(page.getByRole('dialog')).toContainText('저장된 상세 근거가 없습니다');
    await auditAccessibility(page, 'operations-analysis-failure');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: /확인 근거/ }).last()).toBeFocused();
    await page.setViewportSize({ width: 320, height: 844 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('tab', { name: '메타데이터 분석 이력' }).focus();
    await page.screenshot({ path: '../.runtime/verification/analysis-fixture-mobile.png', fullPage: true });
    await auditAccessibility(page, 'operations-analysis-mobile');
  });

  test('report retries retain idempotency and filters, then download the actual response', async ({ page }) => {
    const requests: { key?: string; body: Record<string, unknown> }[] = [];
    await page.route('**/api/admin/v1/analytics/reports', async route => {
      requests.push({ key: route.request().headers()['idempotency-key'], body: route.request().postDataJSON() });
      if (requests.length === 1) return route.fulfill({ status: 503, json: { title: 'isolated unavailable' } });
      return route.fulfill({ status: 201, json: { ...report, filters: requests.at(-1)!.body } });
    });
    await page.goto('http://127.0.0.1:3001/analytics');
    await page.getByRole('combobox', { name: '검색어 포함', exact: true }).selectOption('true');
    await page.getByRole('combobox', { name: '검색 결과', exact: true }).selectOption('true');
    const filtered = page.waitForRequest(request => request.url().includes('/search-history?') && request.url().includes('zeroResults=true'));
    await page.getByRole('button', { name: '조회', exact: true }).click(); await filtered;
    await page.getByRole('button', { name: 'CSV 보고서' }).click();
    await expect(page.getByRole('alert')).toContainText('같은 요청으로 재시도');
    await auditAccessibility(page, 'operations-report-error');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'CSV 보고서' }).click();
    expect((await download).suggestedFilename()).toBe('search-report.csv');
    expect(requests).toHaveLength(2);
    expect(requests[0].key).toBeTruthy(); expect(requests[1].key).toBe(requests[0].key);
    expect(requests[1].body).toMatchObject({ kind: 'SEARCH', format: 'CSV', hasQuery: true, zeroResults: true });
    await page.getByText('생성된 보고서', { exact: false }).click();
    await expect(page.getByText(report.contentHash, { exact: true })).toBeVisible();
    await auditAccessibility(page, 'operations-report-created');
    await page.screenshot({ path: '../.runtime/verification/analytics-report-fixture.png', fullPage: true });
    const refreshedDownload = page.waitForEvent('download');
    await page.getByRole('button', { name: 'CSV 보고서' }).click(); await refreshedDownload;
    expect(requests).toHaveLength(3); expect(requests[2].key).not.toBe(requests[1].key);
  });

  test('period validation, identifier validation and analysis filters match the request', async ({ page }) => {
    await page.goto('http://127.0.0.1:3001/analytics');
    await page.getByLabel('시작일', { exact: true }).fill('2026-01-01');
    await page.getByLabel('종료일', { exact: true }).fill('2026-02-15');
    await page.getByRole('button', { name: '조회', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('31일 이하');
    await page.getByRole('tab', { name: '검색 이력·통계' }).focus(); await page.keyboard.press('ArrowLeft');
    await expect(page.getByRole('tab', { name: '메타데이터 분석 이력' })).toBeFocused();
    await page.getByLabel('처리 식별자', { exact: true }).fill('비공개 검색어');
    expect(await page.getByLabel('처리 식별자', { exact: true }).evaluate((element: HTMLInputElement) => element.checkValidity())).toBe(false);
    await page.getByLabel('처리 식별자', { exact: true }).fill('json-definition-v1');
    await page.getByRole('combobox', { name: '처리 종류', exact: true }).selectOption('DEFINITION_IMPORT');
    await page.getByRole('combobox', { name: '처리 상태', exact: true }).selectOption('FAILED');
    const filtered = page.waitForRequest(request => request.url().includes('/analysis-runs?') && request.url().includes('status=FAILED'));
    await page.getByRole('button', { name: '조회', exact: true }).click();
    const url = new URL((await filtered).url());
    expect(url.searchParams.get('query')).toBe('json-definition-v1');
    expect(url.searchParams.get('kind')).toBe('DEFINITION_IMPORT');
    expect(url.searchParams.get('organizationId')).toBe(organizationId);
  });

  test('empty and failed requests expose recoverable states, mobile menu remains keyboard operable', async ({ page }) => {
    let fail = true;
    await page.route('**/api/admin/v1/search-history?**', route => fail ? route.fulfill({ status: 503, json: {} }) : route.fulfill({ status: 200, json: [] }));
    await page.goto('http://127.0.0.1:3001/analytics');
    await expect(page.getByRole('alert')).toBeVisible();
    fail = false;
    await page.getByRole('button', { name: '다시 시도', exact: true }).click();
    await expect(page.getByText('선택한 조건에 검색 이력이 없습니다.')).toBeVisible();
    await auditAccessibility(page, 'operations-search-empty');
    await page.setViewportSize({ width: 320, height: 844 });
    const menu = page.getByRole('button', { name: /메뉴/ });
    await menu.focus(); await page.keyboard.press('Enter');
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('navigation', { name: '관리 메뉴' })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await auditAccessibility(page, 'operations-mobile-menu');
  });

  test('report limits explain how to narrow the request and no data is downloaded', async ({ page }) => {
    await page.route('**/api/admin/v1/analytics/reports', route => route.fulfill({ status: 422, json: { code: 'REPORT_LIMIT_EXCEEDED' } }));
    await page.goto('http://127.0.0.1:3001/analytics');
    await page.getByRole('button', { name: 'JSON 보고서' }).click();
    await expect(page.getByRole('alert')).toContainText('최대 5,000행 또는 8MiB');
    await expect(page.getByRole('alert')).toContainText('조건을 좁혀');
    await expect(page.getByRole('alert')).not.toContainText('같은 요청으로 재시도');
    await auditAccessibility(page, 'operations-report-limit');
  });

  test('field mapping evidence shows the recorded term and rationale with semantic headers', async ({ page }) => {
    await page.route('**/api/admin/v1/analysis-runs?**', route => route.fulfill({ status: 200, json: [{ ...run, kind: 'FIELD_MAPPING' }] }));
    await page.route('**/api/admin/v1/analysis-runs/*/evidence', route => route.fulfill({ status: 200, json: [{ fieldPath: 'capacity', sourcePointer: '/fields/0', explanation: '선택된 승인 용어의 연결 근거', field: { physicalName: 'capacity', displayName: '수용량', dataType: 'INTEGER', approvedTermId: '50000000-0000-4000-8000-000000000001', mappingEvidence: '담당자가 승인된 수용량 용어를 선택함' } }] }));
    await page.goto('http://127.0.0.1:3001/analytics');
    await page.getByRole('tab', { name: '메타데이터 분석 이력' }).click();
    await page.getByRole('button', { name: /확인 근거/ }).click();
    await expect(page.getByRole('dialog')).toContainText('담당자가 승인된 수용량 용어를 선택함');
    await expect(page.getByRole('dialog').getByRole('columnheader')).toHaveCount(4);
    await expect(page.getByRole('dialog').getByRole('columnheader', { name: '승인 용어 식별자' })).toBeVisible();
    await auditAccessibility(page, 'operations-mapping-evidence');
    await page.screenshot({ path: '../.runtime/verification/analysis-mapping-fixture.png' });
  });
});
