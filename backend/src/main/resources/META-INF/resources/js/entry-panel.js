// The entry panel: shows an entry according to its lifecycle state, in place. Used by Today (review after a
// recording) and the entry page. Port of the Vaadin EntryEditorPanel:
//   - transcribing: progress text + <progress>, polling until the transcript is ready (entry-state.js)
//   - failed: calm notice, "Try transcription again" (while the audio exists) and "Write it myself"
//   - editable: labelled text area with autosave status, and "Finish entry" while the entry is not finished
// It contains no AI output; the only background work it waits for is transcription. Texts come from the page
// (data-texts JSON, keys as in the i18n files); this module has none of its own.
import { get, post } from './api.js';
import { AutosavePresenter, bindAutosave } from './autosave.js';
import { editorOperations, entryActions, latestJob, watchTranscription } from './entry-state.js';
import { notify } from './notify.js';

let counter = 0;

/**
 * @param {object} options
 * @param {Element} options.root container; its content is replaced on every state change
 * @param {Record<string, string>} options.texts
 * @param {() => string} [options.language] "AUTO", "DE" or "EN": used to retry a transcription
 * @param {(entry: object, decision: string) => void} [options.onChanged] the entry reached a state worth refreshing
 *   lists for: finished, transcription done or failed
 * @param {Document} [options.document]
 */
export function createEntryPanel({ root, texts, language = () => 'AUTO', onChanged = () => {}, document: doc = globalThis.document }) {
  let entry = null;
  let hasAudio = false;
  let manualMode = false;
  let editor = null;
  let watcher = null;

  const t = (key) => texts[key] ?? '';

  function make(tag, className, text) {
    const e = doc.createElement(tag);
    if (className) {
      e.className = className;
    }
    if (text !== undefined) {
      e.textContent = text;
    }
    return e;
  }

  function button(label, className, onClick) {
    const b = make('button', className, label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function stop() {
    if (watcher) {
      watcher.stop();
      watcher = null;
    }
    if (editor) {
      editor.autosave.detach();
      editor = null;
    }
  }

  /** Focus only moves when the user is not busy elsewhere (nothing focused, or focus is inside the panel). */
  function focusIfIdle(target) {
    const active = doc.activeElement;
    if (!active || active === doc.body || root.contains(active)) {
      target.focus();
    }
  }

  function render({ moveFocus = false } = {}) {
    stop();
    root.replaceChildren();
    if (!entry) {
      return;
    }
    const actions = entryActions(entry.status, hasAudio, true, manualMode);

    if (actions.transcribing) {
      const running = t('entries.transcription.running');
      const p = make('p', '', running);
      p.setAttribute('role', 'status');
      const bar = make('progress');
      bar.setAttribute('aria-label', running);
      root.append(p, bar);
      startWatching();
      return;
    }

    if (actions.retryTranscription || actions.typeManually) {
      const notice = make('div', 'entries-notice-box');
      notice.setAttribute('role', 'status');
      notice.append(make('p', '', t('entries.transcription.failed')));
      root.append(notice);
      const row = make('div', 'entries-panel__row');
      if (actions.retryTranscription) {
        row.append(button(t('entries.transcription.retry'), 'entries-primary', retryTranscription));
      }
      if (actions.typeManually) {
        row.append(button(t('entries.transcription.typeManually'), 'entries-ghost', () => {
          manualMode = true;
          render();
          if (editor) {
            editor.textarea.focus();
          }
        }));
      }
      root.append(row);
    }

    if (actions.editText) {
      buildEditor(actions);
      if (moveFocus) {
        focusIfIdle(editor.textarea);
      }
    }
  }

  function buildEditor() {
    const reviewing = entry.status === 'TRANSCRIBED';
    const id = `entry-text-${++counter}`;
    const field = make('div', 'entries-field');
    const label = make('label', '', t(reviewing ? 'entries.review.label' : 'entries.text.label'));
    label.htmlFor = id;
    const textarea = make('textarea');
    textarea.id = id;
    textarea.name = 'text';
    textarea.rows = 10;
    textarea.value = entry.text ?? '';
    field.append(label);
    if (reviewing) {
      const helper = make('p', 'entries-field__helper', t('entries.review.helper'));
      helper.id = `${id}-helper`;
      textarea.setAttribute('aria-describedby', helper.id);
      field.append(helper);
    }
    field.append(textarea);

    const status = make('div', 'diary-autosave');
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
    status.setAttribute('data-saving', t('entries.autosave.saving'));
    status.setAttribute('data-saved', t('entries.autosave.saved'));
    status.setAttribute('data-failed', t('entries.autosave.failed'));
    status.setAttribute('data-retry', t('entries.autosave.retry'));

    const wrap = make('div', 'entries-panel__editor');
    wrap.append(field, status);
    root.append(wrap);

    const autosave = bindAutosave({
      textarea,
      status,
      presenter: AutosavePresenter.forExisting(entry.id, entry.text),
      ops: editorOperations(() => null),
      onSaved: (saved) => {
        entry = { ...entry, ...saved };
      },
      document: doc,
    });
    editor = { textarea, autosave };

    if (entryActions(entry.status, hasAudio, true, manualMode).finish) {
      const finish = button(t('entries.finish'), 'entries-primary', finishEntry);
      finish.disabled = textarea.value.trim() === '';
      textarea.addEventListener('input', () => {
        finish.disabled = textarea.value.trim() === '';
      });
      wrap.append(finish);
    }
  }

  async function retryTranscription() {
    try {
      await post(`/api/entries/${entry.id}/transcriptions`, { language: language() });
      show(await get(`/api/entries/${entry.id}`), hasAudio);
    } catch (e) {
      fail(e);
    }
  }

  async function finishEntry(event) {
    const b = event.currentTarget;
    if (b.getAttribute('aria-busy') === 'true') {
      return;
    }
    b.setAttribute('aria-busy', 'true');
    try {
      if (!(await editor.autosave.save())) {
        notify(t('entries.autosave.failed'), { kind: 'error' });
        return;
      }
      const finished = await post(`/api/entries/${entry.id}/finish`);
      show(finished, hasAudio);
      onChanged(finished, 'FINISHED');
    } catch (e) {
      fail(e);
    } finally {
      b.removeAttribute('aria-busy');
    }
  }

  function fail(error) {
    notify(error && error.code && error.code !== 'NETWORK' && error.message ? error.message : t('entries.error.unreachable'),
      { kind: 'error' });
  }

  function startWatching() {
    watcher = watchTranscription({
      entryId: entry.id,
      getEntry: (id) => get(`/api/entries/${id}`),
      getJob: (id) => latestJob(id),
      onDone: (current, decision) => {
        entry = current;
        hasAudio = true;
        render({ moveFocus: true });
        onChanged(current, decision);
      },
      onGiveUp: () => {
        stop();
        root.replaceChildren(make('p', '', t('entries.transcription.stillRunning')));
        root.firstChild.setAttribute('role', 'status');
      },
    });
    watcher.start();
  }

  function show(next, audio, options = {}) {
    entry = next;
    hasAudio = audio;
    if (options.manual !== undefined) {
      manualMode = options.manual;
    }
    render();
  }

  return {
    show,
    get entry() {
      return entry;
    },
    /** Moves keyboard focus into the text area (the "Edit" action); false when the entry is not editable. */
    focusEditor() {
      if (!editor) {
        return false;
      }
      editor.textarea.focus();
      return true;
    },
    /** Saves pending text; resolves true if nothing is left unsaved. */
    saveNow: () => (editor ? editor.autosave.save() : Promise.resolve(true)),
    stop,
  };
}
