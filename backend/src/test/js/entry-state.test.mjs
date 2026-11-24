import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  INTERVAL_MS, MAX_POLLS, apiLanguage, decideTranscription, editorOperations, entryActions, isInProgress, latestJob,
  watchTranscription,
} from '../../main/resources/META-INF/resources/js/entry-state.js';
import { ApiError } from '../../main/resources/META-INF/resources/js/api.js';
import { fakeTimers } from './fakedom.mjs';

// ---- the cases of the Vaadin EntryActionsTest ----

test('a draft with text can be edited and finished', () => {
  const a = entryActions('DRAFT', false, true, false);
  assert.equal(a.editText, true);
  assert.equal(a.finish, true);
  assert.equal(a.transcribing, false);
  assert.equal(a.deleteAudio, false);
  assert.equal(a.deleteEntry, true);
});

test('an empty draft cannot be finished', () => {
  assert.equal(entryActions('DRAFT', false, false, false).finish, false);
});

test('recorded and transcribing show only progress', () => {
  for (const s of ['RECORDED', 'TRANSCRIBING']) {
    const a = entryActions(s, true, false, false);
    assert.equal(a.transcribing, true, s);
    assert.equal(a.editText, false);
    assert.equal(a.finish, false);
    assert.equal(a.retryTranscription, false);
    assert.equal(a.deleteAudio, false);
    assert.equal(a.deleteEntry, false);
  }
});

test('a transcript is reviewed, edited and finished; the audio may be deleted', () => {
  const a = entryActions('TRANSCRIBED', true, true, false);
  assert.equal(a.editText, true);
  assert.equal(a.finish, true);
  assert.equal(a.deleteAudio, true);
  assert.equal(a.retryTranscription, false);
});

test('a failed transcription offers retry and typing, keeping the audio', () => {
  const a = entryActions('TRANSCRIPTION_FAILED', true, false, false);
  assert.equal(a.retryTranscription, true);
  assert.equal(a.typeManually, true);
  assert.equal(a.editText, false);
  assert.equal(a.finish, false);
  assert.equal(a.deleteAudio, true);
});

test('after choosing to type yourself the entry can be finished once text exists', () => {
  const empty = entryActions('TRANSCRIPTION_FAILED', true, false, true);
  assert.equal(empty.editText, true);
  assert.equal(empty.typeManually, false);
  assert.equal(empty.finish, false);
  assert.equal(entryActions('TRANSCRIPTION_FAILED', true, true, true).finish, true);
});

test('a failed transcription without audio cannot be retried', () => {
  assert.equal(entryActions('TRANSCRIPTION_FAILED', false, false, false).retryTranscription, false);
});

test('finished entries are editable but not finishable again', () => {
  const a = entryActions('FINISHED', true, true, false);
  assert.equal(a.editText, true);
  assert.equal(a.finish, false);
  assert.equal(a.deleteAudio, true);
  assert.equal(a.deleteEntry, true);
});

test('an entry still being recorded has no edit or finish', () => {
  const a = entryActions('RECORDING', true, false, false);
  assert.equal(a.editText, false);
  assert.equal(a.finish, false);
  assert.equal(a.transcribing, false);
  assert.equal(a.deleteAudio, false);
});

// ---- the cases of the Vaadin TranscriptionPollingTest ----

const job = (status) => ({ status });

test('keeps polling while queued, running or without a job yet', () => {
  assert.equal(decideTranscription('RECORDED', null, 1), 'CONTINUE');
  assert.equal(decideTranscription('RECORDED', job('QUEUED'), 2), 'CONTINUE');
  assert.equal(decideTranscription('TRANSCRIBING', job('RUNNING'), 3), 'CONTINUE');
});

test('stops when the entry is transcribed or was finished meanwhile', () => {
  assert.equal(decideTranscription('TRANSCRIBED', job('SUCCEEDED'), 4), 'SUCCEEDED');
  assert.equal(decideTranscription('FINISHED', null, 1), 'SUCCEEDED');
});

test('stops on failure by entry status or job', () => {
  assert.equal(decideTranscription('TRANSCRIPTION_FAILED', null, 1), 'FAILED');
  assert.equal(decideTranscription('TRANSCRIBING', job('FAILED'), 1), 'FAILED');
});

test('a succeeded job before the entry switches polls once more', () => {
  assert.equal(decideTranscription('TRANSCRIBING', job('SUCCEEDED'), 5), 'CONTINUE');
});

test('gives up quietly after the maximum number of polls', () => {
  assert.equal(INTERVAL_MS, 2000);
  assert.equal(decideTranscription('TRANSCRIBING', job('RUNNING'), MAX_POLLS - 1), 'CONTINUE');
  assert.equal(decideTranscription('TRANSCRIBING', job('RUNNING'), MAX_POLLS), 'GIVE_UP');
});

