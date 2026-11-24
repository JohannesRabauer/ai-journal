import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNotifier } from '../../main/resources/META-INF/resources/js/notify.js';
import { confirmDialog } from '../../main/resources/META-INF/resources/js/confirm.js';
import { FakeDocument, fakeTimers } from './fakedom.mjs';

test('notify adds a message to the live region and removes it after the timeout', async () => {
  const doc = new FakeDocument();
  const region = doc.createElement('div');
  const timers = fakeTimers();
  const notify = createNotifier({ region, document: doc, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout });
  notify('Saved.');
  assert.equal(region.children.length, 1);
  assert.equal(region.children[0].textContent, 'Saved.');
  assert.equal(region.children[0].getAttribute('data-kind'), 'info');
  await timers.tick(4999);
  assert.equal(region.children.length, 1);
  await timers.tick(2);
  assert.equal(region.children.length, 0);
});

test('errors stay longer, empty text is ignored, dismiss removes at once', async () => {
  const doc = new FakeDocument();
  const region = doc.createElement('div');
  const timers = fakeTimers();
  const notify = createNotifier({ region, document: doc, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout });
  assert.equal(notify(''), null);
  const handle = notify('Problem', { kind: 'error' });
  assert.equal(region.children[0].getAttribute('data-kind'), 'error');
  await timers.tick(9000);
  assert.equal(region.children.length, 1);
  handle.dismiss();
  assert.equal(region.children.length, 0);
});

test('text is set as text, never as markup', () => {
  const doc = new FakeDocument();
  const region = doc.createElement('div');
  createNotifier({ region, document: doc })('<img src=x onerror=alert(1)>', { timeoutMs: 0 });
  assert.equal(region.children[0].textContent, '<img src=x onerror=alert(1)>');
});

const TEXTS = { title: 'Delete entry?', message: 'This cannot be undone.', confirmLabel: 'Delete', cancelLabel: 'Keep', destructive: true };

test('confirming resolves true, removes the dialog and returns focus to the opener', async () => {
  const doc = new FakeDocument();
  const opener = doc.createElement('button');
  doc.body.appendChild(opener);
  opener.focus();
  const result = confirmDialog(TEXTS, { document: doc });
  const dialog = doc.body.children.find((c) => c.tagName === 'DIALOG');
  assert.ok(dialog.open);
  assert.equal(dialog.getAttribute('aria-labelledby'), dialog.children[0].id);
  assert.equal(dialog.getAttribute('aria-describedby'), dialog.children[1].id);
  dialog.querySelector('[data-action="confirm"]').click();
  assert.equal(await result, true);
  assert.ok(!doc.body.children.includes(dialog));
  assert.equal(doc.activeElement, opener);
});

test('cancel button and Escape resolve false', async () => {
  const doc = new FakeDocument();
  const first = confirmDialog(TEXTS, { document: doc });
  doc.body.children.find((c) => c.tagName === 'DIALOG').querySelector('[data-action="cancel"]').click();
  assert.equal(await first, false);

  const second = confirmDialog(TEXTS, { document: doc });
  doc.body.children.find((c) => c.tagName === 'DIALOG').escape();
  assert.equal(await second, false);
});

test('a destructive dialog starts on cancel and styles the confirm button as danger', () => {
  const doc = new FakeDocument();
  confirmDialog(TEXTS, { document: doc });
  const dialog = doc.body.children.find((c) => c.tagName === 'DIALOG');
  const cancel = dialog.querySelector('[data-action="cancel"]');
  const ok = dialog.querySelector('[data-action="confirm"]');
  assert.ok(cancel.focused && !ok.focused);
  assert.match(ok.className, /diary-button-danger/);
  assert.equal(ok.textContent, 'Delete');
  assert.equal(cancel.textContent, 'Keep');
});

test('a plain dialog starts on confirm and has no danger style', () => {
  const doc = new FakeDocument();
  confirmDialog({ title: 'Leave?', confirmLabel: 'Leave', cancelLabel: 'Stay' }, { document: doc });
  const dialog = doc.body.children.find((c) => c.tagName === 'DIALOG');
  const ok = dialog.querySelector('[data-action="confirm"]');
  assert.ok(ok.focused);
  assert.doesNotMatch(ok.className, /danger/);
  assert.equal(dialog.getAttribute('aria-describedby'), null);
});
