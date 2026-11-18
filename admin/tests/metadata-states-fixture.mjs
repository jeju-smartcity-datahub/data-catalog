import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

// Presentation and outgoing-request checks against isolated fixture responses.
// This is not proof of actual authentication, server authorization or persistence.
const output = fileURLToPath(new URL('../../.runtime/admin-ui-fixtures/', import.meta.url)); await mkdir(output, { recursive: true });
const org = '11111111-1111-4111-8111-111111111111'; const id = '22222222-2222-4222-8222-222222222222';
const author = '33333333-3333-4333-8333-333333333333';
const reviewId = '55555555-5555-4555-8555-555555555555'; const publicationId = '66666666-6666-4666-8666-666666666666';
const decisionId = '77777777-7777-4777-8777-777777777777'; const revisionId = '88888888-8888-4888-8888-888888888888';
const draft = { id, slug: 'state-fixture', ownerOrganizationId: org, revisionId, revisionNo: 1, state: 'DRAFT', title: '상태 전이 검증', description: '격리된 UI 상태 전이 시험', providerOrganizationId: org, primaryCategoryId: null, visibility: 'PUBLIC', lockVersion: 1, currentPublicationId: null, reviewRequestId: null, reviewDecisionId: null };
const review = { id: reviewId, datasetId: id, revisionId, contentHash: 'test-hash', status: 'SUBMITTED', submittedBy: author, submittedAt: '2026-10-16T10:00:00Z', lockVersion: 1, decisionId: null, decision: null, reason: null };
const publication = { id: publicationId, datasetId: id, revisionId, publicationNo: 1, state: 'PUBLISHED', accessState: 'METADATA_ONLY', publishedAt: '2026-10-16T10:00:00Z', withdrawnAt: null, lockVersion: 1, withdrawalReason: null };
let roles = ['ROLE_ORG_EDITOR']; let userId = author;
const writes = []; const unexpected = []; const errors = [];
const browser = await chromium.launch({ headless: true }); let page;
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/api/admin/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname.replace('/api/admin/v1', ''); const method = request.method();
    const json = (body, etag, status = 200) => route.fulfill({ status, contentType: 'application/json', headers: etag ? { ETag: etag } : {}, body: JSON.stringify(body) });
    if (method !== 'GET') writes.push({ path, headers: request.headers(), body: request.postData() ? request.postDataJSON() : null });
    if (path === '/session') return json({ userId, organizationIds: [org], authorities: roles, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'isolated-state-token' });
    if (path === '/metadata-options') return json({ organizations: [{ id: org, name: '검증 기관' }], categories: [] });
    if (path === '/datasets') return json({ items: [draft], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } });
    if (path === `/datasets/${id}/draft`) return json(draft, `"dataset:${id}:${draft.lockVersion}"`);
    if (path === `/datasets/${id}/draft/fields`) return json({ revisionId, lockVersion: draft.lockVersion, fields: [] }, `"dataset:${id}:${draft.lockVersion}"`);
    if (path === `/review-requests/${reviewId}`) return json(review, `"review:${reviewId}:${review.lockVersion}"`);
    if (path === `/datasets/${id}/review-requests`) { draft.state = 'IN_REVIEW'; draft.reviewRequestId = reviewId; draft.lockVersion++; review.status = 'SUBMITTED'; return json(review, `"review:${reviewId}:${review.lockVersion}"`, 201); }
    if (path === `/review-requests/${reviewId}/withdrawal`) { review.status = 'WITHDRAWN'; review.lockVersion++; draft.state = 'REJECTED'; draft.lockVersion++; return json(review, `"review:${reviewId}:${review.lockVersion}"`); }
    if (path === `/datasets/${id}/drafts`) { draft.state = 'DRAFT'; draft.revisionNo++; draft.lockVersion++; draft.reviewRequestId = null; return json(draft, `"dataset:${id}:${draft.lockVersion}"`); }
    if (path === `/review-requests/${reviewId}/decisions`) { review.status = 'APPROVED'; review.decision = 'APPROVE'; review.decisionId = decisionId; review.lockVersion++; draft.state = 'APPROVED'; draft.reviewDecisionId = decisionId; draft.lockVersion++; return json(review, `"review:${reviewId}:${review.lockVersion}"`); }
    if (path === `/datasets/${id}/publications`) { draft.currentPublicationId = publicationId; draft.lockVersion++; return json(publication, `"publication:${publicationId}:1"`, 201); }
    if (path === `/publications/${publicationId}`) return json(publication, `"publication:${publicationId}:${publication.lockVersion}"`);
    if (path === `/publications/${publicationId}/withdrawal`) { publication.state = 'WITHDRAWN'; publication.lockVersion++; draft.currentPublicationId = null; draft.lockVersion++; return json(publication, `"publication:${publicationId}:${publication.lockVersion}"`); }
    unexpected.push(`${method} ${path}`); return json({ error: 'Unexpected fixture request' }, undefined, 500);
  });
  page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  const openDetail = async () => { await page.getByRole('button', { name: draft.title, exact: true }).click(); return page.getByRole('dialog', { name: '메타데이터 상세', exact: true }); };
  const action = async (label, reason) => { const modal = page.getByRole('dialog', { name: label, exact: true }); if (reason) await modal.getByRole('textbox', { name: /^사유/ }).fill(reason); await modal.getByRole('button', { name: label, exact: true }).click(); await expect(modal).toHaveCount(0); };
  await page.goto(`${process.env.ADMIN_UI_ORIGIN || 'http://127.0.0.1:3001'}/metadata`); let detail = await openDetail();
  await expect(detail.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toBeEnabled();
  await auditAccessibility(page, 'metadata-state-draft');
  await page.screenshot({ path: `${output}/metadata-state-draft.png` });
  await detail.getByRole('button', { name: '검토 요청', exact: true }).click(); await action('검토 요청');
  detail = await openDetail(); await expect(detail.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toHaveAttribute('readonly', '');
  await expect(detail.getByRole('button', { name: '변경 저장', exact: true })).toHaveCount(0);
  await auditAccessibility(page, 'metadata-state-review');
  await page.screenshot({ path: `${output}/metadata-state-review.png` });
  await detail.getByRole('button', { name: '검토 요청 철회', exact: true }).click();
  const withdrawal = page.getByRole('dialog', { name: '검토 요청 철회', exact: true }); await expect(withdrawal.getByRole('button', { name: '검토 요청 철회', exact: true })).toBeDisabled();
  await action('검토 요청 철회', '필드 보완 필요');
  const request = writes.find(item => item.path.endsWith(`/review-requests/${reviewId}/withdrawal`)); assert.equal(request.headers['if-match'], `"review:${reviewId}:1"`); assert.equal(request.body.reason, '필드 보완 필요'); assert.ok(request.headers['idempotency-key']);
  detail = await openDetail(); await expect(detail).toContainText('검토 철회'); await expect(detail.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toHaveAttribute('readonly', '');
  await detail.getByRole('button', { name: '새 수정 초안 만들기', exact: true }).click(); await action('새 수정 초안 만들기');
  detail = await openDetail(); await expect(detail.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toBeEnabled();
  await detail.getByRole('button', { name: '검토 요청', exact: true }).click(); await action('검토 요청');
  roles = ['ROLE_ORG_REVIEWER']; await page.reload(); detail = await openDetail(); await expect(detail.getByRole('button', { name: '검토 승인', exact: true })).toBeEnabled();
  await detail.getByRole('button', { name: '검토 승인', exact: true }).click(); await action('검토 승인');
  roles = ['ROLE_ORG_PUBLISHER']; await page.reload(); detail = await openDetail(); await expect(detail.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toHaveAttribute('readonly', '');
  await detail.getByRole('button', { name: '메타데이터 게시', exact: true }).click(); await action('메타데이터 게시');
  assert.deepEqual(writes.find(item => item.path.endsWith('/publications')).body, { revisionId, reviewDecisionId: decisionId });
  detail = await openDetail(); await detail.getByRole('button', { name: '게시 철회', exact: true }).click(); await action('게시 철회', '공개 중단 검토');
  assert.equal(writes.find(item => item.path === `/publications/${publicationId}/withdrawal`).headers['if-match'], `"publication:${publicationId}:1"`);
  assert.ok(writes.every(item => item.headers['x-csrf-token'] === 'isolated-state-token' && item.headers['idempotency-key']));
  assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
  console.log('Isolated metadata states: draft, submit freeze, review withdrawal reason/ETag, revision copy, self-review UI, approval, METADATA_ONLY publication, publication withdrawal: 8 passed. No actual authenticated workflow tested.');
} catch (error) { if (page) await page.screenshot({ path: `${output}/metadata-states-failure.png` }); console.error('Fixture browser errors:', errors); console.error('Unexpected fixture requests:', unexpected); throw error; }
finally { await browser.close(); }
