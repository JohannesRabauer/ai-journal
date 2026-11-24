// Offline notice (port of the Vaadin CloudBanner's OFFLINE state). The server renders the cloud/local status
// into the page; this script asks /api/system/info and shows the calm "service not reachable" banner with a
// "Try again" button when that fails. Texts are server-rendered into the page (data-text, data-offline-text).
import { get } from './api.js';

export const INFO_PATH = '/api/system/info';

/**
 * @param {object} deps
 * @param {Element|null} deps.banner #diary-offline: has data-text, a .diary-banner-text live region and a [data-action=retry] button
 * @param {Element|null} [deps.navStatus] .diary-nav-status: data-kind, data-offline-text, a <span> with the text
 * @param {() => Promise<any>} [deps.fetchInfo]
 * @returns {{check(): Promise<boolean>}} check() resolves true when the service answered
 */
export function createStatus({ banner, navStatus = null, fetchInfo = () => get(INFO_PATH) }) {
  if (!banner) {
    return { check: async () => true };
  }
  const live = banner.querySelector('.diary-banner-text');
  const retry = banner.querySelector('[data-action="retry"]');
  const navText = navStatus ? navStatus.querySelector('span') : null;
  const original = navStatus ? { kind: navStatus.getAttribute('data-kind'), text: navText ? navText.textContent : '' } : null;
  let offline = false;

  function show(isOffline) {
    if (isOffline === offline) {
      return;
    }
    offline = isOffline;
    banner.hidden = !isOffline;
    if (live) {
      live.textContent = isOffline ? banner.getAttribute('data-text') || '' : '';
    }
    if (navStatus) {
      navStatus.setAttribute('data-kind', isOffline ? 'offline' : original.kind);
      if (navText) {
        navText.textContent = isOffline ? navStatus.getAttribute('data-offline-text') || original.text : original.text;
      }
    }
  }

  async function check() {
    try {
      const info = await fetchInfo();
      show(!info);
      return !!info;
    } catch (e) {
      show(true);
      return false;
    }
  }

  if (retry) {
    retry.addEventListener('click', () => {
      retry.setAttribute('aria-busy', 'true');
      check().finally(() => retry.removeAttribute('aria-busy'));
    });
  }
  return { check };
}

/** Wires the page: checks on load and whenever the browser reports it is back online or lost the network. */
export function initStatus({ document: doc = globalThis.document, window: win = globalThis.window } = {}) {
  const status = createStatus({ banner: doc.getElementById('diary-offline'), navStatus: doc.querySelector('.diary-nav-status') });
  status.check();
  win.addEventListener('online', () => status.check());
  win.addEventListener('offline', () => status.check());
  return status;
}
