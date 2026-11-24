// Polling with backoff. While the task answers, it runs every intervalMs; after a failure the delay grows
// (exponential, full jitter, lib/backoff.js) and the poller keeps trying until it is stopped or the task says "done".
import { retryDelayMs } from './lib/backoff.js';

/**
 * @param {object} options
 * @param {() => Promise<any>} options.task one poll; may throw
 * @param {(result: any) => boolean} [options.isDone] true stops polling
 * @param {(result: any) => void} [options.onResult]
 * @param {(error: unknown, failures: number) => void} [options.onError] called after every failed poll
 * @param {number} [options.intervalMs] delay between successful polls (default 2000, as the Vaadin TranscriptionPolling)
 * @param {number} [options.maxPolls] stop quietly after this many polls (default unlimited)
 * @param {number} [options.baseMs] first backoff delay after a failure
 * @param {number} [options.maxMs] backoff cap
 * @param {() => number} [options.random]
 * @param {Function} [options.setTimeout]
 * @param {Function} [options.clearTimeout]
 * @returns {{start(): void, stop(): void, readonly running: boolean}}
 */
export function createPoller(options) {
  const {
    task, isDone = () => false, onResult = () => {}, onError = () => {}, intervalMs = 2000, maxPolls = Infinity,
    baseMs = 1000, maxMs = 30000, random = Math.random,
    setTimeout: later = globalThis.setTimeout, clearTimeout: cancel = globalThis.clearTimeout,
  } = options;
  let timer = null;
  let running = false;
  let polls = 0;
  let failures = 0;

  function schedule(ms) {
    timer = later(tick, ms);
  }

  async function tick() {
    timer = null;
    if (!running) {
      return;
    }
    polls += 1;
    let result;
    try {
      result = await task();
    } catch (e) {
      if (!running) {
        return;
      }
      onError(e, failures);
      // Full jitter can be 0; a failed poll always waits at least half the base delay.
      const delay = Math.max(retryDelayMs(failures, { baseMs, maxMs, random }), Math.floor(Math.min(baseMs, maxMs) / 2));
      failures += 1;
      if (polls >= maxPolls) {
        running = false;
        return;
      }
      schedule(delay);
      return;
    }
    if (!running) {
      return;
    }
    failures = 0;
    onResult(result);
    if (isDone(result) || polls >= maxPolls) {
      running = false;
      return;
    }
    schedule(intervalMs);
  }

  return {
    start() {
      if (running) {
        return;
      }
      running = true;
      polls = 0;
      failures = 0;
      schedule(0);
    },
    stop() {
      running = false;
      if (timer !== null) {
        cancel(timer);
        timer = null;
      }
    },
    get running() {
      return running;
    },
  };
}
