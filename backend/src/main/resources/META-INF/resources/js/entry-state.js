// Pure state rules of an entry and the transcription watcher, shared by the Today page and the entry page.
// Ported from the Vaadin EntryActions and TranscriptionPolling (same cases, same tests); the Java twin for the
// server-rendered state is web/ui/EntryActions. No DOM in this file.
import { get, post, put } from './api.js';
import { entryOperations } from './autosave.js';
import { createPoller } from './poll.js';

export const INTERVAL_MS = 2000;
/** About 30 minutes; long recordings can take a while. After that we stop quietly, the entry is not affected. */
export const MAX_POLLS = 900;

/**
 * Which actions an entry offers in its current state (SPEC 4.3).
 * @param {string} status EntryStatus name
 * @param {boolean} hasAudio a complete, not deleted recording exists
 * @param {boolean} hasText the entry text is not blank
 * @param {boolean} manualMode the user chose "write it myself" after a failed transcription
 */
export function entryActions(status, hasAudio, hasText, manualMode) {
  const transcribing = status === 'RECORDED' || status === 'TRANSCRIBING';
  const busy = transcribing || status === 'RECORDING';
  const failed = status === 'TRANSCRIPTION_FAILED';
  const edit = ['DRAFT', 'TRANSCRIBED', 'FINISHED'].includes(status) || (failed && manualMode);
  return {
    editText: edit,
    finish: edit && status !== 'FINISHED' && hasText,
    transcribing,
    retryTranscription: failed && hasAudio,
    typeManually: failed && !manualMode,
    deleteAudio: hasAudio && !busy,
    deleteEntry: !transcribing,
  };
}

/** True while the entry should show transcription progress. */
export function isInProgress(status) {
  return status === 'RECORDED' || status === 'TRANSCRIBING';
}

/**
 * Stop conditions for polling the transcription of an entry.
 * @param {string} entryStatus current entry status (source of truth once the backend moved the entry on)
 * @param {{status: string}|null} job latest job, null while it has not been created yet
 * @param {number} polls number of polls done so far, including this one
 * @returns {'CONTINUE'|'SUCCEEDED'|'FAILED'|'GIVE_UP'}
 */
export function decideTranscription(entryStatus, job, polls, maxPolls = MAX_POLLS) {
  if (entryStatus === 'TRANSCRIBED' || entryStatus === 'FINISHED') {
    return 'SUCCEEDED';
  }
  if (entryStatus === 'TRANSCRIPTION_FAILED' || (job && job.status === 'FAILED')) {
    return 'FAILED';
  }
  // QUEUED, RUNNING, no job yet, or SUCCEEDED while the entry has not switched yet: poll again (bounded).
  return polls >= maxPolls ? 'GIVE_UP' : 'CONTINUE';
}

/**
 * Polls entry and latest job until the transcription is over.
 * @param {object} options
 * @param {number} options.entryId
 * @param {(id: number) => Promise<object>} options.getEntry
 * @param {(id: number) => Promise<object|null>} options.getJob null when no job exists yet
 * @param {(entry: object, decision: 'SUCCEEDED'|'FAILED') => void} options.onDone
 * @param {() => void} [options.onGiveUp]
 * @returns the poller ({start, stop, running})
 */
export function watchTranscription({ entryId, getEntry, getJob, onDone, onGiveUp = () => {}, ...pollerOptions }) {
  let polls = 0;
  return createPoller({
    intervalMs: INTERVAL_MS,
    maxPolls: MAX_POLLS,
    ...pollerOptions,
    task: async () => {
      polls += 1;
      const entry = await getEntry(entryId);
      const job = await getJob(entryId);
      return { entry, decision: decideTranscription(entry.status, job, polls) };
    },
    isDone: (r) => r.decision !== 'CONTINUE',
    onResult: (r) => {
      if (r.decision === 'GIVE_UP') {
        onGiveUp();
      } else if (r.decision !== 'CONTINUE') {
        onDone(r.entry, r.decision);
      }
    },
  });
}

/** The latest transcription job of an entry; null while there is none (the API answers 404 then). */
export async function latestJob(entryId, { get: doGet = get } = {}) {
  try {
    return await doGet(`/api/entries/${entryId}/transcriptions/latest`);
  } catch (e) {
    if (e && e.status === 404) {
      return null;
    }
    throw e;
  }
}

/**
 * Create and update calls for the editors. Saves ask the browser to finish them even when the page is being left
 * (keepalive), so text typed right before a click on a link is not lost.
 * @param {() => ('DE'|'EN'|null)} [language]
 */
export function editorOperations(language = () => null) {
  return entryOperations({
    language,
    post: (path, body) => post(path, body, { keepalive: true }),
    put: (path, body) => put(path, body, { keepalive: true }),
  });
}

/** Entry language for the API from the select value ("AUTO", "DE", "EN"): null lets the provider detect it. */
export function apiLanguage(value) {
  return value === 'DE' || value === 'EN' ? value : null;
}
