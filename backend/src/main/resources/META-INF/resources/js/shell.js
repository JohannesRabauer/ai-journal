// Loaded on every page by base.html (type="module", deferred). Page modules (js/<page>.js) import what they need
// from api.js, notify.js, confirm.js, autosave.js, poll.js on their own.
import { ensureTimeZoneCookie } from './tz.js';
import { initStatus } from './status.js';

ensureTimeZoneCookie();
initStatus();
