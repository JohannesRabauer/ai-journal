// Week page enhancement. Everything works without this script (links, GET week picker, form posts with the form
// token). With it: week picker applies on change, "Create again" / "Delete" ask in a dialog, creating a reflection
// shows a status and errors in place, and the edit form can add and remove rows. Texts come from data-* attributes
// of #weekly-root and of the forms; this module has none of its own.
import { post, del, ApiError } from './api.js';
import { confirmDialog } from './confirm.js';
import { errorInfo, errorAttribute, pageHref, removeLabel, renumber } from './weekly-logic.js';

export function initWeekly(doc = globalThis.document, deps = {}) {
  const root = doc.getElementById('weekly-root');
  if (!root) {
    return null;
  }
  const apiPost = deps.post ?? post;
  const apiDel = deps.del ?? del;
  const confirm = deps.confirmDialog ?? confirmDialog;
  const go = deps.navigate ?? ((href) => globalThis.window.location.assign(href));
  const attr = (name) => root.getAttribute(`data-${name}`) ?? '';
  const status = doc.getElementById('weekly-status');
  const errorBox = doc.getElementById('weekly-error');

  // ---- week picker applies on change
  const picker = doc.getElementById('weekly-picker');
  if (picker) {
    doc.getElementById('weekly-week').addEventListener('change', () => picker.submit());
    const goButton = doc.getElementById('weekly-go');
    if (goButton) {
      goButton.hidden = true;
    }
  }

  // ---- confirmations: the summary of the no-JS <details> asks in a dialog instead
  for (const summary of doc.querySelectorAll('details.weekly-confirm > summary')) {
    summary.addEventListener('click', (event) => {
      event.preventDefault();
      summary.parentElement.querySelector('form').requestSubmit();
    });
  }

  // ---- create / regenerate / delete through the JSON API
  const buttons = () => root.querySelectorAll('.weekly-report button, .weekly-report summary');
  const setBusy = (busy, text = '') => {
    status.textContent = busy ? text : '';
    buttons().forEach((b) => { b.disabled = busy; });
  };
  const showError = (error, retry) => {
    const info = errorInfo(error);
    errorBox.textContent = '';
    const p = doc.createElement('p');
    p.textContent = attr(errorAttribute(info.code).replace('data-', '')) || attr('err-unreachable');
    errorBox.appendChild(p);
    if (info.retryable && retry) {
      const again = doc.createElement('button');
      again.type = 'button';
      again.className = 'diary-button diary-button-quiet';
      again.textContent = attr('retry');
      again.addEventListener('click', () => { errorBox.textContent = ''; retry(); });
      errorBox.appendChild(again);
    }
    if (info.reload) {
      go(pageHref(attr('view-href'), { error: info.code }));
    }
  };

  async function create() {
    errorBox.textContent = '';
    setBusy(true, attr('creating'));
    try {
      await apiPost('/api/weekly-reports', { isoYear: Number(attr('iso-year')), isoWeek: Number(attr('iso-week')) });
      go(pageHref(attr('view-href'), { msg: 'created' }));
    } catch (e) {
      setBusy(false);
      showError(e instanceof ApiError ? e : new ApiError('NETWORK', '', 0), create);
    }
  }

  async function remove(id) {
    errorBox.textContent = '';
    setBusy(true, '');
    try {
      await apiDel(`/api/weekly-reports/${id}`);
      go(pageHref(attr('view-href'), { msg: 'deleted' }));
    } catch (e) {
      setBusy(false);
      showError(e instanceof ApiError ? e : new ApiError('NETWORK', '', 0), () => remove(id));
    }
  }

  for (const form of doc.querySelectorAll('form[data-weekly-action]')) {
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const title = form.getAttribute('data-confirm-title');
      const action = form.getAttribute('data-weekly-action');
      if (title) {
        const ok = await confirm({
          title,
          message: form.getAttribute('data-confirm-text') ?? '',
          confirmLabel: form.getAttribute('data-confirm-label') ?? '',
          cancelLabel: attr('cancel'),
          destructive: action === 'delete',
        });
        if (!ok) {
          return;
        }
      }
      if (action === 'delete') {
        await remove(form.getAttribute('data-report-id'));
      } else {
        await create();
      }
    });
  }

  // ---- after a message from a redirect, move to the heading so the new state is read from the top
  const flash = doc.getElementById('weekly-flash');
  const title = doc.getElementById('weekly-title');
  if (flash && flash.textContent.trim() !== '' && title) {
    title.focus();
  }

  initEditor(doc, attr);
  return { create, remove };
}

function initEditor(doc, attr) {
  const form = doc.getElementById('weekly-edit-form');
  if (!form) {
    return;
  }
  let counter = 1000;
  const label = (row) => {
    const field = row.querySelector('input[type="text"], textarea');
    return field ? field.value : '';
  };
  const addRemove = (row) => {
    const button = doc.createElement('button');
    button.type = 'button';
    button.className = 'diary-button diary-button-quiet';
    button.textContent = attr('remove');
    button.setAttribute('aria-label', removeLabel(attr('remove-label'), label(row)));
    button.addEventListener('click', () => {
      const next = row.nextElementSibling || row.previousElementSibling;
      row.remove();
      if (next && next.focus) {
        next.focus();
      }
    });
    row.appendChild(button);
  };
  form.querySelectorAll('[data-row]').forEach(addRemove);
  form.querySelectorAll('[data-add]').forEach((add) => {
    add.hidden = false;
    add.addEventListener('click', () => {
      const section = add.parentElement;
      const rows = section.querySelectorAll('[data-row]');
      const model = rows[rows.length - 1];
      if (!model) {
        return;
      }
      const row = model.cloneNode(true);
      counter += 1;
      row.querySelectorAll('input, textarea').forEach((f) => {
        if (f.type !== 'hidden') {
          f.value = '';
          f.id = renumber(f.id, counter);
        } else {
          f.value = '';
        }
      });
      row.querySelectorAll('label').forEach((l) => l.setAttribute('for', renumber(l.getAttribute('for'), counter)));
      row.querySelectorAll('button').forEach((b) => b.remove());
      section.insertBefore(row, add);
      addRemove(row);
      const first = row.querySelector('input:not([type="hidden"]), textarea');
      if (first) {
        first.focus();
      }
    });
  });
}

if (globalThis.document && globalThis.document.getElementById) {
  initWeekly();
}
