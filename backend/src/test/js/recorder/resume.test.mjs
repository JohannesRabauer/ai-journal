import assert from 'node:assert/strict';
import { test } from 'node:test';
import { finishRecovered, findPending, recover } from '../../../main/resources/META-INF/resources/js/recorder/resume.js';
import { MemoryStorage } from '../../../main/resources/META-INF/resources/js/recorder/storage.js';
import { FakeServer, bytes, createSleep } from './fakes.mjs';

async function crashedRecording({ local, onServer, stopped = false, totalChunks = null }) {
  const server = new FakeServer();
  const storage = new MemoryStorage();
  const dto = await server.api.start({ mimeType: 'audio/webm;codecs=opus' });
  for (const i of onServer) await server.api.putChunk(dto.id, i, bytes(2));
  for (const i of local) await storage.putChunk(dto.id, i, bytes(2));
  const meta = {
    recordingId: dto.id, entryId: dto.entryId, mimeType: 'audio/webm;codecs=opus', startedAt: 1,
    nextIndex: Math.max(...local, ...onServer) + 1, elapsedMs: 42_000, stopped, totalChunks,
  };
  await storage.putRecording(meta);
  return { server, storage, meta, sleeper: createSleep() };
}

test('findPending lists recordings left in storage, oldest first', async () => {
  const storage = new MemoryStorage();
  await storage.putRecording({ recordingId: 'b', startedAt: 20 });
  await storage.putRecording({ recordingId: 'a', startedAt: 10 });
  assert.deepEqual((await findPending(storage)).map((m) => m.recordingId), ['a', 'b']);
});

test('recover uploads only chunks the server is missing and drops already-received local ones', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [1, 2, 3], onServer: [0, 1] });
  const before = server.putCalls.length;
  const info = await recover({ storage, api: server.api, meta, sleep: sleeper.sleep });
  const uploaded = server.putCalls.slice(before).map((c) => c.index).sort();
  assert.deepEqual(uploaded, [2, 3], 'chunk 1 was already on the server and is not re-sent');
  assert.equal(info.outcome, 'recovered');
  assert.equal(info.nextIndex, 4);
  assert.equal(info.uploaded, 2);
  assert.equal(info.elapsedMs, 42_000);
  assert.deepEqual(await storage.listChunkIndices(meta.recordingId), []);
  assert.deepEqual([...server.recordings.get(meta.recordingId).chunks.keys()].sort(), [0, 1, 2, 3]);
});

test('recover survives a network outage while uploading the missing chunks', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [0, 1], onServer: [] });
  server.offline = true;
  const sleep = async (ms) => { await sleeper.sleep(ms); if (sleeper.delays.length === 2) server.offline = false; };
  const info = await recover({ storage, api: server.api, meta, sleep });
  assert.equal(info.outcome, 'recovered');
  assert.deepEqual([...server.recordings.get(meta.recordingId).chunks.keys()], [0, 1]);
});

test('finishing a recovered recording completes it with the next index as chunk count, then clears local data', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [2], onServer: [0, 1] });
  const info = await recover({ storage, api: server.api, meta, sleep: sleeper.sleep });
  await finishRecovered({ storage, api: server.api, info, sleep: sleeper.sleep });
  const rec = server.recordings.get(meta.recordingId);
  assert.equal(rec.status, 'COMPLETE');
  assert.equal(rec.totalChunks, 3);
  assert.equal(rec.durationMs, 42_000);
  assert.equal(await storage.getRecording(meta.recordingId), undefined);
});

test('a recording that was stopped before the crash finishes with its stored chunk count', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [1], onServer: [0], stopped: true, totalChunks: 2 });
  const info = await recover({ storage, api: server.api, meta, sleep: sleeper.sleep });
  assert.equal(info.stopped, true);
  await finishRecovered({ storage, api: server.api, info, sleep: sleeper.sleep });
  assert.equal(server.recordings.get(meta.recordingId).totalChunks, 2);
});

test('already completed on the server: local leftovers are cleaned up', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [0], onServer: [0] });
  await server.api.complete(meta.recordingId, { totalChunks: 1, durationMs: 1 });
  const info = await recover({ storage, api: server.api, meta, sleep: sleeper.sleep });
  assert.equal(info.outcome, 'already-complete');
  assert.equal(await storage.getRecording(meta.recordingId), undefined);
  assert.deepEqual(await storage.listChunkIndices(meta.recordingId), []);
});

test('unknown on the server: audio is kept locally, never deleted', async () => {
  const { storage, meta, sleeper } = await crashedRecording({ local: [0], onServer: [] });
  const empty = new FakeServer();
  const info = await recover({ storage, api: empty.api, meta, sleep: sleeper.sleep });
  assert.equal(info.outcome, 'server-lost');
  assert.deepEqual(await storage.listChunkIndices(meta.recordingId), [0]);
  assert.ok(await storage.getRecording(meta.recordingId));
});

test('missing chunks make completion fail without removing local metadata', async () => {
  const { server, storage, meta, sleeper } = await crashedRecording({ local: [], onServer: [0, 2] });
  const info = await recover({ storage, api: server.api, meta, sleep: sleeper.sleep });
  await assert.rejects(finishRecovered({ storage, api: server.api, info, sleep: sleeper.sleep }), { status: 409 });
  assert.ok(await storage.getRecording(meta.recordingId));
});
