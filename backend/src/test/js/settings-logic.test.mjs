import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseIntervals, formatIntervals, partialSettings, isEmptyPartial, phraseMatches, formatResult,
} from '../../main/resources/META-INF/resources/js/settings-logic.js';

test('accepts ascending intervals in range, separated by commas or spaces', () => {
  assert.deepEqual(parseIntervals('1, 3, 7, 14, 30'), { values: [1, 3, 7, 14, 30], errorKey: null });
  assert.deepEqual(parseIntervals('1 2 365').values, [1, 2, 365]);
  assert.equal(formatIntervals([1, 3, 7]), '1, 3, 7');
  assert.equal(formatIntervals(null), '');
});

test('rejects invalid intervals with the same codes as the server', () => {
  const key = (text) => parseIntervals(text).errorKey;
  assert.equal(key(''), 'empty');
  assert.equal(key(null), 'empty');
  assert.equal(key(' , '), 'empty');
  assert.equal(key('1, x'), 'format');
  assert.equal(key('1.5'), 'format');
  assert.equal(key('0, 3'), 'range');
  assert.equal(key('1, 366'), 'range');
  assert.equal(key('3, 3'), 'order');
  assert.equal(key('7, 3'), 'order');
  assert.equal(key('1,2,3,4,5,6,7,8,9,10,11'), 'count');
});

test('the partial settings carry only what changed', () => {
  const current = { uiLanguage: 'AUTO', defaultEntryLanguage: 'DE', keepAudio: true, ttsAutoPlay: false, reviewIntervalsDays: [1, 3, 7] };
  assert.ok(isEmptyPartial(partialSettings(current, { ...current })));
  const partial = partialSettings(current, { ...current, uiLanguage: 'EN', keepAudio: false, reviewIntervalsDays: [1, 3, 7, 14] });
  assert.deepEqual(partial, { uiLanguage: 'EN', keepAudio: false, reviewIntervalsDays: [1, 3, 7, 14] });
  assert.equal(isEmptyPartial(partial), false);
});

test('delete is enabled only for the exact phrase', () => {
  assert.equal(phraseMatches('DELETE EVERYTHING', 'DELETE EVERYTHING'), true);
  assert.equal(phraseMatches('delete everything', 'DELETE EVERYTHING'), false);
  assert.equal(phraseMatches('DELETE EVERYTHING ', 'DELETE EVERYTHING'), false);
  assert.equal(phraseMatches('', 'DELETE EVERYTHING'), false);
  assert.equal(phraseMatches(null, 'DELETE EVERYTHING'), false);
});

test('the delete result line fills store and count', () => {
  assert.equal(formatResult('{0}: {1} removed', 'entries', 3), 'entries: 3 removed');
});
