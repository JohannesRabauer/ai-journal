import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ChunkQueue } from '../../../main/frontend/recorder/chunk-queue.js';
import { MemoryStorage } from '../../../main/frontend/recorder/storage.js';
import { UploadError } from '../../../main/frontend/recorder/upload-api.js';
import { FakeServer, bytes, createSleep, settle } from './fakes.mjs';

function setup({ sleepHook, retry = { random: () => 0.5 }, concurrency, api } = {}) {
  const storage = new MemoryStorage();
  const server = new FakeServer();
  const sleeper = createSleep(sleepHook);
  const events = [];
  let queue;
  const ready = server.api.start({ mimeType: 'audio/webm' }).then((dto) => {
    queue = new ChunkQueue({
      recordingId: dto.id, storage, api: api?.(server) ?? server.api, sleep: sleeper.sleep, retry, concurrency,
      onEvent: (name, detail) => events.push([name, detail]),
    });
    return { id: dto.id };
  });
  return { storage, server, sleeper, events, ready, get queue() { return queue; } };
}

test('a chunk is persisted before its upload starts and removed after the ACK', async () => {
  const t = setup();
  const { id } = await t.ready;
  let storedWhenUploading = null;
  const original = t.server.api.putChunk;
  t.server.api.putChunk = async (rid, index, blob) => {
    storedWhenUploading = await t.storage.getChunk(rid, index);
    return original(rid, index, blob);
  };
  await t.queue.enqueue(0, bytes(5));
  await t.queue.flush();
  assert.ok(storedWhenUploading, 'chunk was in storage when the upload began');
  assert.deepEqual(await t.storage.listChunkIndices(id), []);
  assert.deepEqual(t.queue.ackedIndices, [0]);
});

test('out-of-order ACKs still end with an empty queue and all chunks stored on the server', async () => {
  const t = setup({ concurrency: 3 });
  const { id } = await t.ready;
  t.server.holdChunk(0);
  t.server.holdChunk(1);
  await t.queue.enqueue(0, bytes());
  await t.queue.enqueue(1, bytes());
  await t.queue.enqueue(2, bytes());
  await settle();
  assert.deepEqual(t.queue.ackedIndices, [2], 'chunk 2 acknowledged first');
  assert.deepEqual(await t.storage.listChunkIndices(id), [0, 1], 'unacknowledged chunks stay in storage');
  t.server.releaseChunk(1);
  await settle();
  t.server.releaseChunk(0);
  await t.queue.flush();
  assert.deepEqual(t.queue.ackedIndices, [0, 1, 2]);
  assert.deepEqual(await t.storage.listChunkIndices(id), []);
  assert.equal(t.queue.pendingCount, 0);
});

test('enqueueing the same index twice uploads it once', async () => {
  const t = setup();
  await t.ready;
  await Promise.all([t.queue.enqueue(0, bytes()), t.queue.enqueue(0, bytes())]);
  await t.queue.flush();
  await t.queue.enqueue(0, bytes()); // after the ACK as well
  await t.queue.flush();
  assert.equal(t.server.putCalls.filter((c) => c.index === 0).length, 1);
});

test('server-side duplicate (re-upload after lost ACK) is accepted', async () => {
  const t = setup();
  const { id } = await t.ready;
  await t.server.api.putChunk(id, 0, bytes()); // already stored, client never saw the ACK
  await t.queue.enqueue(0, bytes());
  await t.queue.flush();
  assert.deepEqual(t.queue.ackedIndices, [0]);
  assert.equal(t.server.recordings.get(id).chunks.size, 1);
});

test('network down then up: retries with growing jittered delays, then succeeds without data loss', async () => {
  const probes = [];
  const t = setup({ sleepHook: (n) => {
    probes.push(t.storage.getChunk('rec-1', 0)); // what is in storage while we are waiting offline
    if (n === 3) t.server.offline = false;
  } });
  await t.ready;
  t.server.offline = true;
  await t.queue.enqueue(0, bytes(7));
  await t.queue.flush();
  assert.deepEqual((await Promise.all(probes)).map((b) => b?.size), [7, 7, 7], 'chunk safe in storage while offline');
  assert.deepEqual(t.queue.ackedIndices, [0]);
  assert.equal(t.sleeper.delays.length, 3);
  assert.deepEqual(t.sleeper.delays, [250, 500, 1000], 'random()=0.5 halves the exponential caps 500/1000/2000');
  assert.equal(t.events.filter(([n]) => n === 'retrying').length, 3);
});

test('server errors (5xx, 429) are retried; interrupted upload is re-sent in full', async () => {
  const t = setup();
  const { id } = await t.ready;
  t.server.failNext('putChunk', 503, 2);
  t.server.failNext('putChunk', 429, 1);
  await t.queue.enqueue(0, bytes(9));
  await t.queue.flush();
  assert.equal(t.server.recordings.get(id).chunks.get(0), 9);
  assert.equal(t.sleeper.delays.length, 3);
});

test('non-retryable errors fail the flush, keep the chunk in storage and can be retried', async () => {
  const t = setup();
  const { id } = await t.ready;
  t.server.failNext('putChunk', 400, 1);
  await t.queue.enqueue(0, bytes());
  await assert.rejects(t.queue.flush(), (e) => e instanceof UploadError && e.status === 400);
  assert.equal(t.sleeper.delays.length, 0);
  assert.deepEqual(await t.storage.listChunkIndices(id), [0]);
  t.queue.retryFailed();
  await t.queue.flush();
  assert.deepEqual(await t.storage.listChunkIndices(id), []);
});

test('kick() ends the backoff wait immediately', async () => {
  const storage = new MemoryStorage();
  let attempts = 0;
  const api = { putChunk: async () => { if (++attempts === 1) throw new UploadError('offline'); return {}; } };
  const queue = new ChunkQueue({
    recordingId: 'r', storage, api, retry: { random: () => 0.5 },
    sleep: () => new Promise(() => {}), // would wait forever
  });
  await queue.enqueue(0, bytes());
  await settle();
  assert.equal(attempts, 1);
  queue.kick();
  await queue.flush();
  assert.equal(attempts, 2);
});

test('if storage fails the chunk is kept in memory and still uploaded', async () => {
  const storage = new MemoryStorage();
  storage.putChunk = async () => { throw new Error('QuotaExceededError'); };
  const events = [];
  const uploaded = [];
  const queue = new ChunkQueue({
    recordingId: 'r', storage, sleep: async () => {}, onEvent: (n) => events.push(n),
    api: { putChunk: async (id, index, blob) => { uploaded.push([index, blob.size]); return {}; } },
  });
  await queue.enqueue(0, bytes(4));
  await queue.flush();
  assert.deepEqual(uploaded, [[0, 4]]);
  assert.ok(events.includes('storage-error'));
});

test('concurrency is limited', async () => {
  let running = 0;
  let maxRunning = 0;
  const release = [];
  const queue = new ChunkQueue({
    recordingId: 'r', storage: new MemoryStorage(), sleep: async () => {}, concurrency: 2,
    api: {
      putChunk: async () => {
        running++;
        maxRunning = Math.max(maxRunning, running);
        await new Promise((r) => release.push(r));
        running--;
        return {};
      },
    },
  });
  for (let i = 0; i < 5; i++) await queue.enqueue(i, bytes());
  await settle();
  assert.equal(maxRunning, 2);
  while (queue.pendingCount > 0) {
    release.splice(0).forEach((r) => r());
    await settle();
  }
  assert.equal(maxRunning, 2);
});
