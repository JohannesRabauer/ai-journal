import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readMode, recorderLanguage, saveMode } from '../../main/resources/META-INF/resources/js/today.js';

function memoryStore(initial = {}) {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = String(v); },
    data,
  };
}

test('the composer mode defaults to speak and remembers write', () => {
  const store = memoryStore();
  assert.equal(readMode(store), 'speak');
  saveMode('write', store);
  assert.equal(store.data['diary.today.mode'], 'write');
  assert.equal(readMode(store), 'write');
  saveMode('speak', store);
  assert.equal(readMode(store), 'speak');
});

test('a blocked localStorage never breaks the page', () => {
  const blocked = {
    getItem: () => { throw new Error('denied'); },
    setItem: () => { throw new Error('denied'); },
  };
  assert.equal(readMode(blocked), 'speak');
  assert.doesNotThrow(() => saveMode('write', blocked));
  assert.equal(readMode(null), 'speak');
});

test('the recorder gets the language attribute in lower case, auto by default', () => {
  assert.equal(recorderLanguage('AUTO'), 'auto');
  assert.equal(recorderLanguage('DE'), 'de');
  assert.equal(recorderLanguage('EN'), 'en');
  assert.equal(recorderLanguage(''), 'auto');
});