test('in-progress statuses', () => {
  assert.equal(isInProgress('RECORDED'), true);
  assert.equal(isInProgress('TRANSCRIBING'), true);
  assert.equal(isInProgress('TRANSCRIBED'), false);
  assert.equal(isInProgress('DRAFT'), false);
});

// ---- the watcher ----

function watch(entries, jobs, extra = {}) {
  const timers = fakeTimers();
  const done = [];
  let gaveUp = 0;
  let i = 0;
  const poller = watchTranscription({
    entryId: 9,
    getEntry: async () => entries[Math.min(i++, entries.length - 1)],
    getJob: async () => jobs[Math.min(i - 1, jobs.length - 1)],
    onDone: (entry, decision) => done.push([entry.status, decision]),
    onGiveUp: () => { gaveUp += 1; },
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    random: () => 0.5,
    ...extra,
  });
  return { poller, timers, done, gave: () => gaveUp };
}

test('the watcher polls every two seconds and reports the transcript once', async () => {
  const { poller, timers, done } = watch(
    [{ status: 'TRANSCRIBING' }, { status: 'TRANSCRIBING' }, { status: 'TRANSCRIBED' }],
    [null, job('RUNNING'), job('SUCCEEDED')]);
  poller.start();
  await timers.tick(0);
  assert.deepEqual(done, []);
  await timers.tick(2000);
  assert.deepEqual(done, []);
  await timers.tick(2000);
  assert.deepEqual(done, [['TRANSCRIBED', 'SUCCEEDED']]);
  assert.equal(poller.running, false);
  await timers.tick(10000);
  assert.equal(done.length, 1);
});

test('the watcher reports a failed transcription', async () => {
  const { poller, timers, done } = watch([{ status: 'TRANSCRIBING' }], [job('FAILED')]);
  poller.start();
  await timers.tick(0);
  assert.deepEqual(done, [['TRANSCRIBING', 'FAILED']]);
  assert.equal(poller.running, false);
});

test('the watcher survives a failed poll and goes on', async () => {
  let calls = 0;
  const { poller, timers, done } = watch([], [], {
    getEntry: async () => {
      calls += 1;
      if (calls === 1) {
        throw new ApiError('NETWORK', 'down');
      }
      return { status: 'TRANSCRIBED' };
    },
    getJob: async () => null,
  });
  poller.start();
  await timers.tick(0);
  assert.deepEqual(done, []);
  await timers.tick(5000);
  assert.deepEqual(done, [['TRANSCRIBED', 'SUCCEEDED']]);
});

test('the watcher gives up quietly after the maximum number of polls', async () => {
  const { poller, timers, done, gave } = watch([{ status: 'TRANSCRIBING' }], [job('RUNNING')], { intervalMs: 1 });
  poller.start();
  await timers.tick(MAX_POLLS * 2);
  assert.equal(gave(), 1);
  assert.deepEqual(done, []);
  assert.equal(poller.running, false);
});

test('latestJob treats 404 as "no job yet" and passes other errors on', async () => {
  assert.equal(await latestJob(1, { get: async () => { throw new ApiError('NOT_FOUND', 'x', 404); } }), null);
  assert.deepEqual(await latestJob(1, { get: async (p) => ({ path: p }) }), { path: '/api/entries/1/transcriptions/latest' });
  await assert.rejects(latestJob(1, { get: async () => { throw new ApiError('HTTP_500', 'x', 500); } }));
});

test('editor operations save with keepalive and the chosen language', async () => {
  const seen = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (path, init) => {
    seen.push([path, init.method, init.keepalive, init.body]);
    return { ok: true, status: 200, text: async () => '{"id":3}' };
  };
  try {
    const ops = editorOperations(() => 'DE');
    await ops.create('hello');
    await ops.update(3, 'hello again');
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.equal(seen[0][0], '/api/entries');
  assert.equal(seen[0][1], 'POST');
  assert.equal(seen[0][2], true);
  assert.equal(JSON.parse(seen[0][3]).language, 'DE');
  assert.equal(JSON.parse(seen[0][3]).source, 'TEXT');
  assert.equal(seen[1][0], '/api/entries/3');
  assert.equal(seen[1][1], 'PUT');
  assert.equal(seen[1][2], true);
});

test('the language of the select maps to the API value', () => {
  assert.equal(apiLanguage('AUTO'), null);
  assert.equal(apiLanguage('DE'), 'DE');
  assert.equal(apiLanguage('EN'), 'EN');
  assert.equal(apiLanguage(undefined), null);
});
