import { ChunkQueue } from './chunk-queue.js';
import { Emitter } from './emitter.js';
import { withRetry } from './retry.js';
import { finishRecovered, recover } from './resume.js';
import { RecorderStateMachine, State } from './state-machine.js';

const MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];

export function pickMimeType(MediaRecorderCtor) {
  const supported = (t) => typeof MediaRecorderCtor.isTypeSupported === 'function' && MediaRecorderCtor.isTypeSupported(t);
  return MIME_CANDIDATES.find(supported) ?? '';
}

/**
 * Orchestrates MediaRecorder, the persist-then-upload chunk queue and the state machine.
 * All environment access is injected, so it runs under `node --test` with fakes.
 *
 * Events: state {state}, started {recordingId, entryId}, progress {pendingChunks},
 *         retrying {index, attempt}, completed {recordingId, entryId, durationMs},
 *         error {kind, message}, stream {stream}, recovered {info}, recovery-empty {}.
 */
export class RecorderSession extends Emitter {
  #deps;
  #sm;
  #stream = null;
  #recorder = null;
  #queue = null;
  #meta = null;
  #nextIndex = 0;
  #dataChain = Promise.resolve();
  #baseElapsedMs = 0;
  #accumulatedMs = 0;
  #segmentStart = 0;

  /**
   * @param {object} deps
   * @param {{getUserMedia: Function}} deps.mediaDevices
   * @param {Function} deps.MediaRecorder constructor (with static isTypeSupported)
   * @param {object} deps.storage
   * @param {object} deps.api
   * @param {() => number} deps.now
   * @param {(ms: number) => Promise<void>} deps.sleep
   * @param {number} [deps.timesliceMs]
   * @param {object} [deps.retry]
   */
  constructor(deps) {
    super();
    this.#deps = { timesliceMs: 10_000, retry: {}, ...deps };
    this.#sm = new RecorderStateMachine((change) => this.emit('state', { state: change.to, from: change.from }));
  }

  get state() {
    return this.#sm.state;
  }

  get stream() {
    return this.#stream;
  }

  get recordingId() {
    return this.#meta?.recordingId ?? null;
  }

  get entryId() {
    return this.#meta?.entryId ?? null;
  }

  get pendingChunks() {
    return this.#queue?.pendingCount ?? 0;
  }

  elapsedMs() {
    const live = this.#sm.state === State.RECORDING ? this.#deps.now() - this.#segmentStart : 0;
    return this.#baseElapsedMs + this.#accumulatedMs + live;
  }

  /** Call when the browser comes back online to skip the remaining backoff wait. */
  networkRestored() {
    this.#queue?.kick();
  }

  /**
   * Starts a new recording, or continues a recovered one ({@code continueFrom} from {@link #recoverPending}).
   * @param {{entryId?: number|null, language?: string, entryTimestamp?: string|null, continueFrom?: object}} [options]
   */
  async start({ entryId = null, language = 'auto', entryTimestamp = null, continueFrom = null } = {}) {
    const { mediaDevices, MediaRecorder, storage, api, now } = this.#deps;
    this.#sm.send('request');

    try {
      this.#stream = await mediaDevices.getUserMedia({ audio: true });
    } catch {
      this.#fail('permission', 'The microphone is not available. Check the browser permission and try again.');
      return;
    }

    try {
      const mimeType = continueFrom?.mimeType || pickMimeType(MediaRecorder);
      this.#recorder = new MediaRecorder(this.#stream, mimeType ? { mimeType } : undefined);
      const actualMime = this.#recorder.mimeType || mimeType || 'audio/webm';

      if (continueFrom) {
        this.#meta = {
          recordingId: continueFrom.recordingId,
          entryId: continueFrom.entryId,
          mimeType: continueFrom.mimeType,
          language,
          startedAt: now(),
          nextIndex: continueFrom.nextIndex,
          elapsedMs: continueFrom.elapsedMs,
          stopped: false,
          totalChunks: null,
        };
        this.#nextIndex = continueFrom.nextIndex;
        this.#baseElapsedMs = continueFrom.elapsedMs;
      } else {
        const dto = await api.start({ entryId, mimeType: actualMime, language, entryTimestamp });
        this.#meta = {
          recordingId: dto.id,
          entryId: dto.entryId,
          mimeType: actualMime,
          language,
          startedAt: now(),
          nextIndex: 0,
          elapsedMs: 0,
          stopped: false,
          totalChunks: null,
        };
        this.#nextIndex = 0;
        this.#baseElapsedMs = 0;
      }
      this.#accumulatedMs = 0;
      await storage.putRecording(this.#meta);
      this.#queue = this.#newQueue(this.#meta.recordingId);
    } catch (error) {
      this.#releaseStream();
      this.#fail('start', error?.message || 'The recording could not be started.');
      return;
    }

