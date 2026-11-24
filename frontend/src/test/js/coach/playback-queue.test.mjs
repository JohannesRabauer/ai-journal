import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRecordingGuard } from '../../../main/frontend/coach/recording-guard.js';
import { createPlaybackQueue } from '../../../main/frontend/coach/playback-queue.js';

class FakeAudio {
  static all = [];
  constructor(url) {
    this.url = url;
    this.handlers = {};
    this.playing = false;
    this.paused = false;
    this.rejectPlay = false;
    FakeAudio.all.push(this);
  }
  addEventListener(name, fn) {
    this.handlers[name] = fn;
  }
  async play() {
    if (this.rejectPlay) {
      throw new Error('NotAllowedError');
    }
    this.playing = true;
  }
  pause() {
    this.playing = false;
    this.paused = true;
  }
  finish() {
    this.playing = false;
    this.handlers.ended?.();
  }
}

function setup({ audioFor = () => ({ blob: true }) } = {}) {
  FakeAudio.all = [];
  const guard = createRecordingGuard();
  const fetched = [];
  const urls = { created: [], revoked: [] };
  const states = [];
  const queue = createPlaybackQueue({
    guard,
    fetchAudio: async (item) => {
      fetched.push(item.id);
      return audioFor(item);
    },
    createAudio: (url) => new FakeAudio(url),
    objectUrl: {
      create: (blob) => { const url = `blob:${urls.created.length}`; urls.created.push(url); return url; },
      revoke: (url) => urls.revoked.push(url),
    },
    onChange: (s) => states.push(s),
  });
  return { guard, queue, fetched, urls, states };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));
const item = (id) => ({ id, text: `question ${id}`, language: 'DE', voice: null });

test('plays one item and fetches its audio', async () => {
  const { queue, fetched } = setup();
  queue.enqueue(item(1));
  await tick();
  assert.deepEqual(fetched, [1]);
  assert.equal(FakeAudio.all.length, 1);
  assert.equal(FakeAudio.all[0].playing, true);
  assert.equal(queue.state().playingId, 1);
});

test('plays strictly one at a time, in order', async () => {
  const { queue } = setup();
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  assert.equal(FakeAudio.all.length, 1);
  FakeAudio.all[0].finish();
  await tick();
  assert.equal(FakeAudio.all.length, 2);
  assert.equal(queue.state().playingId, 2);
  FakeAudio.all[1].finish();
  await tick();
  assert.equal(queue.state().playingId, null);
});

test('duplicate ids are ignored while queued or playing', async () => {
  const { queue } = setup();
  assert.equal(queue.enqueue(item(1)), true);
  assert.equal(queue.enqueue(item(1)), false);
  await tick();
  assert.equal(queue.enqueue(item(1)), false);
  assert.equal(FakeAudio.all.length, 1);
});

test('nothing starts while a recording is active; queued speech waits', async () => {
  const { guard, queue, fetched } = setup();
  guard.setActive('rec', true);
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  assert.deepEqual(fetched, [], 'not even fetched');
  assert.equal(FakeAudio.all.length, 0);
  assert.equal(queue.state().queued, 2);
  guard.setActive('rec', false);
  await tick();
  assert.equal(FakeAudio.all.length, 1);
  assert.equal(queue.state().playingId, 1);
  assert.equal(queue.state().queued, 1);
});

test('starting a recording stops ongoing playback immediately and drops the clip', async () => {
  const { guard, queue, urls } = setup();
  queue.enqueue(item(1));
  await tick();
  const audio = FakeAudio.all[0];
  assert.equal(audio.playing, true);
  guard.setActive('rec', true);
  assert.equal(audio.playing, false);
  assert.equal(audio.paused, true);
  assert.deepEqual(urls.revoked, ['blob:0']);
  assert.equal(queue.state().playingId, null);
  guard.setActive('rec', false);
  await tick();
  assert.equal(FakeAudio.all.length, 1, 'the interrupted clip is not replayed');
});

test('queued items survive an interruption and play after the recording', async () => {
  const { guard, queue } = setup();
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  guard.setActive('rec', true);
  guard.setActive('rec', false);
  await tick();
  assert.equal(queue.state().playingId, 2);
});

test('a recording that starts while audio is still being fetched prevents playback', async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const { guard, queue } = setup({ audioFor: () => gate.then(() => ({ blob: true })) });
  queue.enqueue(item(1));
  await tick();
  assert.equal(queue.state().loadingId, 1);
  guard.setActive('rec', true);
  release();
  await tick();
  assert.equal(FakeAudio.all.length, 0, 'no audio element while recording');
  assert.equal(queue.state().queued, 1, 'the item waits');
  guard.setActive('rec', false);
  await tick();
  assert.equal(queue.state().playingId, 1);
});

test('the recording starting between fetch and play also blocks (late guard check)', async () => {
  const guardRef = {};
  const { guard, queue } = setup({
    audioFor: () => { guardRef.guard.setActive('rec', true); return { blob: true }; },
  });
  guardRef.guard = guard;
  queue.enqueue(item(1));
  await tick();
  assert.equal(FakeAudio.all.length, 0);
  assert.equal(queue.state().queued, 1);
});

test('no audio (204/error) is skipped silently and the next item plays', async () => {
  const { queue } = setup({ audioFor: (it) => (it.id === 1 ? null : { blob: true }) });
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  await tick();
  assert.equal(queue.state().playingId, 2);
});

test('a fetch that throws is treated as silence', async () => {
  const { queue } = setup({ audioFor: () => { throw new Error('network'); } });
  queue.enqueue(item(1));
  await tick();
  assert.equal(FakeAudio.all.length, 0);
  assert.deepEqual(queue.state(), { playingId: null, loadingId: null, queued: 0 });
});

test('blocked autoplay (play() rejects) moves on without throwing', async () => {
  const { queue } = setup();
  const original = FakeAudio.prototype.play;
  FakeAudio.prototype.play = async function rejecting() { throw new Error('NotAllowedError'); };
  try {
    queue.enqueue(item(1));
    await tick();
    assert.equal(queue.state().playingId, null);
  } finally {
    FakeAudio.prototype.play = original;
  }
});

test('stop() halts playback, clears the queue and revokes the url', async () => {
  const { queue, urls } = setup();
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  queue.stop();
  assert.equal(FakeAudio.all[0].playing, false);
  assert.deepEqual(queue.state(), { playingId: null, loadingId: null, queued: 0 });
  assert.deepEqual(urls.revoked, ['blob:0']);
  await tick();
  assert.equal(FakeAudio.all.length, 1);
});

test('stop() during a fetch means the late audio never plays', async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const { queue } = setup({ audioFor: () => gate.then(() => ({ blob: true })) });
  queue.enqueue(item(1));
  await tick();
  queue.stop();
  release();
  await tick();
  assert.equal(FakeAudio.all.length, 0);
});

test('playNow replaces what is playing and queued; refuses while recording', async () => {
  const { guard, queue } = setup();
  queue.enqueue(item(1));
  queue.enqueue(item(2));
  await tick();
  assert.equal(queue.playNow(item(9)), true);
  await tick();
  assert.equal(queue.state().playingId, 9);
  assert.equal(queue.state().queued, 0);
  guard.setActive('rec', true);
  assert.equal(queue.playNow(item(10)), false);
  assert.equal(queue.state().queued, 0, 'nothing is queued behind the recording');
});

test('state changes are reported', async () => {
  const { queue, states } = setup();
  queue.enqueue(item(1));
  await tick();
  assert.ok(states.some((s) => s.loadingId === 1));
  assert.ok(states.some((s) => s.playingId === 1));
});
