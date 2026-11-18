import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createServer } from '../node_modules/vite/dist/node/index.js';
import { chromium, expect } from '../../web/node_modules/@playwright/test/index.mjs';

// UI contract fixtures only. Server authorization and persistence are tested separately.
const adminRoot = fileURLToPath(new URL('../', import.meta.url));
const output = fileURLToPath(new URL('../test-results/self-approval/', import.meta.url));
await mkdir(output, { recursive: true });
const server = await createServer({ root: adminRoot, base: '/', server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
await server.listen();
const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
const browser = await chromium.launch({ headless: true });
const org = '11111111-1111-4111-8111-111111111111';
const user = '22222222-2222-4222-8222-222222222222';
const dataset = '33333333-3333-4333-8333-333333333333';
const revision = '44444444-4444-4444-8444-444444444444';
const reviewId = '55555555-5555-4555-8555-555555555555';
const dict = '66666666-6666-4666-8666-666666666666';
const word = '77777777-7777-4777-8777-777777777777';
const options = { organizations: [{ id: org, name: '검증 기관' }], categories: [], topics: [], regions: [], hubs: [] };
const baseDraft = { id: dataset, slug: 'self-approval-ui', ownerOrganizationId: org, revisionId: revision, revisionNo: 1, state: 'IN_REVIEW', title: '본인 제출 메타데이터', description: '화면 계약 검증', providerOrganizationId: org, primaryCategoryId: null, visibility: 'PUBLIC', lockVersion: 1, currentPublicationId: null, reviewRequestId: reviewId, reviewDecisionId: null, updatedAt: '2026-10-28T08:00:00Z', details: { department: '담당부서', license: 'CC BY', keywords: ['검증'] } };
const review = { id: reviewId, datasetId: dataset, revisionId: revision, status: 'SUBMITTED', contentHash: 'fixture-content-hash', submittedBy: user, submittedAt: '2026-10-28T08:00:00Z', lockVersion: 1 };
const dictionary = { id: dict, scope: 'ORGANIZATION', organizationId: org, code: 'fixture', name: '검증 사전', versionLabel: '1', sourceUri: null, sourceChecksum: null };
const baseWord = { id: word, dictionaryId: dict, kind: 'WORD', code: 'own_word', name: '본인 제출 단어', definition: '화면 계약 검증', dataType: 'STRING', state: 'IN_REVIEW', lockVersion: 1, createdBy: user, updatedBy: user, submittedBy: user, decidedBy: null, decisionReason: null };
const checks = [];
try {
  for (const role of ['ORG_REVIEWER', 'PLATFORM_ADMIN', 'ORG_EDITOR']) {
    for (const feature of ['metadata', 'standards']) {
      const writes = [], unexpected = [], errors = [];
      const draft = { ...baseDraft }, entry = { ...baseWord };
      const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
      await context.route('**/api/admin/v1/**', async route => {
        const req = route.request(); const path = new URL(req.url()).pathname.replace('/api/admin/v1', '');
        const json = (body, tag = 'fixture:1', status = 200) => route.fulfill({ status, contentType: 'application/json', headers: { ETag: `"${tag}"` }, body: JSON.stringify(body) });
        if (req.method() === 'POST') writes.push({ path, body: req.postDataJSON(), headers: req.headers() });
        if (path === '/session') return json({ userId: user, organizationIds: [org], authorities: [`ROLE_${role}`], csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'fixture-token' });
        if (path === '/metadata-options') return json(options);
        if (path === '/datasets') return json({ items: [draft], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } });
        if (path === `/datasets/${dataset}/draft`) return json(draft, `dataset:${dataset}:1`);
        if (path === `/datasets/${dataset}/draft/fields`) return json({ revisionId: revision, lockVersion: 1, fields: [] }, `dataset:${dataset}:1`);
        if (path === `/review-requests/${reviewId}`) return json(review, `review:${reviewId}:1`);
        if (path === `/review-requests/${reviewId}/decisions`) { draft.state = 'APPROVED'; return json({ ...review, status: 'APPROVED' }); }
        if (path === '/dictionaries') return json([dictionary]);
        if (path === `/dictionaries/${dict}/entries`) return json([entry]);
        if (path === `/dictionary-entries/${word}`) return json(entry, `dictionary-entry:${word}:1`);
        if (path === `/dictionary-entries/${word}/relationships`) return json({ entryId: word, kind: 'WORD', lockVersion: 1, word: { englishName: 'own word', abbreviation: 'OW', synonyms: [] }, domain: null, term: null });
        if (path === `/dictionary-entries/${word}/decisions`) { entry.state = 'APPROVED'; return json(entry); }
        unexpected.push(`${req.method()} ${path}`); return json({}, '', 500);
      });
      const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
      try {
        await page.goto(`${origin}/${feature}`);
        if (feature === 'standards') await page.getByRole('button', { name: '우리 기관 사전', exact: true }).click();
        await page.getByRole('button', { name: feature === 'metadata' ? draft.title : entry.name, exact: true }).click();
        const modal = page.getByRole('dialog', { name: feature === 'metadata' ? '메타데이터 상세' : '표준사전 항목 상세', exact: true });
        await expect(modal).toBeVisible();
        const approve = modal.getByRole('button', { name: feature === 'metadata' ? '검토 승인' : '승인', exact: true });
        const reject = modal.getByRole('button', { name: '반려', exact: true });
        if (role === 'ORG_EDITOR') {
          await expect(approve).toHaveCount(0); await expect(reject).toHaveCount(0); assert.equal(writes.length, 0);
        } else {
          await expect(approve).toBeEnabled(); await expect(reject).toBeEnabled();
          assert.ok(!(await modal.innerText()).includes('다른 검토 담당자가 결정'));
          await approve.click();
          if (feature === 'metadata') await page.getByRole('dialog', { name: '검토 승인', exact: true }).getByRole('button', { name: '검토 승인', exact: true }).click();
          else await modal.getByRole('button', { name: '확인', exact: true }).click();
          await expect(modal).toHaveCount(0);
          assert.equal(writes.length, 1); assert.equal(writes[0].body.decision, 'APPROVE');
          assert.equal(writes[0].headers['x-csrf-token'], 'fixture-token');
          assert.match(writes[0].headers['idempotency-key'], /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i);
          assert.equal(writes[0].headers['if-match'], `"${feature === 'metadata' ? `review:${reviewId}:1` : `dictionary-entry:${word}:1`}"`);
        }
        assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
        checks.push({ name: `${feature}-${role}-${role === 'ORG_EDITOR' ? 'approval-hidden' : 'own-submission-approval-post'}`, passed: true });
      } finally { await context.close(); }
    }
  }
  const result = { result: 'PASS', scope: 'Isolated browser UI fixtures; server authorization and persistence are separate', checks };
  await writeFile(`${output}/result.json`, JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result));
} finally { await browser.close(); await server.close(); }
