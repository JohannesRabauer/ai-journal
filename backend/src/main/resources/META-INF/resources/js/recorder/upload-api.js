// Same-origin client for the backend's recordings API (/api/recordings), same origin.
// Never logs bodies.

export class UploadError extends Error {
  /**
   * @param {string} message user-presentable, never journal content
   * @param {{status?: number, code?: string}} [info] status undefined = network failure
   */
  constructor(message, { status, code } = {}) {
    super(message);
    this.name = 'UploadError';
    this.status = status;
    this.code = code;
    this.retryable = isRetryableStatus(status);
  }
}

/** Network failures, timeouts, throttling and server errors are retried; other 4xx are not. */
export function isRetryableStatus(status) {
  return status === undefined || status === 408 || status === 425 || status === 429 || status >= 500;
}

export function createUploadApi({ fetch, baseUrl = '/api/recordings' }) {
  async function call(method, path, { json, body, contentType } = {}) {
    // X-AI-Diary: non-safelisted header; makes cross-site writes impossible (the servers require it for non-GET).
    const headers = { Accept: 'application/json', 'X-AI-Diary': '1' };
    let payload = body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(json);
    } else if (contentType) {
      headers['Content-Type'] = contentType;
    }
    let response;
    try {
      response = await fetch(baseUrl + path, { method, headers, body: payload });
    } catch {
      throw new UploadError('The connection to the diary service was interrupted.');
    }
    if (!response.ok) {
      let code;
      let message = 'The diary service could not process the upload.';
      try {
        const error = await response.json();
        code = error.code;
        message = error.message || message;
      } catch {
        // non-JSON error body (e.g. proxy error page): keep generic message
      }
      throw new UploadError(message, { status: response.status, code });
    }
    try {
      return await response.json();
    } catch {
      throw new UploadError('Unexpected response from the diary service.', { status: 502 });
    }
  }

  return {
    start: ({ entryId = null, mimeType, language = 'auto', entryTimestamp = null }) =>
      call('POST', '', {
        json: { entryId, mimeType, language: String(language).toUpperCase(), entryTimestamp },
      }),
    putChunk: (recordingId, index, blob) =>
      call('PUT', `/${recordingId}/chunks/${index}`, { body: blob, contentType: 'application/octet-stream' }),
    status: (recordingId) => call('GET', `/${recordingId}`),
    complete: (recordingId, { totalChunks, durationMs }) =>
      call('POST', `/${recordingId}/complete`, { json: { totalChunks, durationMs } }),
  };
}
