import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RecorderSession } from '../../../main/frontend/recorder/recorder-session.js';
import { IllegalTransitionError, State } from '../../../main/frontend/recorder/state-machine.js';
import { MemoryStorage } from '../../../main/frontend/recorder/storage.js';
import {
  FakeServer, bytes, createClock, createFakeMediaDevices, createFakeMediaRecorder, createSleep, settle,
} from './fakes.mjs';

function setup({ server = new FakeServer(), storage = new MemoryStorage(), deny = false, sleepHook, dead = false } = {}) {
  const MediaRecorder = createFakeMediaRecorder();
  const mediaDevices = createFakeMediaDevices({ deny });
  const clock = createClock(1_000);
  const sleeper = createSleep(sleepHook, { dead });
  const log = [];
  const session = new RecorderSession({
    mediaDevices, MediaRecorder, storage, api: server.api, now: clock.now, sleep: sleeper.sleep,
    timesliceMs: 10_000, retry: { random: () => 0.5 },
  });
  for (const name of ['started', 'completed', 'error', 'recovered', 'retrying']) {
    session.on(name, (d) => log.push([name, d]));
  }
  return { session, MediaRecorder, mediaDevices, clock, sleeper, server, storage, log, rec: () => MediaRecorder.instances.at(-1) };
}

const events = (log, name) => log.filter(([n]) => n === name).map(([, d]) => d);

test('full recording: chunks are uploaded and persisted, then completed with chunk count and duration', async () => {
  const t = setup();
  await t.session.start({ language: 'de' });
  assert.equal(t.session.state, State.RECORDING);
  assert.equal(t.rec().timeslice, 10_000);
  assert.equal(t.rec().mimeType, 'audio/webm;codecs=opus');
  const started = events(t.log, 'started')[0];
  assert.equal(started.recordingId, 'rec-1');

  t.clock.advance(10_000);
  t.rec().emitChunk(bytes(10));
  t.clock.advance(10_000);
  t.rec().emitChunk(bytes(10));
  t.clock.advance(3_000);
  t.rec().finalBytes = bytes(4);
  await t.session.stop();

  assert.equal(t.session.state, State.DONE);
  t.session.reset();
  assert.equal(t.session.state, State.IDLE);
  const rec = t.server.recordings.get('rec-1');
  assert.equal(rec.status, 'COMPLETE');
  assert.equal(rec.totalChunks, 3);
  assert.equal(rec.durationMs, 23_000);
  assert.equal((await t.storage.listRecordings()).length, 0, 'local data removed after completion');
  assert.equal(t.mediaDevices.stream.stopped, true, 'microphone released');
  assert.deepEqual(events(t.log, 'completed'), [{ recordingId: 'rec-1', entryId: started.entryId, durationMs: 23_000 }]);
});

test('pause stops the clock; elapsed excludes paused time', async () => {
  const t = setup();
  await t.session.start();
  t.clock.advance(5_000);
  t.session.pause();
  assert.equal(t.session.state, State.PAUSED);
  t.clock.advance(60_000);
  assert.equal(t.session.elapsedMs(), 5_000);
  t.session.resume();
  t.clock.advance(2_000);
  assert.equal(t.session.elapsedMs(), 7_000);
  assert.throws(() => t.session.resume(), IllegalTransitionError);
});

test('permission denied ends in error with a calm message and no recording', async () => {
  const t = setup({ deny: true });
  await t.session.start();
  assert.equal(t.session.state, State.ERROR);
  assert.equal(t.server.recordings.size, 0);
  assert.match(events(t.log, 'error')[0].message, /microphone/i);
  t.session.reset();
  assert.equal(t.session.state, State.IDLE);
});

test('network down during recording and stop: audio stays safe and everything is uploaded after it returns', async () => {
  const t = setup();
  await t.session.start();
  t.server.offline = true;
  t.rec().emitChunk(bytes(6));
  t.rec().emitChunk(bytes(6));
  await settle();
  assert.deepEqual(await t.storage.listChunkIndices('rec-1'), [0, 1], 'chunks wait in storage while offline');
  assert.ok(events(t.log, 'retrying').length >= 1);
  t.server.offline = false;
  await t.session.stop();
  assert.equal(t.server.recordings.get('rec-1').status, 'COMPLETE');
  assert.deepEqual([...t.server.recordings.get('rec-1').chunks.keys()].sort(), [0, 1]);
});

test('chunks are not retained after persisting: nothing pending in memory once acknowledged', async () => {
  const t = setup();
  await t.session.start();
  for (let i = 0; i < 50; i++) t.rec().emitChunk(bytes(100));
  await settle(50);
  assert.equal(t.session.pendingChunks, 0);
  assert.deepEqual(await t.storage.listChunkIndices('rec-1'), []);
});

