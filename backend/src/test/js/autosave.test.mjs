import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  AutosavePresenter, createAutosave, bindAutosave, entryOperations, localIsoNow, DEBOUNCE_MS,
} from '../../main/resources/META-INF/resources/js/autosave.js';
import { FakeDocument, fakeTimers } from './fakedom.mjs';

// ---- presenter: the cases of the Vaadin AutosavePresenterTest ----

test('nothing is created for blank text', () => {
  const p = AutosavePresenter.forNewEntry();
  assert.equal(p.decide(null), 'NONE');
  assert.equal(p.decide(''), 'NONE');
  assert.equal(p.decide('  \n '), 'NONE');
  assert.equal(p.entryId, null);
});

test('entry is created on first text, then updated only when changed', () => {
  const p = AutosavePresenter.forNewEntry();
  assert.equal(p.decide('H'), 'CREATE');
  p.saved(5, 'H');
  assert.equal(p.entryId, 5);
  assert.equal(p.decide('H'), 'NONE');
  assert.equal(p.decide('Hello'), 'UPDATE');
  p.saved(5, 'Hello');
  assert.equal(p.decide('Hello'), 'NONE');
});

test('a failed save keeps deciding the same until it succeeds', () => {
  const p = AutosavePresenter.forNewEntry();
  assert.equal(p.decide('a'), 'CREATE');
  assert.equal(p.decide('ab'), 'CREATE');
  p.saved(1, 'ab');
  assert.equal(p.decide('abc'), 'UPDATE');
  assert.ok(p.isDirty('abc'));
});

test('clearing the text of an existing entry is saved', () => {
  assert.equal(AutosavePresenter.forExisting(3, 'text').decide(''), 'UPDATE');
});

test('an existing entry starts clean', () => {
  const p = AutosavePresenter.forExisting(3, 'text');
  assert.equal(p.decide('text'), 'NONE');
  assert.equal(p.decide('text!'), 'UPDATE');
});

test('reset starts a new entry', () => {
  const p = AutosavePresenter.forExisting(3, 'text');
  p.reset();
  assert.equal(p.entryId, null);
  assert.equal(p.decide('new'), 'CREATE');
});

// ---- controller ----

function setup({ failures = 0, text = '' } = {}) {
  const timers = fakeTimers();
  const calls = [];
  const states = [];
  const saved = [];
  let remaining = failures;
  const state = { text };
  const presenter = AutosavePresenter.forNewEntry();
  const auto = createAutosave({
    presenter,
    getText: () => state.text,
    create: async (t) => { calls.push(['create', t]); if (remaining-- > 0) throw new Error('down'); return { id: 7 }; },
    update: async (id, t) => { calls.push(['update', id, t]); if (remaining-- > 0) throw new Error('down'); return { id }; },
    onState: (s) => states.push(s),
    onSaved: (e) => saved.push(e.id),
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
  });
  return { auto, timers, calls, states, saved, state };
}

test('typing is debounced: one save after 2 seconds of quiet', async () => {
  const { auto, timers, calls, state, states, saved } = setup();
  assert.equal(DEBOUNCE_MS, 2000);
  state.text = 'a';
  auto.changed();
  await timers.tick(1500);
  state.text = 'ab';
  auto.changed();
  await timers.tick(1999);
  assert.deepEqual(calls, []);
  await timers.tick(2);
  assert.deepEqual(calls, [['create', 'ab']]);
  assert.deepEqual(states, ['saving', 'saved']);
  assert.deepEqual(saved, [7]);
  state.text = 'abc';
  auto.changed();
  await timers.tick(2000);
  assert.deepEqual(calls[1], ['update', 7, 'abc']);
});

test('save() flushes at once and blank text saves nothing', async () => {
  const { auto, calls, state, states } = setup();
  assert.equal(await auto.save(), true);
  assert.deepEqual(calls, []);
  assert.deepEqual(states, []);
  state.text = 'now';
  assert.equal(await auto.save(), true);
  assert.deepEqual(calls, [['create', 'now']]);
});

