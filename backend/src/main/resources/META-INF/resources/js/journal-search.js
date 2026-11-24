// Pure logic of the journal search (no DOM): URL building and the debounced live update. Search text stays in
// the browser and the local backend; it is only ever sent as the `q` query parameter to the same origin.

export const MAX_QUERY_CHARS = 200;
export const DEBOUNCE_MS = 300;
export const FILTERS = ['all', 'voice', 'reflected'];

export function normalizeQuery(text) {
  return String(text ?? '').trim().slice(0, MAX_QUERY_CHARS);
}

export function normalizeFilter(filter) {
  const f = String(filter ?? '').toLowerCase();
  return FILTERS.includes(f) ? f : 'all';
}

/** Path + query of the journal for a state; defaults (empty q, filter all, page 0) are left out. */
export function buildUrl({ q = '', filter = 'all', page = 0, fragment = false } = {}) {
  const params = new URLSearchParams();
  const text = normalizeQuery(q);
  if (text) {
    params.set('q', text);
  }
  const f = normalizeFilter(filter);
  if (f !== 'all') {
    params.set('filter', f);
  }
  if (page > 0) {
    params.set('page', String(page));
  }
  if (fragment) {
    params.set('fragment', '1');
  }
  const query = params.toString();
  return '/journal' + (query ? '?' + query : '');
}

/**
 * Debounced live search. `schedule()` is called on every keystroke and runs `load` once the input has been quiet
 * for `delay` ms; `now()` runs it at once (filter change, Enter). Unchanged state is not reloaded and a slow,
 * outdated response never overwrites a newer one.
 *
 * @param {{
 *   getState: () => {q: string, filter: string},
 *   load: (state: {q: string, filter: string}) => Promise<unknown>,
 *   apply: (result: unknown, state: {q: string, filter: string}) => void,
 *   onError?: (error: unknown, state: {q: string, filter: string}) => void,
 *   initial?: {q: string, filter: string},
 *   delay?: number, setTimeout?: Function, clearTimeout?: Function,
 * }} deps
 */
export function createLiveSearch({
  getState, load, apply, onError = () => {}, initial,
  delay = DEBOUNCE_MS, setTimeout: later = globalThis.setTimeout, clearTimeout: cancel = globalThis.clearTimeout,
}) {
  let timer = null;
  let seq = 0;
  const key = (s) => JSON.stringify([normalizeQuery(s.q), normalizeFilter(s.filter)]);
  let applied = initial ? key(initial) : null;

  async function run() {
    timer = null;
    const current = getState();
    const state = { q: normalizeQuery(current.q), filter: normalizeFilter(current.filter) };
    if (key(state) === applied) {
      return;
    }
    const mine = ++seq;
    try {
      const result = await load(state);
      if (mine !== seq) {
        return;
      }
      applied = key(state);
      apply(result, state);
    } catch (error) {
      if (mine === seq) {
        onError(error, state);
      }
    }
  }

  return {
    schedule() {
      if (timer !== null) {
        cancel(timer);
      }
      timer = later(run, delay);
    },
    now() {
      if (timer !== null) {
        cancel(timer);
        timer = null;
      }
      return run();
    },
  };
}
