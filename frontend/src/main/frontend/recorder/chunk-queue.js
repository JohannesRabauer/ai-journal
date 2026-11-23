import { withRetry } from './retry.js';

/**
 * Persist-then-upload queue for one recording.
 * - every chunk is written to storage BEFORE its upload starts and removed only after the server ACK
 * - uploads retry with backoff (network down, 5xx, 429); non-retryable errors (other 4xx) fail the queue
 * - acknowledgements may arrive in any order; enqueueing an index twice is a no-op (server is idempotent too)
 * - chunks are not kept in RAM once persisted
 */
export class ChunkQueue {
  #pending = new Map(); // index -> {index, blob|null, state}
  #acked = new Set();
  #inFlight = 0;
  #persisting = 0;
  #waiters = [];
  #fatal = null;
  #wake;
  #wakeResolve;
  #o;

  /**
   * @param {object} o
   * @param {string} o.recordingId
   * @param {object} o.storage
   * @param {{putChunk: Function}} o.api
   * @param {(ms: number) => Promise<void>} o.sleep
   * @param {{baseMs?: number, maxMs?: number, maxAttempts?: number, random?: () => number}} [o.retry]
   * @param {number} [o.concurrency]
   * @param {(name: string, detail: object) => void} [o.onEvent] retrying | acked | failed | storage-error
   */
  constructor(o) {
    this.#o = { retry: {}, concurrency: 2, onEvent: () => {}, ...o };
    this.#renewWake();
  }

  get pendingCount() {
    return this.#pending.size + this.#persisting;
  }

  get ackedIndices() {
    return [...this.#acked].sort((a, b) => a - b);
  }

  #renewWake() {
    this.#wake = new Promise((resolve) => {
      this.#wakeResolve = resolve;
    });
  }

  /** Ends running backoff waits now (e.g. when the browser reports it is online again). */
  kick() {
    this.#wakeResolve();
    this.#renewWake();
  }

  /** Persists a new chunk, then schedules its upload. */
  async enqueue(index, blob) {
    if (this.#known(index)) {
      return;
    }
    this.#persisting++;
    let kept = null;
    try {
      await this.#o.storage.putChunk(this.#o.recordingId, index, blob);
    } catch {
      // Storage full or unavailable: keep the chunk in memory so it is still uploaded.
      kept = blob;
      this.#o.onEvent('storage-error', { index });
    } finally {
      this.#persisting--;
    }
    this.#add(index, kept);
  }

  /** Schedules a chunk that is already in storage (resume after reload). */
  enqueueExisting(index) {
    this.#add(index, null);
  }

  #known(index) {
    return this.#pending.has(index) || this.#acked.has(index);
  }

  #add(index, blob) {
    if (this.#known(index)) {
      this.#settle();
      return;
    }
    this.#pending.set(index, { index, blob, state: 'queued' });
    this.#pump();
  }

  /** Resolves when everything is acknowledged; rejects with the first non-retryable error. */
  flush() {
    return new Promise((resolve, reject) => {
      this.#waiters.push({ resolve, reject });
      this.#settle();
    });
  }

  /** After a fatal failure: put failed chunks back in the queue and continue. */
  retryFailed() {
    this.#fatal = null;
    for (const item of this.#pending.values()) {
      if (item.state === 'failed') {
        item.state = 'queued';
      }
    }
    this.#pump();
  }

  #pump() {
    if (this.#fatal) {
      this.#settle();
      return;
    }
    for (const item of this.#pending.values()) {
      if (this.#inFlight >= this.#o.concurrency) {
        break;
      }
      if (item.state === 'queued') {
        item.state = 'uploading';
        this.#inFlight++;
        this.#upload(item);
      }
    }
    this.#settle();
  }

  async #upload(item) {
    const { recordingId, storage, api, sleep, retry, onEvent } = this.#o;
    try {
      await withRetry(
        async () => {
          const blob = item.blob ?? (await storage.getChunk(recordingId, item.index));
          if (!blob) {
            return; // already gone from storage: it was uploaded and removed earlier
          }
          await api.putChunk(recordingId, item.index, blob);
        },
        {
          ...retry,
          sleep,
          wake: () => this.#wake,
          isRetryable: (error) => error?.retryable !== false,
          onRetry: (info) => onEvent('retrying', { index: item.index, attempt: info.attempt, delayMs: info.delayMs }),
        },
      );
      try {
        await storage.deleteChunk(recordingId, item.index);
      } catch {
        // leftover chunk is harmless: resume sees it as received and deletes it
      }
      this.#pending.delete(item.index);
      this.#acked.add(item.index);
      onEvent('acked', { index: item.index, pendingCount: this.pendingCount });
    } catch (error) {
      item.state = 'failed';
      this.#fatal ??= error;
      onEvent('failed', { index: item.index, error });
    } finally {
      this.#inFlight--;
      this.#pump();
    }
  }

  #settle() {
    if (this.#waiters.length === 0) {
      return;
    }
    if (this.#fatal && this.#inFlight === 0) {
      const error = this.#fatal;
      this.#waiters.splice(0).forEach((w) => w.reject(error));
    } else if (!this.#fatal && this.#pending.size === 0 && this.#persisting === 0) {
      this.#waiters.splice(0).forEach((w) => w.resolve());
    }
  }
}
