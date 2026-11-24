import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildUrl, createLiveSearch, normalizeFilter, normalizeQuery, MAX_QUERY_CHARS,
} from '../../main/resources/META-INF/resources/js/journal-search.js';
import { fakeTimers } from './fakedom.mjs';

test('buildUrl leaves defaults out and encodes the text', () => {
  assert.equal(buildUrl({}), '/journal');
  assert.equal(buildUrl({ q: '  ', filter: 'all' }), '/journal');
  assert.equal(buildUrl({ q: 'a&b c', filter: 'voice' }), '/journal?q=a%26b+c&filter=voice');
  assert.equal(buildUrl({ filter: 'reflected', page: 2, fragment: true }), '/journal?filter=reflected&page=2&fragment=1');
  assert.equal(buildUrl({ filter: 'bogus' }), '/journal');
});

test('query is trimmed and capped', () => {
  assert.equal(normalizeQuery('  hi '), 'hi');
  assert.equal(normalizeQuery('x'.repeat(500)).length, MAX_QUERY_CHARS);
  assert.equal(normalizeQuery(null), '');
  assert.equal(normalizeFilter('VOICE'), 'voice');
});

function harness(initial = { q: '', filter: 'all' }) {
  const timers = fakeTimers();
  const state = { ...initial };
  const loads = [];
  const applied = [];
  const search = createLiveSearch({
    getState: () => state,
    load: async (s) => { loads.push(s); return 'r:' + s.q + ':' + s.filter; },
    apply: (r) => applied.push(r),
    initial,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
  });
  return { timers, state, loads, applied, search };
}

test('typing is debounced: one load after 300 ms of quiet', async () => {
  const h = harness();
  h.state.q = 'r'; h.search.schedule();
  await h.timers.tick(200);
  h.state.q = 'ri'; h.search.schedule();
  await h.timers.tick(200);
  h.state.q = 'riv'; h.search.schedule();
  await h.timers.tick(299);
  assert.equal(h.loads.length, 0);
  await h.timers.tick(2);
  assert.deepEqual(h.loads, [{ q: 'riv', filter: 'all' }]);
  assert.deepEqual(h.applied, ['r:riv:all']);
});

test('now() loads immediately and cancels the pending timer; unchanged state is not reloaded', async () => {
  const h = harness();
  h.state.filter = 'voice'; h.search.schedule();
  await h.search.now();
  assert.equal(h.loads.length, 1);
  await h.timers.tick(1000);
  assert.equal(h.loads.length, 1);
  await h.search.now();
  assert.equal(h.loads.length, 1);
  h.state.q = '  '; // whitespace only equals the empty query
  await h.search.now();
  assert.equal(h.loads.length, 1);
});

test('an outdated slow response never overwrites a newer one', async () => {
  const timers = fakeTimers();
  const state = { q: 'a', filter: 'all' };
  const pending = [];
  const applied = [];
  const search = createLiveSearch({
    getState: () => state,
    load: (s) => new Promise((resolve) => pending.push(() => resolve(s.q))),
    apply: (r) => applied.push(r),
    setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout,
  });
  const first = search.now();
  state.q = 'ab';
  const second = search.now();
  pending[1]();
  await second;
  pending[0]();
  await first;
  assert.deepEqual(applied, ['ab']);
});

test('errors are reported and the state stays unapplied so the next try reloads', async () => {
  const timers = fakeTimers();
  const errors = [];
  let fail = true;
  const search = createLiveSearch({
    getState: () => ({ q: 'x', filter: 'all' }),
    load: async () => { if (fail) { throw new Error('boom'); } return 'ok'; },
    apply: () => {},
    onError: (e) => errors.push(e.message),
    setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout,
  });
  await search.now();
  fail = false;
  await search.now();
  assert.deepEqual(errors, ['boom']);
});
