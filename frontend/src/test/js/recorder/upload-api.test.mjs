import assert from 'node:assert/strict';
import { test } from 'node:test';
import { UploadError, createUploadApi, isRetryableStatus } from '../../../main/frontend/recorder/upload-api.js';
import { formatElapsed, localIsoDateTime } from '../../../main/frontend/recorder/format.js';
import { computeLevel, drawWaveform } from '../../../main/frontend/recorder/waveform.js';
import { MemoryStorage } from '../../../main/frontend/recorder/storage.js';

function fetchStub(handler) {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, ...init });
    return handler(url, init);
  };
  return { fetch, calls };
}

const json = (status, body) => ({ ok: status < 300, status, json: async () => body });

test('requests go to the same-origin proxy with the right shape', async () => {
  const { fetch, calls } = fetchStub(() => json(200, { ok: true }));
  const api = createUploadApi({ fetch });
  const blob = new Blob([new Uint8Array(3)]);
  await api.start({ mimeType: 'audio/webm', language: 'de' });
  await api.putChunk('abc', 4, blob);
  await api.status('abc');
  await api.complete('abc', { totalChunks: 5, durationMs: 1234 });
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['POST', '/upload/recordings'],
    ['PUT', '/upload/recordings/abc/chunks/4'],
    ['GET', '/upload/recordings/abc'],
    ['POST', '/upload/recordings/abc/complete'],
  ]);
  assert.equal(JSON.parse(calls[0].body).language, 'DE');
  assert.equal(calls[1].body, blob);
  assert.equal(calls[1].headers['Content-Type'], 'application/octet-stream');
  assert.deepEqual(JSON.parse(calls[3].body), { totalChunks: 5, durationMs: 1234 });
});

test('errors: network failure is retryable, 4xx is not, backend message is kept', async () => {
  const net = createUploadApi({ fetch: async () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(net.status('x'), (e) => e instanceof UploadError && e.retryable && e.status === undefined);

  const bad = createUploadApi({ fetch: async () => json(409, { code: 'MISSING', message: 'Missing chunks: 3' }) });
  await assert.rejects(bad.complete('x', { totalChunks: 4, durationMs: 1 }),
    (e) => e.status === 409 && !e.retryable && e.message === 'Missing chunks: 3' && e.code === 'MISSING');

  const html = createUploadApi({ fetch: async () => ({ ok: false, status: 502, json: async () => { throw new Error('html'); } }) });
  await assert.rejects(html.status('x'), (e) => e.status === 502 && e.retryable);
});

test('retryable statuses', () => {
  for (const s of [undefined, 408, 429, 500, 502, 503]) assert.equal(isRetryableStatus(s), true, String(s));
  for (const s of [400, 404, 409, 413]) assert.equal(isRetryableStatus(s), false, String(s));
});

test('localIsoDateTime uses the browser local wall-clock fields', () => {
  assert.equal(localIsoDateTime(new Date(2026, 2, 5, 0, 46, 3)), '2026-03-05T00:46:03');
  assert.equal(localIsoDateTime(new Date(2026, 11, 31, 23, 59, 59)), '2026-12-31T23:59:59');
});

test('formatElapsed', () => {
  assert.equal(formatElapsed(0), '00:00');
  assert.equal(formatElapsed(65_999), '01:05');
  assert.equal(formatElapsed(3_600_000), '1:00:00');
  assert.equal(formatElapsed(3_725_000), '1:02:05');
  assert.equal(formatElapsed(-5), '00:00');
});

test('computeLevel: silence is 0, loud signal is high and capped at 1', () => {
  assert.equal(computeLevel(new Uint8Array(64).fill(128)), 0);
  const loud = new Uint8Array(64).map((_, i) => (i % 2 ? 255 : 0));
  assert.ok(computeLevel(loud) > 0.9 && computeLevel(loud) <= 1);
  assert.equal(computeLevel(new Uint8Array(0)), 0);
});

test('drawWaveform draws one segment per sample', () => {
  const ops = [];
  const ctx = new Proxy({}, { get: (_, name) => (...args) => ops.push([name, ...args]), set: () => true });
  drawWaveform(ctx, 100, 50, new Uint8Array([128, 255, 0]), '#000');
  assert.equal(ops.filter(([n]) => n === 'lineTo').length, 2);
  assert.equal(ops.filter(([n]) => n === 'moveTo').length, 1);
});

test('MemoryStorage deleteRecording removes its chunks too and isolates stored metadata', async () => {
  const s = new MemoryStorage();
  const meta = { recordingId: 'r', n: 1 };
  await s.putRecording(meta);
  await s.putChunk('r', 2, new Blob([1]));
  await s.putChunk('r', 0, new Blob([1]));
  meta.n = 99;
  assert.equal((await s.getRecording('r')).n, 1);
  assert.deepEqual(await s.listChunkIndices('r'), [0, 2]);
  await s.deleteRecording('r');
  assert.deepEqual(await s.listChunkIndices('r'), []);
  assert.equal(await s.getRecording('r'), undefined);
});
