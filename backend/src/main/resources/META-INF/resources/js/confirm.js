// Confirmation dialog on the native <dialog> element: showModal() makes the rest of the page inert (focus trap),
// Escape cancels, and focus returns to the control that opened it. All texts are passed in (they come from the
// server's i18n files); this module has none of its own.

/**
 * @param {{title: string, message?: string, confirmLabel: string, cancelLabel: string, destructive?: boolean}} texts
 * @param {{document?: Document}} [deps]
 * @returns {Promise<boolean>} true when confirmed, false when cancelled (button or Escape)
 */
export function confirmDialog(texts, { document: doc = globalThis.document } = {}) {
  const opener = doc.activeElement;
  const id = `diary-dialog-${Math.random().toString(36).slice(2, 8)}`;
  const dialog = doc.createElement('dialog');
  dialog.className = 'diary-dialog';
  dialog.setAttribute('aria-labelledby', `${id}-title`);
  if (texts.message) {
    dialog.setAttribute('aria-describedby', `${id}-message`);
  }

  const title = doc.createElement('h2');
  title.className = 'diary-dialog__title';
  title.id = `${id}-title`;
  title.textContent = texts.title;
  dialog.appendChild(title);

  if (texts.message) {
    const message = doc.createElement('p');
    message.className = 'diary-dialog__message';
    message.id = `${id}-message`;
    message.textContent = texts.message;
    dialog.appendChild(message);
  }

  const actions = doc.createElement('div');
  actions.className = 'diary-dialog__actions';
  const cancel = button(doc, texts.cancelLabel, 'diary-button', 'cancel');
  const ok = button(doc, texts.confirmLabel, texts.destructive ? 'diary-button diary-button-danger' : 'diary-button', 'confirm');
  actions.appendChild(cancel);
  actions.appendChild(ok);
  dialog.appendChild(actions);

  return new Promise((resolve) => {
    dialog.addEventListener('click', (event) => {
      const action = event.target && event.target.getAttribute && event.target.getAttribute('data-action');
      if (action) {
        dialog.close(action);
      }
    });
    // Escape closes the dialog with an empty returnValue, which counts as cancel.
    dialog.addEventListener('close', () => {
      const confirmed = dialog.returnValue === 'confirm';
      dialog.remove();
      if (opener && typeof opener.focus === 'function' && opener.isConnected !== false) {
        opener.focus();
      }
      resolve(confirmed);
    });
    doc.body.appendChild(dialog);
    dialog.showModal();
    // A destructive question starts on "cancel" so Enter never deletes by accident.
    (texts.destructive ? cancel : ok).focus();
  });
}

function button(doc, label, className, action) {
  const b = doc.createElement('button');
  b.type = 'button';
  b.className = className;
  b.setAttribute('data-action', action);
  b.textContent = label;
  return b;
}
