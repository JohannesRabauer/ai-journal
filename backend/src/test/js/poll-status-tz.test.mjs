import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPoller } from '../../main/resources/META-INF/resources/js/poll.js';
import { createStatus } from '../../main/resources/META-INF/resources/js/status.js';
import { ensureTimeZoneCookie } from '../../main/resources/META-INF/resources/js/tz.js';
import { FakeDocument, fakeTimers } from './fakedom.mjs';

// ---- poll ----

function poller(task, extra = {}) {
  const timers = fakeTimers();
  const results = [];
  const errors = [];
  const p = createPoller({
    task, intervalMs: 2000, baseMs: 100, maxMs: 1000, random: () => 0.999999,
    onResult: (r) => results.push(r), onError: (e, n) => errors.push(n),
    setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout, ...extra,
  });
  return { p, timers, results, errors };
}

test('polls at the interval until done', async () => {
  let n = 0;
  const { p, timers, results } = poller(async () => ({ n: ++n }), { isDone: (r) => r.n === 3 });
  p.start();
  await timers.tick(0);
  assert.equal(results.length, 1);
  await timers.tick(1999);
  assert.equal(results.length, 1);
  await timers.tick(1);
  assert.equal(results.length, 2);
  await timers.tick(2000);
  assert.deepEqual(results.map((r) => r.n), [1, 2, 3]);
  assert.equal(p.running, false);
  assert.equal(timers.pending, 0);
});

test('failures back off exponentially and a success resets', async () => {
  let n = 0;
  const { p, timers, results, errors } = poller(async () => {
    n += 1;
    if (n <= 3) throw new Error('down');
    return 'ok';
  });
  p.start();
  await timers.tick(0);          // poll 1 fails, next in 99 ms
  assert.deepEqual(errors, [0]);
  await timers.tick(98);
  assert.equal(n, 1);
  await timers.tick(1);          // poll 2 fails, next in 199 ms
  assert.equal(n, 2);
  await timers.tick(198);
  assert.equal(n, 2);
  await timers.tick(1);          // poll 3 fails, next in 399 ms
  assert.equal(n, 3);
  await timers.tick(399);        // poll 4 succeeds
  assert.deepEqual(results, ['ok']);
  assert.deepEqual(errors, [0, 1, 2]);
});

test('stop cancels the next poll; maxPolls ends quietly', async () => {
  const { p, timers, results } = poller(async () => 1);
  p.start();
  await timers.tick(0);
  p.stop();
  await timers.tick(10000);
  assert.equal(results.length, 1);
  assert.equal(timers.pending, 0);

  const limited = poller(async () => 1, { maxPolls: 2 });
  limited.p.start();
  await limited.timers.tick(10000);
  assert.equal(limited.results.length, 2);
  assert.equal(limited.p.running, false);
});

// ---- status ----

function banner(doc) {
  const el = doc.createElement('div');
  el.hidden = true;
  el.setAttribute('data-text', 'The diary service is not reachable right now.');
  const live = doc.createElement('span');
  live.className = 'diary-banner-text';
  const retry = doc.createElement('button');
  retry.setAttribute('data-action', 'retry');
  el.appendChild(live);
  el.appendChild(retry);
  return { el, live, retry };
}

test('shows the offline notice when the service does not answer, and hides it when it is back', async () => {
  const doc = new FakeDocument();
  const { el, live, retry } = banner(doc);
  const nav = doc.createElement('div');
  nav.setAttribute('data-kind', 'local');
  nav.setAttribute('data-offline-text', 'Diary service not reachable.');
  const label = doc.createElement('span');
  label.textContent = 'Local. Nothing leaves this device.';
  nav.appendChild(label);
  let up = false;
  const status = createStatus({ banner: el, navStatus: nav, fetchInfo: async () => { if (!up) throw new Error('x'); return { profile: 'full' }; } });

  assert.equal(await status.check(), false);
  assert.equal(el.hidden, false);
  assert.equal(live.textContent, 'The diary service is not reachable right now.');
  assert.equal(nav.getAttribute('data-kind'), 'offline');
  assert.equal(label.textContent, 'Diary service not reachable.');

  up = true;
  retry.click();
  await new Promise((r) => setImmediate(r));
  assert.equal(el.hidden, true);
  assert.equal(live.textContent, '');
  assert.equal(nav.getAttribute('data-kind'), 'local');
  assert.equal(label.textContent, 'Local. Nothing leaves this device.');
});

test('a page without the banner does nothing', async () => {
  assert.equal(await createStatus({ banner: null }).check(), true);
});

// ---- time zone ----

test('writes the zone cookie once and not again while it matches', () => {
  const doc = new FakeDocument();
  const store = { value: '' };
  Object.defineProperty(doc, 'cookie', {
    get: () => store.value,
    set: (v) => { store.value = v.split(';')[0]; },
  });
  const intl = { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: 'Europe/Berlin' }) }) };
  assert.equal(ensureTimeZoneCookie({ document: doc, intl }), true);
  assert.equal(store.value, 'diary-tz=Europe%2FBerlin');
  assert.equal(ensureTimeZoneCookie({ document: doc, intl }), false);
  const moved = { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: 'America/New_York' }) }) };
  assert.equal(ensureTimeZoneCookie({ document: doc, intl: moved }), true);
});

test('an unusable zone is not sent', () => {
  const doc = new FakeDocument();
  const bad = { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: 'x;y' }) }) };
  assert.equal(ensureTimeZoneCookie({ document: doc, intl: bad }), false);
  assert.equal(doc.cookie, '');
  const none = { DateTimeFormat: () => { throw new Error('no intl'); } };
  assert.equal(ensureTimeZoneCookie({ document: doc, intl: none }), false);
});
