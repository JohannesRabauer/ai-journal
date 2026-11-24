// Tiny fetch wrapper for the JSON API. Every request carries X-AI-Diary: 1 (the CSRF header the backend requires
// for writes, docs/DECISIONS.md D-031) and asks for JSON. Success resolves with the parsed body (null for no content);
// failure rejects with an ApiError { code, message, status } built from the backend's ErrorDto.

export class ApiError extends Error {
  /**
   * @param {string} code machine code: the backend's ErrorDto.code, or NETWORK / HTTP_<status> / BAD_RESPONSE
   * @param {string} message short text, safe to show
   * @param {number} [status] HTTP status, 0 when the server was not reached
   */
  constructor(code, message, status = 0) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

/**
 * @param {string} path path starting with /api/
 * @param {{method?: string, body?: unknown, headers?: Record<string, string>, signal?: AbortSignal, fetch?: typeof fetch, keepalive?: boolean}} [options]
 *   body is JSON-encoded unless it is a string, FormData or Blob (then it is sent as is, without a JSON content type)
 * @returns {Promise<any>}
 */
export async function api(path, options = {}) {
  const { method = 'GET', body, headers = {}, signal, keepalive } = options;
  const doFetch = options.fetch ?? globalThis.fetch;
  const init = {
    method, signal, keepalive, credentials: 'same-origin',
    headers: { Accept: 'application/json', 'X-AI-Diary': '1', ...headers },
  };
  if (body !== undefined) {
    const raw = typeof body === 'string'
      || (typeof FormData !== 'undefined' && body instanceof FormData)
      || (typeof Blob !== 'undefined' && body instanceof Blob);
    if (raw) {
      init.body = body;
    } else {
      init.body = JSON.stringify(body);
      init.headers['Content-Type'] = 'application/json';
    }
  }
  let response;
  try {
    response = await doFetch(path, init);
  } catch (e) {
    if (e && e.name === 'AbortError') {
      throw e;
    }
    throw new ApiError('NETWORK', 'The diary service is not reachable.', 0);
  }
  if (!response.ok) {
    throw await toError(response);
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  if (text === '') {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new ApiError('BAD_RESPONSE', 'The answer of the diary service could not be read.', response.status);
  }
}

async function toError(response) {
  let code = `HTTP_${response.status}`;
  let message = response.statusText || `HTTP ${response.status}`;
  try {
    const dto = JSON.parse(await response.text());
    if (dto && typeof dto.code === 'string') {
      code = dto.code;
      if (typeof dto.message === 'string') {
        message = dto.message;
      }
    }
  } catch (e) {
    // not an ErrorDto: keep the HTTP-based code
  }
  return new ApiError(code, message, response.status);
}

export const get = (path, options) => api(path, { ...options, method: 'GET' });
export const post = (path, body, options) => api(path, { ...options, method: 'POST', body });
export const put = (path, body, options) => api(path, { ...options, method: 'PUT', body });
export const del = (path, options) => api(path, { ...options, method: 'DELETE' });
