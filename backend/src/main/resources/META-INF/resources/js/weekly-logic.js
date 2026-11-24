// Pure logic of the week page (no DOM). Mirrors WeeklyPresenter.errorFor on the server: calm, user-presentable
// explanations of a failed call, as a short code that selects a text rendered into the page (data-err-<code>).

/**
 * @param {{code?: string, status?: number}|null|undefined} error an ApiError (or anything else)
 * @returns {{code: string, retryable: boolean, reload: boolean}} code is one of unreachable, provider, invalid, noEntries,
 *   badWeek, invalidEdit, notFound, unavailable
 */
export function errorInfo(error) {
  const code = error && typeof error.code === 'string' ? error.code : '';
  const status = error && typeof error.status === 'number' ? error.status : 0;
  if (code === 'LLM_OUTPUT_INVALID') {
    return { code: 'invalid', retryable: true, reload: false };
  }
  if (code.startsWith('PROVIDER_')) {
    return { code: 'provider', retryable: true, reload: false };
  }
  if (code === 'NO_ENTRIES_IN_WEEK') {
    return { code: 'noEntries', retryable: false, reload: false };
  }
  if (status === 400) {
    return code === 'INVALID_REPORT'
      ? { code: 'invalidEdit', retryable: false, reload: false }
      : { code: 'badWeek', retryable: false, reload: false };
  }
  if (status === 404) {
    return { code: 'notFound', retryable: false, reload: true };
  }
  if (status === 501) {
    return { code: 'unavailable', retryable: false, reload: false };
  }
  return { code: 'unreachable', retryable: true, reload: false };
}

/** The name of the data attribute holding the text of an error code (attribute names are lower case). */
export function errorAttribute(code) {
  return `data-err-${String(code).toLowerCase()}`;
}

/** Address of the page for a week with a message code, e.g. "/weekly?week=2026-W12&msg=created". */
export function pageHref(viewHref, params = {}) {
  const query = Object.entries(params).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return query ? `${viewHref}${viewHref.includes('?') ? '&' : '?'}${query}` : viewHref;
}

/** Label for a "Remove ..." button: the first characters of what is removed, like the server-rendered Vaadin label. */
export function removeLabel(template, what) {
  let label = (what ?? '').trim();
  if (label.length > 40) {
    label = `${label.slice(0, 40)}…`;
  }
  return String(template).replace('{0}', label);
}

/** Replaces the running number in an id/for like "weekly-thread-3" so cloned rows stay unique. */
export function renumber(id, n) {
  return String(id).replace(/-\d+$/, `-${n}`);
}
