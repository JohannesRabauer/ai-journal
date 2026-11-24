import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRecordingGuard } from '../../../main/resources/META-INF/resources/js/coach/recording-guard.js';
import {
  AnswerState, MAX_ANSWER_MS, classifyTranscribeError, createAnswerRecorder, pickMimeType,
} from '../../../main/resources/META-INF/resources/js/coach/answer-recorder.js';

class FakeTrack {
  stopped = false;
  stop() { this.stopped = true; }
}

class FakeStream {
  tracks = [new FakeTrack()];
  getTracks() { return this.tracks; }
}

class FakeMediaRecorder {
  static instances = [];
  static types = new Set(['audio/webm']);
  static isTypeSupported(type) { return FakeMediaRecorder.types.has(type); }
  constructor(stream, options) {
    this.stream = stream;
    this.mimeType = options?.mimeType ?? '';
    this.state = 'inactive';
    this.handlers = {};
    FakeMediaRecorder.instances.push(this);
  }
  addEventListener(name, fn) { this.handlers[name] = fn; }
  start() { this.state = 'recording'; }
  stop() {
    if (this.state === 'inactive') {
      return;
    }
    this.state = 'inactive';
    this.handlers.dataavailable?.({ data: new Blob([new Uint8Array(this.bytes ?? 8)]) });
    this.handlers.stop?.();
  }
}

function setup({ transcribe, getUserMedia } = {}) {
  FakeMediaRecorder.instances = [];
  const guard = createRecordingGuard();
  const events = [];
  const clock = { t: 1000 };
  const timers = new Map();
  let nextTimer = 1;
  const stream = new FakeStream();
  const recorder = createAnswerRecorder({
    mediaDevices: { getUserMedia: getUserMedia ?? (async () => stream) },
    MediaRecorder: FakeMediaRecorder,
    guard,
    transcribe: transcribe ?? (async () => ({ text: '  Ich war müde.  ', detectedLanguage: 'DE' })),
    now: () => clock.t,
    setTimer: (fn, ms) => { const id = nextTimer++; timers.set(id, { fn, ms }); return id; },
    clearTimer: (id) => timers.delete(id),
    onEvent: (e) => events.push(e),
  });
  return { recorder, guard, events, clock, timers, stream };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));
const kinds = (events, type) => events.filter((e) => e.type === type);

test('happy path: record, stop, transcribe, deliver trimmed text, release the guard', async () => {
  const { recorder, guard, events, stream } = setup();
  await recorder.start();
  assert.equal(recorder.state, AnswerState.RECORDING);
  assert.equal(guard.isRecording(), true);
  recorder.stop();
  assert.equal(guard.isRecording(), false, 'guard released as soon as recording has ended');
  await tick();
  assert.equal(recorder.state, AnswerState.IDLE);
  assert.deepEqual(kinds(events, 'transcript'), [{ type: 'transcript', text: 'Ich war müde.', detectedLanguage: 'DE' }]);
  assert.equal(stream.tracks[0].stopped, true, 'microphone released');
});

test('pressing record claims the guard before the microphone permission is answered', async () => {
  let grant;
  const stream = new FakeStream();
  const { recorder, guard } = setup({ getUserMedia: () => new Promise((resolve) => { grant = () => resolve(stream); }) });
  const started = recorder.start();
  assert.equal(recorder.state, AnswerState.REQUESTING);
  assert.equal(guard.isRecording(), true);
  grant();
  await started;
  assert.equal(recorder.state, AnswerState.RECORDING);
});

test('denied microphone: calm error, back to idle, guard released', async () => {
  const { recorder, guard, events } = setup({ getUserMedia: async () => { throw new Error('NotAllowedError'); } });
  await recorder.start();
  assert.equal(recorder.state, AnswerState.IDLE);
  assert.equal(guard.isRecording(), false);
  assert.deepEqual(kinds(events, 'error'), [{ type: 'error', kind: 'mic-denied' }]);
});

test('no MediaRecorder support: unsupported, guard untouched', async () => {
  const guard = createRecordingGuard();
  const events = [];
  const recorder = createAnswerRecorder({
    mediaDevices: undefined, MediaRecorder: undefined, guard, transcribe: async () => ({}),
    now: () => 0, setTimer: () => 1, clearTimer: () => {}, onEvent: (e) => events.push(e),
  });
  await recorder.start();
  assert.equal(guard.isRecording(), false);
  assert.deepEqual(kinds(events, 'error'), [{ type: 'error', kind: 'unsupported' }]);
});

