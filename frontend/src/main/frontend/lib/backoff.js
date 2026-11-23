// Retry delays for uploads and polling (pure, no DOM; unit-tested with `node --test`).

/**
 * Exponential backoff with full jitter.
 * @param {number} attempt 0-based attempt number
 * @param {{baseMs?: number, maxMs?: number, random?: () => number}} [options]
 * @returns {number} delay in milliseconds, 0 <= delay <= min(maxMs, baseMs * 2^attempt)
 */
export function retryDelayMs(attempt, options = {}) {
  const { baseMs = 500, maxMs = 30000, random = Math.random } = options;
  if (!Number.isInteger(attempt) || attempt < 0) {
    throw new RangeError('attempt must be a non-negative integer');
  }
  const cap = Math.min(maxMs, baseMs * 2 ** Math.min(attempt, 30));
  return Math.floor(random() * cap);
}