    this.#recorder.ondataavailable = (event) => this.#onData(event);
    this.#recorder.onerror = () => this.#onRecorderError();
    this.#recorder.start(this.#deps.timesliceMs);
    this.#segmentStart = now();
    this.#sm.send('granted');
    this.emit('stream', { stream: this.#stream });
    this.emit('started', { recordingId: this.#meta.recordingId, entryId: this.#meta.entryId });
  }

  pause() {
    this.#sm.send('pause');
    this.#recorder.pause();
    this.#accumulatedMs += this.#deps.now() - this.#segmentStart;
  }

  resume() {
    this.#sm.send('resume');
    this.#recorder.resume();
    this.#segmentStart = this.#deps.now();
  }

  async stop() {
    const wasRecording = this.#sm.state === State.RECORDING;
    this.#sm.send('stop');
    if (wasRecording) {
      this.#accumulatedMs += this.#deps.now() - this.#segmentStart;
    }
    try {
      await this.#stopRecorder();
      await this.#dataChain;
      this.#meta = {
        ...this.#meta,
        stopped: true,
        totalChunks: this.#nextIndex,
        nextIndex: this.#nextIndex,
        elapsedMs: this.elapsedMs(),
      };
      // Persist "stopped" before uploading: after a crash during upload, recovery completes automatically.
      await this.#deps.storage.putRecording(this.#meta);
    } catch {
      this.#fail('stop', 'The recording could not be stopped cleanly. Your audio is kept on this device.');
      return;
    }
    this.#sm.send('stopped');
    await this.#finalize();
  }

  /** After {@code error} during upload/complete: try again. */
  async retryUpload() {
    this.#sm.send('retry');
    this.#queue?.retryFailed();
    await this.#finalize();
  }

  reset() {
    this.#sm.send('reset');
    this.#meta = null;
    this.#queue = null;
    this.#recorder = null;
  }

  /**
   * Looks for recordings interrupted by a reload or crash, uploads their missing chunks and reports what to offer.
   * Emits {@code recovered} {info} when the user may continue or finish; completes stopped recordings itself.
   * @returns {Promise<object|null>} recovery info for a recording that can be continued/finished, else null
   */
  async recoverPending() {
    const { storage, api, sleep, retry } = this.#deps;
    const pending = (await storage.listRecordings()).sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
    for (const meta of pending) {
      let info;
      try {
        info = await recover({
          storage, api, meta, sleep, retry,
          onEvent: (name, detail) => name === 'retrying' && this.emit('retrying', detail),
        });
      } catch (error) {
        this.emit('error', { kind: 'recover', message: error?.message || 'Saved audio could not be uploaded yet.' });
        return null;
      }
      if (info.outcome !== 'recovered') {
        if (info.outcome === 'server-lost') {
          this.emit('error', { kind: 'server-lost', message: 'A saved recording is not known to the diary service. The audio stays on this device.' });
          return null;
        }
        continue;
      }
      if (info.stopped) {
        await this.finishRecovered(info);
        return null;
      }
      this.emit('recovered', { info });
      return info;
    }
    this.emit('recovery-empty', {});
    return null;
  }

  /** "Finish" for a recovered recording that was not stopped. */
  async finishRecovered(info) {
    this.#sm.send('finish');
    this.#meta = { recordingId: info.recordingId, entryId: info.entryId };
    try {
      await finishRecovered({
        storage: this.#deps.storage, api: this.#deps.api, info, sleep: this.#deps.sleep, retry: this.#deps.retry,
      });
    } catch (error) {
      this.#fail('complete', error?.message || 'The recording could not be finished yet.');
      return;
    }
    this.#sm.send('uploaded');
    this.emit('completed', { recordingId: info.recordingId, entryId: info.entryId, durationMs: info.elapsedMs });
  }

  // ---- internals

  #newQueue(recordingId) {
    return new ChunkQueue({
      recordingId,
      storage: this.#deps.storage,
      api: this.#deps.api,
      sleep: this.#deps.sleep,
      retry: this.#deps.retry,
      onEvent: (name, detail) => {
        if (name === 'retrying') this.emit('retrying', detail);
        if (name === 'acked') this.emit('progress', { pendingChunks: detail.pendingCount });
      },
    });
  }

  #onData(event) {
    const blob = event.data;
    if (!blob || blob.size === 0) {
      return;
    }
    const index = this.#nextIndex++;
    const elapsedMs = this.elapsedMs();
    // Chained so chunks are persisted in order; the blob is not retained after enqueue() returns.
    this.#dataChain = this.#dataChain.then(async () => {
      await this.#queue.enqueue(index, blob);
      this.#meta = { ...this.#meta, nextIndex: index + 1, elapsedMs };
      try {
        await this.#deps.storage.putRecording(this.#meta);
      } catch {
        // metadata refresh is best effort; chunk indices are also derivable from stored chunks
      }
      this.emit('progress', { pendingChunks: this.#queue.pendingCount });
    });
  }

  #onRecorderError() {
    if (this.#sm.can('fail')) {
      this.#releaseStream();
      this.#fail('recorder', 'Recording was interrupted. Audio recorded so far is kept and will be uploaded.');
    }
  }

  #stopRecorder() {
    return new Promise((resolve) => {
      const recorder = this.#recorder;
      const done = () => {
        this.#releaseStream();
        resolve();
      };
      if (!recorder || recorder.state === 'inactive') {
        done();
        return;
      }
      recorder.onstop = done;
      recorder.stop();
    });
  }

  #releaseStream() {
    this.#stream?.getTracks().forEach((track) => track.stop());
    this.#stream = null;
  }

  async #finalize() {
    const { storage, api, sleep, retry } = this.#deps;
    try {
      await this.#queue.flush();
      const meta = this.#meta;
      await withRetry(
        () => api.complete(meta.recordingId, { totalChunks: meta.totalChunks, durationMs: meta.elapsedMs }),
        {
          ...retry,
          sleep,
          isRetryable: (e) => e?.retryable !== false,
          onRetry: (info) => this.emit('retrying', { index: -1, attempt: info.attempt }),
        },
      );
      await storage.deleteRecording(meta.recordingId);
      this.#sm.send('uploaded');
      this.emit('completed', { recordingId: meta.recordingId, entryId: meta.entryId, durationMs: meta.elapsedMs });
    } catch (error) {
      this.#fail('upload', error?.message || 'The upload did not finish. Your audio is kept on this device.');
    }
  }

  #fail(kind, message) {
    if (this.#sm.can('fail')) {
      this.#sm.send('fail');
    }
    this.emit('error', { kind, message });
  }
}
