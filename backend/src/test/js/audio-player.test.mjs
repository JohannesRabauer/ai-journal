import { test } from 'node:test';
import assert from 'node:assert/strict';

// The module defines a custom element at import time; give it a minimal browser stand-in.
globalThis.HTMLElement = class {};
globalThis.customElements = { get: () => undefined, define: () => {} };
const { formatTime } = await import('../../main/resources/META-INF/resources/js/audio-player.js');

test('formats minutes and seconds', () => {
  assert.equal(formatTime(0), '0:00');
  assert.equal(formatTime(64.9), '1:04');
  assert.equal(formatTime(192), '3:12');
});

test('formats hours', () => {
  assert.equal(formatTime(3725), '1:02:05');
});

test('invalid input shows zero', () => {
  assert.equal(formatTime(NaN), '0:00');
  assert.equal(formatTime(Infinity), '0:00');
  assert.equal(formatTime(-3), '0:00');
});
