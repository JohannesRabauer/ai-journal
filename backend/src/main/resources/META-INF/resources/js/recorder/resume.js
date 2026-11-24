import { ChunkQueue } from './chunk-queue.js';
import { withRetry } from './retry.js';

// Recovery after a reload or crash. Locally stored chunks are the source of truth for audio that was
// not yet acknowledged; the server's receivedChunks tells which of them can be dropped.

/** Recordings whose chunks/metadata are still in local storage (not yet completed on the server). */
export async function findPending(storage) {
  const all = await storage.listRecordings();
  return all.sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
}

/**
 * Uploads every locally stored chunk the server is missing.
 * @returns {Promise<{outcome: 'recovered', recordingId: string, entryId: number, mimeType: string,
 *   nextIndex: number, elapsedMs: number, stopped: boolean, totalChunks: number|null, uploaded: number}
 *   | {outcome: 'already-complete', recordingId: string, entryId: number}
 *   | {outcome: 'server-lost', recordingId: string, entryId: number}>}
 */
export async function recover({ storage, api, meta, sleep, retry = {}, onEvent = () => {} }) {
  let status;
  try {
    status = await withRetry(() => api.status(meta.recordingId), {
      ...retry,
      sleep,
      isRetryable: (e) => e?.retryable !== false,
    });
  } catch (error) {
    if (error?.status === 404) {
      // The server does not know this recording. Keep the local audio untouched; never delete it silently.
      return { outcome: 'server-lost', recordingId: meta.recordingId, entryId: meta.entryId };
    }
    throw error;
  }

  if (status.status !== 'RECORDING') {
    await storage.deleteRecording(meta.recordingId);
    return { outcome: 'already-complete', recordingId: meta.recordingId, entryId: status.entryId ?? meta.entryId };
  }

  const received = new Set(status.receivedChunks ?? []);
  const local = await storage.listChunkIndices(meta.recordingId);
  const queue = new ChunkQueue({ recordingId: meta.recordingId, storage, api, sleep, retry, onEvent });
  let uploaded = 0;
  for (const index of local) {
    if (received.has(index)) {
      await storage.deleteChunk(meta.recordingId, index);
    } else {
      queue.enqueueExisting(index);
      uploaded++;
    }
  }
  await queue.flush();

  const highest = Math.max(-1, ...received, ...local, (meta.nextIndex ?? 0) - 1);
  return {
    outcome: 'recovered',
    recordingId: meta.recordingId,
    entryId: status.entryId ?? meta.entryId,
    mimeType: meta.mimeType,
    nextIndex: highest + 1,
    elapsedMs: meta.elapsedMs ?? 0,
    stopped: Boolean(meta.stopped),
    totalChunks: meta.totalChunks ?? null,
    uploaded,
  };
}

/**
 * Declares the recording complete (user chose "finish", or the recording had been stopped before the crash).
 * Local metadata is removed only after the server accepted it.
 */
export async function finishRecovered({ storage, api, info, sleep, retry = {} }) {
  const totalChunks = info.stopped && info.totalChunks != null ? info.totalChunks : info.nextIndex;
  const dto = await withRetry(
    () => api.complete(info.recordingId, { totalChunks, durationMs: info.elapsedMs }),
    { ...retry, sleep, isRetryable: (e) => e?.retryable !== false },
  );
  await storage.deleteRecording(info.recordingId);
  return dto;
}
