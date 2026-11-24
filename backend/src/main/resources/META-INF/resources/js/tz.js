// Tells the server the browser's time zone once, in a cookie (diary-tz). The server uses it for "Today" and
// "Yesterday" (web/ui/ClientZone). The cookie is not sensitive and carries no journal content.

export const COOKIE = 'diary-tz';

/**
 * Sets the cookie when it is missing or differs from the browser's current zone.
 * @returns {boolean} true when the cookie was written
 */
export function ensureTimeZoneCookie({ document: doc = globalThis.document, intl = globalThis.Intl } = {}) {
  let zone;
  try {
    zone = intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch (e) {
    return false;
  }
  if (!zone || !/^[A-Za-z][A-Za-z0-9_+-]*(\/[A-Za-z0-9_+-]+){0,2}$/.test(zone)) {
    return false;
  }
  const current = (doc.cookie || '').split(';').map((c) => c.trim()).find((c) => c.startsWith(`${COOKIE}=`));
  if (current && decodeURIComponent(current.slice(COOKIE.length + 1)) === zone) {
    return false;
  }
  doc.cookie = `${COOKIE}=${encodeURIComponent(zone)}; Path=/; Max-Age=31536000; SameSite=Lax`;
  return true;
}
