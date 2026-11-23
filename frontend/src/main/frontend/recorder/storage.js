// Local persistence for recording chunks, so audio survives reloads and crashes.
// Interface (all async): putRecording, getRecording, listRecordings, deleteRecording (incl. its chunks),
// putChunk, getChunk, listChunkIndices, deleteChunk.

/** In-memory implementation (tests, and fallback when IndexedDB is unavailable). */
export class MemoryStorage {
  #recordings = new Map();
  #chunks = new Map(); // recordingId -> Map(index -> blob)

  async putRecording(meta) {
    this.#recordings.set(meta.recordingId, structuredClone(meta));
  }

  async getRecording(recordingId) {
    const meta = this.#recordings.get(recordingId);
    return meta ? structuredClone(meta) : undefined;
  }

  async listRecordings() {
    return [...this.#recordings.values()].map((m) => structuredClone(m));
  }

  async deleteRecording(recordingId) {
    this.#recordings.delete(recordingId);
    this.#chunks.delete(recordingId);
  }

  async putChunk(recordingId, index, blob) {
    const map = this.#chunks.get(recordingId) ?? new Map();
    map.set(index, blob);
    this.#chunks.set(recordingId, map);
  }

  async getChunk(recordingId, index) {
    return this.#chunks.get(recordingId)?.get(index);
  }

  async listChunkIndices(recordingId) {
    return [...(this.#chunks.get(recordingId)?.keys() ?? [])].sort((a, b) => a - b);
  }

  async deleteChunk(recordingId, index) {
    this.#chunks.get(recordingId)?.delete(index);
  }
}

const DB_NAME = 'ai-diary-recorder';
const DB_VERSION = 1;

/** IndexedDB implementation (browser only). */
export class IndexedDbStorage {
  #dbPromise;
  #idb;

  constructor(idb = globalThis.indexedDB) {
    this.#idb = idb;
  }

  #db() {
    this.#dbPromise ??= new Promise((resolve, reject) => {
      const request = this.#idb.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        db.createObjectStore('recordings', { keyPath: 'recordingId' });
        db.createObjectStore('chunks', { keyPath: ['recordingId', 'index'] });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return this.#dbPromise;
  }

  async #run(storeNames, mode, work) {
    const db = await this.#db();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeNames, mode);
      let result;
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
      const out = work(tx);
      if (out && 'onsuccess' in out) {
        out.onsuccess = () => {
          result = out.result;
        };
      }
    });
  }

  #range(recordingId) {
    return globalThis.IDBKeyRange.bound([recordingId, 0], [recordingId, Number.MAX_SAFE_INTEGER]);
  }

  putRecording(meta) {
    return this.#run('recordings', 'readwrite', (tx) => tx.objectStore('recordings').put(meta));
  }

  getRecording(recordingId) {
    return this.#run('recordings', 'readonly', (tx) => tx.objectStore('recordings').get(recordingId));
  }

  listRecordings() {
    return this.#run('recordings', 'readonly', (tx) => tx.objectStore('recordings').getAll());
  }

  deleteRecording(recordingId) {
    return this.#run(['recordings', 'chunks'], 'readwrite', (tx) => {
      tx.objectStore('chunks').delete(this.#range(recordingId));
      return tx.objectStore('recordings').delete(recordingId);
    });
  }

  putChunk(recordingId, index, blob) {
    return this.#run('chunks', 'readwrite', (tx) => tx.objectStore('chunks').put({ recordingId, index, blob }));
  }

  async getChunk(recordingId, index) {
    const row = await this.#run('chunks', 'readonly', (tx) => tx.objectStore('chunks').get([recordingId, index]));
    return row?.blob;
  }

  async listChunkIndices(recordingId) {
    const keys = await this.#run('chunks', 'readonly', (tx) =>
      tx.objectStore('chunks').getAllKeys(this.#range(recordingId)));
    return keys.map((k) => k[1]).sort((a, b) => a - b);
  }

  deleteChunk(recordingId, index) {
    return this.#run('chunks', 'readwrite', (tx) => tx.objectStore('chunks').delete([recordingId, index]));
  }
}

/** IndexedDB when available, otherwise memory (recording still works, crash recovery does not). */
export function createDefaultStorage() {
  if (typeof globalThis.indexedDB !== 'undefined') {
    return new IndexedDbStorage();
  }
  return new MemoryStorage();
}