test('a failed save says failed, keeps the text, and the next trigger retries', async () => {
  const { auto, calls, state, states } = setup({ failures: 1 });
  state.text = 'keep me';
  assert.equal(await auto.save(), false);
  assert.equal(auto.state, 'failed');
  assert.deepEqual(states, ['saving', 'failed']);
  assert.equal(await auto.retry(), true);
  assert.deepEqual(calls, [['create', 'keep me'], ['create', 'keep me']]);
  assert.equal(auto.state, 'saved');
});

test('text typed while a save is running is saved right after', async () => {
  const timers = fakeTimers();
  const calls = [];
  let release;
  const state = { text: 'one' };
  const auto = createAutosave({
    presenter: AutosavePresenter.forNewEntry(),
    getText: () => state.text,
    create: (t) => { calls.push(['create', t]); return new Promise((r) => { release = () => r({ id: 1 }); }); },
    update: async (id, t) => { calls.push(['update', id, t]); return { id }; },
    setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout,
  });
  const first = auto.save();
  state.text = 'one two';
  const second = auto.save();
  release();
  await Promise.all([first, second]);
  assert.deepEqual(calls, [['create', 'one'], ['update', 1, 'one two']]);
});

test('entry operations call the API with the local time and no language change on update', async () => {
  const seen = [];
  const ops = entryOperations({
    now: () => '2031-03-05T08:09:10', language: () => null,
    post: async (...a) => { seen.push(['post', ...a]); return { id: 1 }; },
    put: async (...a) => { seen.push(['put', ...a]); return { id: 1 }; },
  });
  await ops.create('hi');
  await ops.update(1, 'hi!');
  assert.deepEqual(seen, [
    ['post', '/api/entries', { text: 'hi', entryTimestamp: '2031-03-05T08:09:10', language: null, source: 'TEXT' }],
    ['put', '/api/entries/1', { text: 'hi!', language: null }],
  ]);
  assert.equal(localIsoNow(new Date(2031, 2, 5, 8, 9, 10)), '2031-03-05T08:09:10');
});

// ---- DOM binding ----

test('binding shows saving, saved and failed with a retry button, texts from data attributes', async () => {
  const doc = new FakeDocument();
  const win = new FakeDocument();
  const textarea = doc.createElement('textarea');
  const status = doc.createElement('div');
  status.setAttribute('data-saving', 'Saving');
  status.setAttribute('data-saved', 'Saved.');
  status.setAttribute('data-failed', 'Could not save just now.');
  status.setAttribute('data-retry', 'Try saving again');
  let fail = true;
  const auto = bindAutosave({
    textarea, status, presenter: AutosavePresenter.forNewEntry(), document: doc, window: win,
    ops: { create: async () => { if (fail) throw new Error('x'); return { id: 2 }; }, update: async (id) => ({ id }) },
  });
  const text = status.querySelector('.diary-autosave__text');
  const retry = status.querySelector('button');
  assert.equal(retry.hidden, true);

  textarea.value = 'hello';
  textarea.dispatch('blur');
  await new Promise((r) => setImmediate(r));
  assert.equal(status.getAttribute('data-state'), 'failed');
  assert.equal(text.textContent, 'Could not save just now.');
  assert.equal(retry.hidden, false);
  assert.equal(retry.textContent, 'Try saving again');

  fail = false;
  retry.click();
  await new Promise((r) => setImmediate(r));
  assert.equal(status.getAttribute('data-state'), 'saved');
  assert.equal(text.textContent, 'Saved.');
  assert.equal(retry.hidden, true);
  assert.equal(auto.presenter.entryId, 2);
});

test('hiding the page saves what is unsaved', async () => {
  const doc = new FakeDocument();
  const win = new FakeDocument();
  const textarea = doc.createElement('textarea');
  const status = doc.createElement('div');
  const calls = [];
  bindAutosave({
    textarea, status, presenter: AutosavePresenter.forNewEntry(), document: doc, window: win,
    ops: { create: async (t) => { calls.push(t); return { id: 1 }; }, update: async (id) => ({ id }) },
  });
  textarea.value = 'unsaved';
  doc.visibilityState = 'hidden';
  doc.dispatch('visibilitychange');
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(calls, ['unsaved']);
});
