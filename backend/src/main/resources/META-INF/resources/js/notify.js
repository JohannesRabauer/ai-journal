// Short status messages in the single aria-live="polite" region of base.html (<div id="diary-notify">).
// Messages are announced by screen readers without moving focus and remove themselves after a few seconds.
// Never use this for anything the user has to act on: use an inline status or a dialog for that.

const REGION_ID = 'diary-notify';

/**
 * @param {{region: Element, document?: Document, setTimeout?: Function, clearTimeout?: Function}} deps
 */
export function createNotifier({
  region, document: doc = globalThis.document, setTimeout: later = globalThis.setTimeout,
  clearTimeout: cancel = globalThis.clearTimeout,
}) {
  return function notify(text, { kind = 'info', timeoutMs } = {}) {
    if (!text) {
      return null;
    }
    const item = doc.createElement('p');
    item.className = 'diary-notify__item';
    item.setAttribute('data-kind', kind);
    item.textContent = String(text);
    region.appendChild(item);
    const ms = timeoutMs ?? (kind === 'error' ? 10000 : 5000);
    const timer = ms > 0 ? later(() => item.remove(), ms) : null;
    return {
      dismiss() {
        if (timer !== null) {
          cancel(timer);
        }
        item.remove();
      },
    };
  };
}

/**
 * Shows a message in the page's live region; does nothing when the page has none.
 * @param {string} text
 * @param {{kind?: 'info'|'error', timeoutMs?: number}} [options]
 */
export function notify(text, options) {
  const region = globalThis.document && globalThis.document.getElementById(REGION_ID);
  return region ? createNotifier({ region })(text, options) : null;
}
