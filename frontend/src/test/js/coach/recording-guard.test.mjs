import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRecordingGuard, watchDiaryRecorders } from '../../../main/frontend/coach/recording-guard.js';

class FakeRecorderElement {
  localName = 'diary-recorder';
  attrs = {};
  constructor(state) {
    if (state) {
      this.attrs['data-state'] = state;
    }
  }
  getAttribute(name) {
    return this.attrs[name] ?? null;
  }
  querySelectorAll() {
    return [];
  }
}

function fakeRoot(recorders = []) {
  const handlers = new Map();
  return {
    recorders,
    addEventListener: (name, fn) => handlers.set(name, fn),
    removeEventListener: (name) => handlers.delete(name),
    querySelectorAll: () => recorders,
    emit: (name, target) => handlers.get(name)?.({ target }),
    handlers,
  };
}

class FakeObserver {
  static last;
  constructor(callback) {
    this.callback = callback;
    this.disconnected = false;
    FakeObserver.last = this;
  }
  observe(target, options) {
    this.options = options;
  }
  disconnect() {
    this.disconnected = true;
  }
}

test('guard is idle by default and reflects any active source', () => {
  const guard = createRecordingGuard();
  assert.equal(guard.isRecording(), false);
  guard.setActive('a', true);
  guard.setActive('b', true);
  assert.equal(guard.isRecording(), true);
  guard.setActive('a', false);
  assert.equal(guard.isRecording(), true, 'one recording is still active');
  guard.setActive('b', false);
  assert.equal(guard.isRecording(), false);
});

test('onChange fires only on first start and last stop', () => {
  const guard = createRecordingGuard();
  const seen = [];
  guard.onChange((recording) => seen.push(recording));
  guard.setActive('a', true);
  guard.setActive('a', true);
  guard.setActive('b', true);
  guard.setActive('a', false);
  guard.setActive('b', false);
  assert.deepEqual(seen, [true, false]);
});

test('whenIdle runs immediately when idle', () => {
  const guard = createRecordingGuard();
  let ran = 0;
  assert.equal(guard.whenIdle('k', () => { ran += 1; }), true);
  assert.equal(ran, 1);
});

test('whenIdle waits for recording to stop and keeps only the latest job per key', () => {
  const guard = createRecordingGuard();
  const log = [];
  guard.setActive('a', true);
  assert.equal(guard.whenIdle('announce', () => log.push('first')), false);
  guard.whenIdle('announce', () => log.push('second'));
  guard.whenIdle('focus', () => log.push('focus'));
  assert.deepEqual(log, []);
  guard.setActive('a', false);
  assert.deepEqual(log, ['second', 'focus']);
  guard.setActive('a', true);
  guard.setActive('a', false);
  assert.deepEqual(log, ['second', 'focus'], 'jobs run once');
});

test('cancelDeferred drops a pending job', () => {
  const guard = createRecordingGuard();
  const log = [];
  guard.setActive('a', true);
  guard.whenIdle('x', () => log.push('x'));
  guard.cancelDeferred('x');
  guard.setActive('a', false);
  assert.deepEqual(log, []);
});

test('watcher: recorder in recording or paused state activates the guard, picked up on start', () => {
  const guard = createRecordingGuard();
  const el = new FakeRecorderElement('paused');
  watchDiaryRecorders({ guard, root: fakeRoot([el]), MutationObserverImpl: FakeObserver });
  assert.equal(guard.isRecording(), true);
});

test('watcher: data-state changes toggle the guard', () => {
  const guard = createRecordingGuard();
  const el = new FakeRecorderElement('idle');
  watchDiaryRecorders({ guard, root: fakeRoot([el]), MutationObserverImpl: FakeObserver });
  assert.equal(guard.isRecording(), false);
  assert.deepEqual(FakeObserver.last.options.attributeFilter, ['data-state']);
  for (const [state, expected] of [
    ['requesting-permission', true], ['recording', true], ['paused', true], ['recording', true],
    ['stopping', false], ['uploading', false], ['done', false], ['error', false],
  ]) {
    el.attrs['data-state'] = state;
    FakeObserver.last.callback([{ type: 'attributes', target: el }]);
    assert.equal(guard.isRecording(), expected, state);
  }
});

test('watcher: recording-started event activates, completed event re-reads the state', () => {
  const guard = createRecordingGuard();
  const el = new FakeRecorderElement('idle');
  const root = fakeRoot([]);
  watchDiaryRecorders({ guard, root, MutationObserverImpl: FakeObserver });
  root.emit('recording-started', el);
  assert.equal(guard.isRecording(), true);
  el.attrs['data-state'] = 'uploading';
  root.emit('recording-completed', el);
  assert.equal(guard.isRecording(), false);
});

test('watcher: events from other elements are ignored', () => {
  const guard = createRecordingGuard();
  const root = fakeRoot([]);
  watchDiaryRecorders({ guard, root, MutationObserverImpl: FakeObserver });
  root.emit('recording-started', { localName: 'div' });
  assert.equal(guard.isRecording(), false);
});

test('watcher: removing a recording element clears it, adding one re-evaluates', () => {
  const guard = createRecordingGuard();
  const el = new FakeRecorderElement('recording');
  watchDiaryRecorders({ guard, root: fakeRoot([el]), MutationObserverImpl: FakeObserver });
  assert.equal(guard.isRecording(), true);
  FakeObserver.last.callback([{ type: 'childList', removedNodes: [el], addedNodes: [] }]);
  assert.equal(guard.isRecording(), false);
  FakeObserver.last.callback([{ type: 'childList', removedNodes: [], addedNodes: [el] }]);
  assert.equal(guard.isRecording(), true);
});

test('watcher: two recorders, one stopping keeps the guard active; stop watching disconnects', () => {
  const guard = createRecordingGuard();
  const a = new FakeRecorderElement('recording');
  const b = new FakeRecorderElement('recording');
  const root = fakeRoot([a, b]);
  const stop = watchDiaryRecorders({ guard, root, MutationObserverImpl: FakeObserver });
  a.attrs['data-state'] = 'done';
  FakeObserver.last.callback([{ type: 'attributes', target: a }]);
  assert.equal(guard.isRecording(), true);
  stop();
  assert.equal(FakeObserver.last.disconnected, true);
  assert.equal(root.handlers.size, 0);
});
