import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/features/dcatFeed.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { dcatPath, readDcatFeed } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

const json = '{"@context":{"dcat":"http://www.w3.org/ns/dcat#"},"@graph":[]}';
const turtle = '@prefix dcat: <http://www.w3.org/ns/dcat#> .\n<urn:catalog> a dcat:Catalog .';
const response = (body, type, total = '101', extra = {}) => new Response(body, { headers: { 'Content-Type': type, 'X-Total-Count': total, ...extra } });

test('Turtle requests the selected page with its media type and preserves the response body', async () => {
  const signal = new AbortController().signal;
  const feed = await readDcatFeed('turtle', 1, signal, false, async (url, options) => {
    assert.equal(url, '/api/catalog/v1/dcat?page=1&size=100');
    assert.equal(options.headers.Accept, 'text/turtle');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.signal, signal);
    return response(turtle, 'text/turtle;charset=UTF-8');
  });
  assert.deepEqual(feed, { body: turtle, total: 101, page: 1, format: 'turtle' });
});

test('JSON-LD uses JSON-LD Accept and displays formatted JSON', async () => {
  const feed = await readDcatFeed('jsonld', 0, new AbortController().signal, false, async (_url, options) => {
    assert.equal(options.headers.Accept, 'application/ld+json');
    return response(json, 'application/ld+json; charset=UTF-8', '0');
  });
  assert.equal(feed.body, JSON.stringify(JSON.parse(json), null, 2));
  assert.equal(feed.total, 0);
});

test('downloads query the selected format and page instead of the full catalog', async () => {
  for (const format of ['jsonld', 'turtle']) {
    const feed = await readDcatFeed(format, 2, new AbortController().signal, true, async (url) => {
      assert.equal(url, `/api/catalog/v1/dcat/download?page=2&size=100&format=${format}`);
      return response(format === 'turtle' ? turtle : json, format === 'turtle' ? 'text/turtle' : 'application/ld+json', '250');
    });
    assert.equal(feed.page, 2);
    assert.equal(feed.total, 250);
  }
});

test('HTTP failures and unexpected response formats are not displayed as a catalog', async () => {
  const signal = new AbortController().signal;
  await assert.rejects(readDcatFeed('turtle', 0, signal, false, async () => new Response('failed', { status: 502 })));
  await assert.rejects(readDcatFeed('turtle', 0, signal, false, async () => response('<html>sign in</html>', 'text/html')));
  await assert.rejects(readDcatFeed('jsonld', 0, signal, false, async () => response('invalid JSON', 'application/ld+json')));
});

test('invalid totals and page indexes cannot produce misleading pagination', async () => {
  for (const total of ['-1', '1.5', 'unknown', '9007199254740992']) {
    await assert.rejects(readDcatFeed('turtle', 0, new AbortController().signal, false, async () => response(turtle, 'text/turtle', total)));
  }
  await assert.rejects(readDcatFeed('turtle', 0, new AbortController().signal, false, async () => new Response(turtle, { headers: { 'Content-Type': 'text/turtle' } })));
  for (const page of [-1, 0.5, Number.NaN]) assert.throws(() => dcatPath(page));
});

test('oversized responses are rejected even without a declared content length', async () => {
  const signal = new AbortController().signal;
  const bytes = new Uint8Array(8 * 1024 * 1024 + 1);
  await assert.rejects(readDcatFeed('turtle', 0, signal, false, async () => response(bytes, 'text/turtle')));
  await assert.rejects(readDcatFeed('turtle', 0, signal, false, async () => response(turtle, 'text/turtle', '1', { 'Content-Length': String(bytes.length) })));
});

test('request cancellation rejects instead of replacing a failed request with empty data', async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(readDcatFeed('turtle', 0, controller.signal, false, async (_url, options) => {
    options.signal.throwIfAborted();
    return response(turtle, 'text/turtle');
  }), { name: 'AbortError' });
});
