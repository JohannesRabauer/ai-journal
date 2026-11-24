// Settings page enhancement. Without this script the form posts to /settings (with the form token) and the page is
// reloaded with a message; with it the settings are saved in place through the JSON API. Texts come from data-*
// attributes of #settings-root; this module has none of its own.
import { put, post, ApiError } from './api.js';
import {
  parseIntervals, formatIntervals, partialSettings, isEmptyPartial, phraseMatches, formatResult,
} from './settings-logic.js';

export function initSettings(root = globalThis.document, deps = {}) {
  const win = deps.window ?? globalThis.window;
  const apiPut = deps.put ?? put;
  const apiPost = deps.post ?? post;
  const host = root.getElementById('settings-root');
  if (!host) {
    return null;
  }
  const text = (name) => host.getAttribute(`data-${name}`) ?? '';
  const form = root.getElementById('settings-form');
  const status = root.getElementById('settings-save-status');
  const intervalsInput = root.getElementById('settings-intervals');
  const intervalsError = root.getElementById('settings-intervals-error');
  const saveButton = root.getElementById('settings-save');

  initTheme(root, win);

  // ---- settings form
  const read = () => {
    const intervals = parseIntervals(intervalsInput.value);
    return {
      uiLanguage: root.getElementById('settings-ui-language').value.toUpperCase(),
      defaultEntryLanguage: root.getElementById('settings-entry-language').value.toUpperCase(),
      keepAudio: root.getElementById('settings-keep-audio').checked,
      ttsAutoPlay: root.getElementById('settings-tts').checked,
      reviewIntervalsDays: intervals.values,
      intervalsError: intervals.errorKey,
    };
  };
  let loaded = read();

  const showIntervalsError = (key, message) => {
    intervalsError.textContent = key ? (message ?? text(`err-${key}`)) : '';
    if (key) {
      intervalsInput.setAttribute('aria-invalid', 'true');
    } else {
      intervalsInput.removeAttribute('aria-invalid');
    }
  };
  intervalsInput.addEventListener('change', () => showIntervalsError(parseIntervals(intervalsInput.value).errorKey));

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    setStatus(status, '');
    const edited = read();
    showIntervalsError(edited.intervalsError);
    if (edited.intervalsError) {
      intervalsInput.focus();
      return;
    }
    const partial = partialSettings(loaded, edited);
    if (isEmptyPartial(partial)) {
      setStatus(status, text('nothing'));
      return;
    }
    saveButton.disabled = true;
    try {
      const saved = await apiPut('/api/settings', partial);
      const languageChanged = 'uiLanguage' in partial;
      if (saved && Array.isArray(saved.reviewIntervalsDays)) {
        intervalsInput.value = formatIntervals(saved.reviewIntervalsDays);
      }
      loaded = read();
      if (languageChanged) {
        // The next render uses the new language: reload with the "saved" message.
        win.location.assign('/settings?msg=saved');
        return;
      }
      setStatus(status, text('saved'));
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && partial.reviewIntervalsDays) {
        showIntervalsError('server', e.message);
      }
      setStatus(status, errorText(e, text), true);
    } finally {
      saveButton.disabled = false;
    }
  });

  // ---- export: a plain GET form; only announce that the download started
  const exportStatus = root.getElementById('settings-export-status');
  root.getElementById('settings-export').addEventListener('submit', () => {
    setStatus(exportStatus, text('export-started'));
  });

  // ---- delete everything: enabled only for the exact phrase
  const deleteForm = root.getElementById('settings-delete');
  const confirmation = root.getElementById('settings-confirmation');
  const deleteButton = root.getElementById('settings-delete-button');
  const deleteStatus = root.getElementById('settings-delete-status');
  const phrase = text('phrase');
  const sync = () => {
    deleteButton.disabled = !phraseMatches(confirmation.value, phrase);
  };
  sync();
  confirmation.addEventListener('input', sync);
  deleteForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!phraseMatches(confirmation.value, phrase)) {
      return;
    }
    setStatus(deleteStatus, '');
    deleteButton.disabled = true;
    try {
      const result = await apiPost('/api/data/delete-all', { confirmation: confirmation.value });
      confirmation.value = '';
      showDeleteResult(root, deleteStatus, text, result);
    } catch (e) {
      setStatus(deleteStatus, errorText(e, text), true);
    } finally {
      sync();
    }
  });
  return { read };
}

function initTheme(root, win) {
  const group = root.getElementById('settings-theme');
  if (!group || !win || !win.diaryTheme) {
    return;
  }
  const buttons = group.querySelectorAll('[data-theme-mode]');
  const mark = (mode) => buttons.forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-theme-mode') === mode)));
  mark(win.diaryTheme.get());
  buttons.forEach((b) => b.addEventListener('click', () => {
    const mode = b.getAttribute('data-theme-mode');
    win.diaryTheme.set(mode);
    mark(mode);
  }));
  group.hidden = false;
}

function errorText(e, text) {
  const prefix = text('error-prefix');
  if (e instanceof ApiError && e.code === 'NOT_IMPLEMENTED') {
    return `${prefix} ${text('not-available')}`;
  }
  if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.message) {
    return `${prefix} ${e.message}`;
  }
  return `${prefix} ${text('error-unreachable')}`;
}

function setStatus(region, message, isError = false) {
  region.textContent = message;
  region.className = message ? `diary-status${isError ? ' diary-error' : ''}` : '';
  region.setAttribute('role', isError ? 'alert' : 'status');
}

function showDeleteResult(root, region, text, result) {
  region.textContent = '';
  region.className = 'diary-status';
  region.setAttribute('role', 'status');
  const done = root.createElement('p');
  done.textContent = text('delete-done');
  region.appendChild(done);
  const deleted = result && result.deleted ? Object.entries(result.deleted).sort(([a], [b]) => a.localeCompare(b)) : [];
  if (deleted.length > 0) {
    const list = root.createElement('ul');
    list.className = 'diary-result';
    for (const [store, count] of deleted) {
      const li = root.createElement('li');
      li.textContent = formatResult(text('delete-result'), store, count);
      list.appendChild(li);
    }
    region.appendChild(list);
  }
}

if (globalThis.document && globalThis.document.getElementById) {
  initSettings();
}
