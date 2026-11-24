// Entry page: text editing with autosave, transcription progress, delete audio / delete entry with a confirmation
// dialog. Progressive enhancement: without this script the page is readable and every action is a real form
// (templates/JournalResource/entry.html); with it the panel and the actions work in place.
import { del } from './api.js';
import { confirmDialog } from './confirm.js';
import { createEntryPanel } from './entry-panel.js';
import { notify } from './notify.js';

const FOCUS_KEY = 'diary.entry.focus';

/** After an in-page action the old controls are gone: reload and put focus on the heading (like the Vaadin view). */
function reloadWithFocus() {
  try {
    globalThis.sessionStorage.setItem(FOCUS_KEY, '1');
  } catch (e) {
    // focus is a nicety
  }
  globalThis.location.reload();
}

function restoreFocus(title) {
  try {
    if (globalThis.sessionStorage.getItem(FOCUS_KEY)) {
      globalThis.sessionStorage.removeItem(FOCUS_KEY);
      title.focus();
    }
  } catch (e) {
    // nothing to restore
  }
}

function init() {
  const root = document.getElementById('entry');
  if (!root) {
    return;
  }
  const texts = JSON.parse(root.getAttribute('data-texts') || '{}');
  const entryId = Number(root.getAttribute('data-entry-id'));
  const title = document.getElementById('entry-title');
  restoreFocus(title);

  // ---- Text panel: transcription progress, failed notice, editor with autosave and "Finish entry"
  const panelRoot = document.getElementById('entry-panel');
  const status = root.getAttribute('data-status');
  const panel = createEntryPanel({
    root: panelRoot,
    texts,
    onChanged: () => reloadWithFocus(),
  });
  const textarea = panelRoot.querySelector('textarea');
  const manual = new URLSearchParams(globalThis.location.search).get('manual') === '1';
  panel.show({ id: entryId, status, text: textarea ? textarea.value : '' },
    panelRoot.getAttribute('data-has-audio') === 'true', { manual });

  const edit = root.querySelector('[data-action="edit"]');
  if (edit) {
    edit.hidden = false;
    edit.addEventListener('click', () => panel.focusEditor());
  }

  // ---- Destructive actions: the <details> fallback becomes a button that opens the confirmation dialog
  root.querySelectorAll('form.entries-action').forEach((form) => {
    const summary = form.querySelector('summary');
    const details = form.querySelector('details');
    if (!summary || !details) {
      return;
    }
    const button = document.createElement('button');
    button.type = 'submit';
    button.className = 'entries-ghost';
    summary.childNodes.forEach((n) => button.appendChild(n.cloneNode(true)));
    details.replaceWith(button);

    let busy = false;
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (busy) {
        return;
      }
      busy = true;
      try {
        const confirmed = await confirmDialog({
          title: form.getAttribute('data-title'),
          message: form.getAttribute('data-message'),
          confirmLabel: form.getAttribute('data-confirm'),
          cancelLabel: form.getAttribute('data-cancel'),
          destructive: true,
        });
        if (!confirmed) {
          return;
        }
        await panel.saveNow();
        if (form.getAttribute('data-kind') === 'audio') {
          await del(`/api/recordings/${form.getAttribute('data-recording-id')}/audio`);
          reloadWithFocus();
        } else {
          await del(`/api/entries/${entryId}`);
          globalThis.location.assign('/journal');
        }
      } catch (e) {
        notify(e && e.code && e.code !== 'NETWORK' && e.message ? e.message : texts['entries.error.unreachable'],
          { kind: 'error' });
      } finally {
        busy = false;
      }
    });
  });
}

if (typeof document !== 'undefined' && document.getElementById) {
  init();
}