test('reload mid-recording: a new session finds the pending recording, uploads missing chunks and can continue', async () => {
  const server = new FakeServer();
  const storage = new MemoryStorage();
  const a = setup({ server, storage, dead: true });
  await a.session.start({ language: 'en' });
  a.rec().emitChunk(bytes(8));
  await settle();
  server.offline = true; // the tab dies while the network is down
  a.rec().emitChunk(bytes(8));
  a.rec().emitChunk(bytes(8));
  await settle();
  assert.deepEqual(await storage.listChunkIndices('rec-1'), [1, 2]);
  server.offline = false;

  // --- page reload: new session objects, same IndexedDB (storage) and same server
  const b = setup({ server, storage });
  const info = await b.session.recoverPending();
  assert.equal(info.outcome, 'recovered');
  assert.equal(info.nextIndex, 3);
  assert.equal(info.stopped, false);
  assert.deepEqual([...server.recordings.get('rec-1').chunks.keys()].sort(), [0, 1, 2], 'no audio lost');
  assert.equal(events(b.log, 'recovered').length, 1);

  // continue: indices carry on, same recording id and entry
  await b.session.start({ continueFrom: info });
  assert.equal(b.session.recordingId, 'rec-1');
  assert.equal(b.session.elapsedMs() >= info.elapsedMs, true);
  b.rec().emitChunk(bytes(8));
  await b.session.stop();
  const rec = server.recordings.get('rec-1');
  assert.equal(rec.status, 'COMPLETE');
  assert.equal(rec.totalChunks, 4);
});

test('reload mid-recording: user can finish the recovered recording as it is', async () => {
  const server = new FakeServer();
  const storage = new MemoryStorage();
  const a = setup({ server, storage });
  await a.session.start();
  a.rec().emitChunk(bytes(8));
  await settle();

  const b = setup({ server, storage });
  const info = await b.session.recoverPending();
  await b.session.finishRecovered(info);
  assert.equal(server.recordings.get('rec-1').status, 'COMPLETE');
  assert.equal(server.recordings.get('rec-1').totalChunks, 1);
  assert.equal(events(b.log, 'completed').length, 1);
  assert.equal((await storage.listRecordings()).length, 0);
});

test('crash while uploading after stop: recovery completes the recording automatically', async () => {
  const server = new FakeServer();
  const storage = new MemoryStorage();
  const a = setup({ server, storage, dead: true });
  await a.session.start();
  server.offline = true;
  a.rec().emitChunk(bytes(8));
  a.rec().emitChunk(bytes(8));
  a.session.stop(); // never settles: network is down, then the tab "crashes"
  await settle();
  assert.equal(a.session.state, State.UPLOADING);
  assert.equal((await storage.getRecording('rec-1')).stopped, true);
  server.offline = false;

  const b = setup({ server, storage });
  const info = await b.session.recoverPending();
  assert.equal(info, null, 'nothing left to offer: it was completed');
  assert.equal(server.recordings.get('rec-1').status, 'COMPLETE');
  assert.equal(server.recordings.get('rec-1').totalChunks, 2);
  assert.equal(events(b.log, 'completed').length, 1);
});

test('recoverPending with nothing pending is a no-op', async () => {
  const t = setup();
  assert.equal(await t.session.recoverPending(), null);
  assert.equal(t.session.state, State.IDLE);
});

test('complete fails with 409: error state keeps local audio, retry after fix succeeds', async () => {
  const t = setup();
  await t.session.start();
  t.rec().emitChunk(bytes(8));
  t.server.failNext('complete', 409, 1);
  await t.session.stop();
  assert.equal(t.session.state, State.ERROR);
  assert.ok(await t.storage.getRecording('rec-1'), 'local metadata kept after failure');
  assert.equal(events(t.log, 'error').length, 1);
  await t.session.retryUpload();
  assert.equal(t.server.recordings.get('rec-1').status, 'COMPLETE');
});

test('start failure (server unreachable) releases the microphone and reports an error', async () => {
  const t = setup();
  t.server.offline = true;
  await t.session.start();
  assert.equal(t.session.state, State.ERROR);
  assert.equal(t.mediaDevices.stream.stopped, true);
});

test('MediaRecorder falls back to what the browser supports', async () => {
  const t = setup();
  t.MediaRecorder.supported = new Set(['audio/mp4']);
  await t.session.start();
  assert.equal(t.rec().mimeType, 'audio/mp4');
});
