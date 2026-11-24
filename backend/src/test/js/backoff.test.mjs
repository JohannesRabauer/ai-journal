import { test } from 'node:test';
import assert from 'node:assert/strict';
import { retryDelayMs } from '../../main/resources/META-INF/resources/js/lib/backoff.js';

test('grows exponentially up to the cap', () => {
  const max = () => 0.999999;
  assert.equal(retryDelayMs(0, { baseMs: 100, random: max }), 99);
  assert.equal(retryDelayMs(3, { baseMs: 100, random: max }), 799);
  assert.equal(retryDelayMs(20, { baseMs: 100, maxMs: 5000, random: max }), 4999);
});

test('full jitter can be zero', () => {
  assert.equal(retryDelayMs(5, { random: () => 0 }), 0);
});

test('rejects invalid attempts', () => {
  assert.throws(() => retryDelayMs(-1), RangeError);
  assert.throws(() => retryDelayMs(1.5), RangeError);
});
