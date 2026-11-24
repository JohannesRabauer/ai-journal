import { test } from 'node:test';
import assert from 'node:assert/strict';
import { api, post, ApiError } from '../../main/resources/META-INF/resources/js/api.js';

function respond(status, body, statusText = '') {
  return async () => ({
    ok: status >= 200 && status < 300, status, statusText,
    text: async () => (body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body)),
  });
}

test('always sends the CSRF header and asks for JSON', async () => {
  let seen;
  await api('/api/entries', { fetch: async (url, init) => { seen = { url, init }; return respond(200, [])(); } });
  assert.equal(seen.url, '/api/entries');
  assert.equal(seen.init.headers['X-AI-Diary'], '1');
  assert.equal(seen.init.headers.Accept, 'application/json');
  assert.equal(seen.init.method, 'GET');
  assert.equal(seen.init.headers['Content-Type'], undefined);
});

test('JSON bodies are encoded with a content type', async () => {
  let seen;
  const result = await post('/api/entries', { text: 'hi' }, {
    fetch: async (url, init) => { seen = init; return respond(200, { id: 4 })(); },
  });
  assert.deepEqual(result, { id: 4 });
  assert.equal(seen.method, 'POST');
  assert.equal(seen.body, '{"text":"hi"}');
  assert.equal(seen.headers['Content-Type'], 'application/json');
  assert.equal(seen.headers['X-AI-Diary'], '1');
});

test('no content resolves with null', async () => {
  assert.equal(await api('/x', { fetch: respond(204) }), null);
  assert.equal(await api('/x', { fetch: respond(200, '') }), null);
});

test('backend errors become { code, message }', async () => {
  await assert.rejects(
    api('/x', { fetch: respond(409, { code: 'ENTRY_BUSY', message: 'Still transcribing.' }) }),
    (e) => e instanceof ApiError && e.code === 'ENTRY_BUSY' && e.message === 'Still transcribing.' && e.status === 409);
});

test('non-JSON errors fall back to the HTTP status', async () => {
  await assert.rejects(api('/x', { fetch: respond(502, '<html>', 'Bad Gateway') }),
    (e) => e.code === 'HTTP_502' && e.message === 'Bad Gateway' && e.status === 502);
});

test('an unreachable server is NETWORK, an abort stays an abort', async () => {
  await assert.rejects(api('/x', { fetch: async () => { throw new TypeError('Failed to fetch'); } }),
    (e) => e.code === 'NETWORK' && e.status === 0);
  const abort = Object.assign(new Error('aborted'), { name: 'AbortError' });
  await assert.rejects(api('/x', { fetch: async () => { throw abort; } }), (e) => e === abort);
});

test('an unreadable success body is BAD_RESPONSE', async () => {
  await assert.rejects(api('/x', { fetch: respond(200, 'not json') }), (e) => e.code === 'BAD_RESPONSE');
});
