// Save-first autosave (SPEC 4.2), ported from the Vaadin AutosavePresenter + AutosaveEditor.
//  - The entry is created on the first non-blank text, then updated whenever the text differs from the last save.
//  - Saves run after 2 s without typing, on blur/change, and when the page is hidden or left.
//  - A failed save keeps the text, says so calmly, and is retried on the next trigger or with the retry button.
//  - States: idle -> saving -> saved | failed. Texts come from the page (data-* attributes), see bindAutosave().
import { post, put } from './api.js';

/** Idle time after the last keystroke before a save starts. */
export const DEBOUNCE_MS = 2000;

/** What a save has to do (pure; mirrors the Vaadin AutosavePresenter and its tests). */
export class AutosavePresenter {
  #entryId = null;
  #lastSaved = '';

  static forNewEntry() {
    return new AutosavePresenter();
  }

  static forExisting(entryId, text) {
    const p = new AutosavePresenter();
    p.#entryId = entryId;
    p.#lastSaved = text ?? '';
    return p;
  }

  /** @returns {'NONE'|'CREATE'|'UPDATE'} */
  decide(current) {
    const text = current ?? '';
    if (this.#entryId === null) {
      return text.trim() === '' ? 'NONE' : 'CREATE';
    }
    return text === this.#lastSaved ? 'NONE' : 'UPDATE';
  }

  /** Call after the backend accepted the text. */
  saved(id, text) {
    this.#entryId = id;
    this.#lastSaved = text ?? '';
  }

  get entryId() {
    return this.#entryId;
  }

  isDirty(current) {
    return this.decide(current) !== 'NONE';
  }

  reset() {
    this.#entryId = null;
    this.#lastSaved = '';
  }
}

/** Local date-time without zone, as the API's LocalDateTime expects (the user's wall clock). */
export function localIsoNow(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

/**
 * Create/update calls for diary entries.
 * @param {{language?: () => ('DE'|'EN'|null), now?: () => string, post?: Function, put?: Function}} [options]
 *   language: the entry language (null = detect automatically, the default)
 */
export function entryOperations({ language = () => null, now = localIsoNow, post: doPost = post, put: doPut = put } = {}) {
  return {
    create: (text) => doPost('/api/entries', { text, entryTimestamp: now(), language: language(), source: 'TEXT' }),
    update: (id, text) => doPut(`/api/entries/${id}`, { text, language: null }),
  };
}

/**
 * The save logic without any DOM.
 * @param {object} options
 * @param {AutosavePresenter} options.presenter
 * @param {() => string} options.getText current text of the editor
 * @param {(text: string) => Promise<{id: number}>} options.create
 * @param {(id: number, text: string) => Promise<{id: number}>} options.update
 * @param {(state: 'idle'|'saving'|'saved'|'failed') => void} [options.onState]
 * @param {(entry: {id: number}) => void} [options.onSaved] after every successful save with the stored entry
 */
export function createAutosave(options) {
  const {
    presenter, getText, create, update, onState = () => {}, onSaved = () => {}, delayMs = DEBOUNCE_MS,
    setTimeout: later = globalThis.setTimeout, clearTimeout: cancel = globalThis.clearTimeout,
  } = options;
  let state = 'idle';
  let timer = null;
  let inFlight = null;
  let again = false;

  function setState(next) {
    state = next;
    onState(next);
  }

  /** Called on every keystroke: restarts the debounce. */
  function changed() {
    if (timer !== null) {
      cancel(timer);
    }
    timer = later(() => {
      timer = null;
      save();
    }, delayMs);
  }

  /** Saves now if there is anything unsaved. Never throws; resolves true when nothing is left unsaved. */
  function save() {
    if (timer !== null) {
      cancel(timer);
      timer = null;
    }
    if (inFlight) {
      // Text changed while a save is running: save again once it finishes.
      again = true;
      return inFlight;
    }
    inFlight = run().finally(() => {
      inFlight = null;
    });
    return inFlight;
  }

  async function run() {
    let ok = true;
    do {
      again = false;
      const text = getText();
      const action = presenter.decide(text);
      if (action === 'NONE') {
        continue;
      }
      setState('saving');
      try {
        const entry = action === 'CREATE' ? await create(text) : await update(presenter.entryId, text);
        presenter.saved(entry.id, text);
        setState('saved');
        onSaved(entry);
        ok = true;
      } catch (e) {
        // Keep the text; the next trigger decides the same thing again and retries.
        setState('failed');
        ok = false;
        again = false;
      }
    } while (again);
    return ok;
  }

  return {
    changed,
    save,
    /** Same as save(); named for the retry button. */
    retry: save,
    get state() {
      return state;
    },
    presenter,
  };
}

/**
 * Wires a textarea and its status element. The status element carries the texts server-rendered in the user's
 * language: data-saving, data-saved, data-failed, data-retry. It should be a polite live region.
 *
 *   <textarea id="entry-text"></textarea>
 *   <div class="diary-autosave" role="status" data-saving="..." data-saved="..." data-failed="..." data-retry="..."></div>
 *
 * @returns the controller from createAutosave (plus detach())
 */
export function bindAutosave({ textarea, status, presenter, ops = entryOperations(), onSaved, document: doc = globalThis.document,
                               window: win = globalThis.window }) {
  const text = doc.createElement('span');
  text.className = 'diary-autosave__text';
  const retry = doc.createElement('button');
  retry.type = 'button';
  retry.className = 'diary-button';
  retry.textContent = status.getAttribute('data-retry') || '';
  retry.hidden = true;
  status.appendChild(text);
  status.appendChild(retry);

  const labels = {
    saving: status.getAttribute('data-saving'),
    saved: status.getAttribute('data-saved'),
    failed: status.getAttribute('data-failed'),
    idle: '',
  };
  const controller = createAutosave({
    presenter,
    getText: () => textarea.value,
    create: ops.create,
    update: ops.update,
    onSaved,
    onState(state) {
      status.setAttribute('data-state', state);
      text.textContent = labels[state] ?? '';
      retry.hidden = state !== 'failed';
    },
  });

  const flush = () => controller.save();
  textarea.addEventListener('input', controller.changed);
  textarea.addEventListener('change', flush);
  textarea.addEventListener('blur', flush);
  retry.addEventListener('click', () => {
    controller.retry();
    textarea.focus();
  });
  const onHide = () => {
    if (doc.visibilityState === 'hidden') {
      flush();
    }
  };
  doc.addEventListener('visibilitychange', onHide);
  win.addEventListener('pagehide', flush);

  return {
    ...controller,
    get state() {
      return controller.state;
    },
    detach() {
      textarea.removeEventListener('input', controller.changed);
      textarea.removeEventListener('change', flush);
      textarea.removeEventListener('blur', flush);
      doc.removeEventListener('visibilitychange', onHide);
      win.removeEventListener('pagehide', flush);
    },
  };
}
