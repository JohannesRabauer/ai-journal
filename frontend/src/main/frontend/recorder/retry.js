import { retryDelayMs } from '../lib/backoff.js';

/**
 * Runs fn until it succeeds, retrying with exponential backoff + jitter.
 * @param {(attempt: number) => Promise<any>} fn
 * @param {object} options
 * @param {(ms: number) => Promise<void>} options.sleep injected so tests need no real time
 * @param {() => Promise<void>} [options.wake] returns a promise that ends the current wait early (e.g. "online")
 * @param {(error: unknown) => boolean} [options.isRetryable]
 * @param {number} [options.maxAttempts] default: retry forever
 * @param {(info: {attempt: number, delayMs: number, error: unknown}) => void} [options.onRetry]
 */
export async function withRetry(fn, options) {
  const { sleep, wake, isRetryable = () => true, maxAttempts = Infinity, onRetry, baseMs, maxMs, random } = options;
  for (let attempt = 0; ; attempt++) {
    try {
      return await fn(attempt);
    } catch (error) {
      if (!isRetryable(error) || attempt + 1 >= maxAttempts) {
        throw error;
      }
      const delayMs = retryDelayMs(attempt, { baseMs, maxMs, random });
      onRetry?.({ attempt: attempt + 1, delayMs, error });
      await (wake ? Promise.race([sleep(delayMs), wake()]) : sleep(delayMs));
    }
  }
}