test('hard limit: auto-stops after the maximum duration', async () => {
  const { recorder, timers, clock } = setup();
  await recorder.start();
  const [{ ms }] = [...timers.values()];
  assert.equal(ms, MAX_ANSWER_MS);
  clock.t += 5000;
  assert.equal(recorder.elapsedMs(), 5000);
  clock.t += 500_000;
  assert.equal(recorder.elapsedMs(), MAX_ANSWER_MS, 'timer display is capped');
});

test('limit timer firing stops the recording and transcribes', async () => {
  const { recorder, timers, events } = setup();
  await recorder.start();
  [...timers.values()][0].fn();
  await tick();
  assert.equal(kinds(events, 'transcript').length, 1);
});

test('stop clears the limit timer', async () => {
  const { recorder, timers } = setup();
  await recorder.start();
  assert.equal(timers.size, 1);
  recorder.stop();
  assert.equal(timers.size, 0);
});

test('cancel while recording discards the clip: no transcription, guard released', async () => {
  let called = 0;
  const { recorder, guard, events, stream } = setup({ transcribe: async () => { called += 1; return { text: 'x' }; } });
  await recorder.start();
  recorder.cancel();
  await tick();
  assert.equal(recorder.state, AnswerState.IDLE);
  assert.equal(guard.isRecording(), false);
  assert.equal(called, 0);
  assert.equal(stream.tracks[0].stopped, true);
  assert.equal(kinds(events, 'transcript').length, 0);
});

test('cancel while transcribing ignores the late result', async () => {
  let resolve;
  const { recorder, events } = setup({ transcribe: () => new Promise((r) => { resolve = r; }) });
  await recorder.start();
  recorder.stop();
  await tick();
  assert.equal(recorder.state, AnswerState.TRANSCRIBING);
  recorder.cancel();
  resolve({ text: 'too late' });
  await tick();
  assert.equal(kinds(events, 'transcript').length, 0);
  assert.equal(recorder.state, AnswerState.IDLE);
});

test('cancel while the permission prompt is open releases the stream when it arrives', async () => {
  let grant;
  const stream = new FakeStream();
  const { recorder, guard } = setup({ getUserMedia: () => new Promise((resolve) => { grant = () => resolve(stream); }) });
  const started = recorder.start();
  recorder.cancel();
  grant();
  await started;
  assert.equal(recorder.state, AnswerState.IDLE);
  assert.equal(guard.isRecording(), false);
  assert.equal(stream.tracks[0].stopped, true);
  assert.equal(FakeMediaRecorder.instances.length, 0);
});

test('503 and 501 map to unavailable, 413 to too-large, others to failed', () => {
  assert.equal(classifyTranscribeError({ status: 503 }), 'unavailable');
  assert.equal(classifyTranscribeError({ status: 501 }), 'unavailable');
  assert.equal(classifyTranscribeError({ status: 413 }), 'too-large');
  assert.equal(classifyTranscribeError({ status: 400 }), 'failed');
  assert.equal(classifyTranscribeError(new TypeError('network')), 'unavailable');
});

test('transcribe failure reports the kind and returns to idle (typing stays possible)', async () => {
  const { recorder, events } = setup({ transcribe: async () => { throw Object.assign(new Error('x'), { status: 503 }); } });
  await recorder.start();
  recorder.stop();
  await tick();
  assert.equal(recorder.state, AnswerState.IDLE);
  assert.deepEqual(kinds(events, 'error'), [{ type: 'error', kind: 'unavailable' }]);
});

test('empty transcript is reported as empty, not delivered', async () => {
  const { recorder, events } = setup({ transcribe: async () => ({ text: '   ' }) });
  await recorder.start();
  recorder.stop();
  await tick();
  assert.deepEqual(kinds(events, 'error'), [{ type: 'error', kind: 'empty' }]);
  assert.equal(kinds(events, 'transcript').length, 0);
});

test('start is ignored unless idle', async () => {
  const { recorder } = setup();
  await recorder.start();
  await recorder.start();
  assert.equal(FakeMediaRecorder.instances.length, 1);
});

test('mime type preference and fallback', () => {
  FakeMediaRecorder.types = new Set(['audio/webm', 'audio/mp4']);
  assert.equal(pickMimeType(FakeMediaRecorder), 'audio/webm');
  FakeMediaRecorder.types = new Set(['audio/mp4']);
  assert.equal(pickMimeType(FakeMediaRecorder), 'audio/mp4');
  FakeMediaRecorder.types = new Set();
  assert.equal(pickMimeType(FakeMediaRecorder), '');
  assert.equal(pickMimeType(undefined), '');
  FakeMediaRecorder.types = new Set(['audio/webm']);
});
