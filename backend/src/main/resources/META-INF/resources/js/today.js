// Today page: Speak / Write composer, recorder element, transcript review, "Earlier today" refresh.
// Hard rule (SPEC 4.4): nothing on this page triggers or shows AI output. Without this script the page is the plain
// Write form (a real <form method="post">), see templates/TodayResource/today.html.
import { get, post } from './api.js';
import { AutosavePresenter, bindAutosave } from './autosave.js';
import { apiLanguage, editorOperations } from './entry-state.js';
import { createEntryPanel } from './entry-panel.js';
import { notify } from './notify.js';

const MODE_KEY = 'diary.today.mode';
const ZONE_RELOAD_KEY = 'diary.today.zoneReload';
const SAVED_CLASS = 'today-write--saved';

function storage(name) {
  try {
    return globalThis[name];
  } catch (e) {
    return null;
  }
}

/** localStorage may be blocked (private window): the mode is a convenience, never required. */
export function readMode(store = storage('localStorage')) {
  try {
    return store && store.getItem(MODE_KEY) === 'write' ? 'write' : 'speak';
  } catch (e) {
    return 'speak';
  }
}

export function saveMode(mode, store = storage('localStorage')) {
  try {
    store && store.setItem(MODE_KEY, mode);
  } catch (e) {
    // best effort
  }
}

/** The recorder's language attribute for a select value ("AUTO", "DE", "EN"). */
export function recorderLanguage(value) {
  return value === 'DE' || value === 'EN' ? value.toLowerCase() : 'auto';
}

function init() {
  const root = document.getElementById('today');
  if (!root) {
    return;
  }
  const texts = JSON.parse(root.getAttribute('data-texts') || '{}');
  const $ = (id) => document.getElementById(id);
  const title = $('today-title');
  const message = $('today-message');
  const seg = $('today-seg');
  const speakPanel = $('today-speak');
  const writeForm = $('today-write');
  const language = $('today-lang');
  const recorder = $('today-recorder');
  const textarea = $('today-text');
  const status = $('today-autosave');
  const review = $('today-review');
  const timeline = $('today-timeline');

  // The browser's time zone decides the date line and "today": the first visit has no cookie yet, so the server
  // used its own zone. shell.js has just written the cookie; reload once so the page is right.
  zoneCheck(root);

  // ---- Speak / Write
  function setMode(mode, persist) {
    const write = mode === 'write';
    seg.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-mode') === mode)));
    speakPanel.hidden = write;
    writeForm.classList.toggle('today-write--on', write);
    if (persist) {
      saveMode(mode);
    }
  }
  seg.hidden = false;
  seg.addEventListener('click', (e) => {
    const b = e.target.closest ? e.target.closest('[data-mode]') : null;
    if (b) {
      setMode(b.getAttribute('data-mode'), true);
    }
  });
  setMode(readMode(), false);

  // ---- Language: for the recorder, for new text entries, for a transcription retry
  language.addEventListener('change', () => recorder.setAttribute('language', recorderLanguage(language.value)));

  // ---- Timeline
  async function refreshTimeline() {
    try {
      const response = await fetch('/?fragment=timeline', { headers: { Accept: 'text/html' }, credentials: 'same-origin' });
      if (!response.ok) {
        return;
      }
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      timeline.replaceChildren(...Array.from(doc.body.childNodes).map((n) => document.importNode(n, true)));
    } catch (e) {
      // keep what is shown
    }
  }

  // ---- Review panel (after a recording)
  const panel = createEntryPanel({
    root: $('today-panel'),
    texts,
    language: () => language.value,
    onChanged(entry) {
      if (entry.status === 'FINISHED') {
        review.hidden = true;
        message.textContent = texts['entries.finished'];
        // the finish button just disappeared: keep keyboard focus on the page instead of losing it to <body>
        title.focus();
      }
      refreshTimeline();
    },
  });
  recorder.addEventListener('recording-completed', async (event) => {
    try {
      const entry = await get(`/api/entries/${event.detail.entryId}`);
      message.textContent = '';
      review.hidden = false;
      panel.show(entry, true);
      refreshTimeline();
    } catch (e) {
      notify(texts['entries.error.unreachable'], { kind: 'error' });
    }
  });

  // ---- Write: save-first autosave, then "Finish entry"
  const presenter = AutosavePresenter.forNewEntry();
  const autosave = bindAutosave({
    textarea,
    status,
    presenter,
    ops: editorOperations(() => apiLanguage(language.value)),
    onSaved: () => {
      writeForm.classList.add(SAVED_CLASS);
    },
  });
  // Typing invalidates the "saved" check at once; the next successful save sets it again.
  textarea.addEventListener('input', () => writeForm.classList.remove(SAVED_CLASS));

  let finishing = false;
  writeForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (finishing) {
      return;
    }
    finishing = true;
    try {
      if (!(await autosave.save())) {
        notify(texts['entries.autosave.failed'], { kind: 'error' });
        return;
      }
      const id = presenter.entryId;
      if (id === null) {
        return;
      }
      await post(`/api/entries/${id}/finish`);
      presenter.reset();
      textarea.value = '';
      status.removeAttribute('data-state');
      const text = status.querySelector('.diary-autosave__text');
      if (text) {
        text.textContent = '';
      }
      writeForm.classList.remove(SAVED_CLASS);
      message.textContent = texts['entries.finished'];
      refreshTimeline();
    } catch (e) {
      notify(e && e.code && e.code !== 'NETWORK' && e.message ? e.message : texts['entries.error.unreachable'], { kind: 'error' });
    } finally {
      finishing = false;
    }
  });
}

function zoneCheck(root) {
  try {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const used = root.getAttribute('data-zone');
    const store = storage('sessionStorage');
    if (zone && used && zone !== used && store && !store.getItem(ZONE_RELOAD_KEY)) {
      store.setItem(ZONE_RELOAD_KEY, '1');
      globalThis.location.reload();
    }
  } catch (e) {
    // zone or storage unavailable: keep the server's date line
  }
}

if (typeof document !== 'undefined' && document.getElementById) {
  init();
}
