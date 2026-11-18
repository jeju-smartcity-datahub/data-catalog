import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { AxeBuilder } from '../web/node_modules/@axe-core/playwright/dist/index.mjs';
// Both the workspace scripts and Playwright's web working directory use this helper.
const root = existsSync(join(process.cwd(), 'web', 'package.json')) ? process.cwd() : resolve(process.cwd(), '..');

/** DOM/Chromium accessibility checks; never evidence of audible screen-reader testing. */
export async function auditAccessibility(page, label, source = 'ISOLATED_UI_CONTRACT_FIXTURE') {
  if (!/^[a-z0-9-]+$/.test(label)) throw new Error('Accessibility evidence label must be a safe filename.');
  await page.evaluate(() => document.fonts.ready);
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
  const summarize = finding => ({ id: finding.id, impact: finding.impact, help: finding.help, helpUrl: finding.helpUrl,
    nodes: finding.nodes.map(node => ({ target: node.target, failureSummary: node.failureSummary })) });
  const cdp = await page.context().newCDPSession(page);
  let tree;
  try { tree = await cdp.send('Accessibility.getFullAXTree'); } finally { await cdp.detach(); }
  const exposed = tree.nodes.filter(node => !node.ignored).map(node => ({ role: node.role?.value, name: node.name?.value,
    properties: node.properties?.map(property => ({ name: property.name, value: property.value?.value })), childIds: node.childIds, nodeId: node.nodeId }));
  const output = join(root, '.runtime', 'accessibility');
  await mkdir(output, { recursive: true });
  await writeFile(join(output, `${label}.json`), JSON.stringify({ checkedAt: new Date().toISOString(), source,
    audibleScreenReader: false, url: page.url(), viewport: page.viewportSize(), engine: result.testEngine,
    violations: result.violations.map(summarize), incomplete: result.incomplete.map(summarize),
    passedRuleCount: result.passes.length, exposedNodes: exposed }, null, 2) + '\n');
  await writeFile(join(output, `${label}.aria.txt`), await page.locator('body').ariaSnapshot());
  assert.deepEqual(result.violations.map(summarize), [], `Accessibility violations in ${label}; see .runtime/accessibility/${label}.json`);
  return { label, passedRuleCount: result.passes.length, incomplete: result.incomplete.length, exposedNodes: exposed.length };
}
