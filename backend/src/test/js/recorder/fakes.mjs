// Test doubles: no microphone, no network, no real time.
import { UploadError } from '../../../main/resources/META-INF/resources/js/recorder/upload-api.js';

export function bytes(n = 3, fill = 1) {
  return new Blob([new Uint8Array(n).fill(fill)]);
}

/**
 * Records requested delays and yields one macrotask instead of waiting (so loops cannot starve the event loop).
 * `onSleep(count)` lets a test change the world; `dead: true` never resolves (a crashed tab does not retry).
 */
export function createSleep(onSleep = () => {}, { dead = false } = {}) {
  const sleeper = {
    delays: [],
    sleep: async (ms) => {
      sleeper.delays.push(ms);
      if (dead) {
        await new Promise(() => {});
      }
      onSleep(sleeper.delays.length);
      await new Promise((resolve) => setImmediate(resolve));
    },
  };
  return sleeper;
}

export function createClock(start = 0) {
  const clock = { t: start, now: () => clock.t, advance: (ms) => { clock.t += ms; } };
  return clock;
}

export function createFakeMediaRecorder() {
  class FakeMediaRecorder {
    static instances = [];
    static supported = new Set(['audio/webm;codecs=opus', 'audio/webm']);
    static isTypeSupported(type) {
      return FakeMediaRecorder.supported.has(type);
    }

    constructor(stream, options) {
      this.stream = stream;
      this.mimeType = options?.mimeType ?? '';
      this.state = 'inactive';
      this.finalBytes = null;
      FakeMediaRecorder.instances.push(this);
    }

    start(timeslice) {
      this.state = 'recording';
      this.timeslice = timeslice;
    }

    pause() {
      this.state = 'paused';
    }

    resume() {
      this.state = 'recording';
    }

    stop() {
      this.state = 'inactive';
      // Like the browser: final dataavailable first, then stop.
      queueMicrotask(() => {
        if (this.finalBytes) {
          this.ondataavailable?.({ data: this.finalBytes });
        }
        this.onstop?.();
      });
    }

    /** Test helper: the browser delivers one timeslice of audio. */
    emitChunk(blob = bytes()) {
      this.ondataavailable?.({ data: blob });
    }
  }
  return FakeMediaRecorder;
}

export function createFakeMediaDevices({ deny = false } = {}) {
  const stream = { stopped: false, getTracks: () => [{ stop: () => { stream.stopped = true; } }] };
  return {
    stream,
    getUserMedia: async () => {
      if (deny) {
        throw new Error('NotAllowedError');
      }
      return stream;
    },
  };
}

/** In-memory stand-in for the backend recordings API, with failure injection and held (out-of-order) chunks. */
export class FakeServer {
  recordings = new Map();
  offline = false;
  failures = []; // {method, status, times}
  held = new Map(); // index -> release()
  putCalls = [];
  nextId = 1;

  constructor() {
    this.api = {
      start: (req) => this.#guard('start', async () => {
        const id = `rec-${this.nextId++}`;
        const rec = { id, entryId: req.entryId ?? 100 + this.nextId, mimeType: req.mimeType, status: 'RECORDING', chunks: new Map() };
        this.recordings.set(id, rec);
        return { id, entryId: rec.entryId, status: 'RECORDING', receivedChunks: [] };
      }),
      putChunk: (id, index, blob) => this.#guard('putChunk', async () => {
        this.putCalls.push({ id, index });
        const rec = this.#get(id);
        if (rec.status !== 'RECORDING') {
          throw new UploadError('Recording already complete', { status: 409 });
        }
        if (this.held.has(index)) {
          await new Promise((resolve) => this.held.set(index, resolve));
        }
        const duplicate = rec.chunks.has(index);
        rec.chunks.set(index, blob.size);
        return { recordingId: id, chunkIndex: index, duplicate, receivedCount: rec.chunks.size };
      }),
      status: (id) => this.#guard('status', async () => this.#dto(this.#get(id))),
      complete: (id, { totalChunks, durationMs }) => this.#guard('complete', async () => {
        const rec = this.#get(id);
        const missing = [];
        for (let i = 0; i < totalChunks; i++) {
          if (!rec.chunks.has(i)) missing.push(i);
        }
        if (missing.length) {
          throw new UploadError(`Missing chunks: ${missing.join(',')}`, { status: 409 });
        }
        rec.status = 'COMPLETE';
        rec.totalChunks = totalChunks;
        rec.durationMs = durationMs;
        return this.#dto(rec);
      }),
    };
  }

  holdChunk(index) {
    this.held.set(index, () => {});
  }

  releaseChunk(index) {
    const release = this.held.get(index);
    this.held.delete(index);
    release();
  }

  failNext(method, status, times = 1) {
    this.failures.push({ method, status, times });
  }

  #get(id) {
    const rec = this.recordings.get(id);
    if (!rec) throw new UploadError('Recording not found', { status: 404 });
    return rec;
  }

  #dto(rec) {
    return {
      id: rec.id, entryId: rec.entryId, status: rec.status,
      receivedChunks: [...rec.chunks.keys()].sort((a, b) => a - b), totalChunks: rec.totalChunks ?? null,
    };
  }

  async #guard(method, fn) {
    if (this.offline) {
      throw new UploadError('The connection to the diary service was interrupted.');
    }
    const failure = this.failures.find((f) => f.method === method && f.times > 0);
    if (failure) {
      failure.times--;
      throw new UploadError('server error', { status: failure.status });
    }
    return fn();
  }
}

/** Lets pending microtasks/promise chains settle. */
export async function settle(times = 20) {
  for (let i = 0; i < times; i++) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}
