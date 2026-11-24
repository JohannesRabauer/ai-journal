// Pure logic of the settings page (no DOM): the same rules as IntervalsParser, SettingsDiff and DeleteConfirmation on
// the server, so the page can explain a problem before sending anything. The server stays the authority.

export const MIN_DAYS = 1;
export const MAX_DAYS = 365;
export const MAX_COUNT = 10;

/**
 * Review intervals typed as "1, 3, 7, 14, 30": strictly ascending whole days in 1..365, at most 10.
 * @param {string|null|undefined} text
 * @returns {{values: number[]|null, errorKey: string|null}} errorKey is one of empty, format, range, order, count
 */
export function parseIntervals(text) {
  if (text === null || text === undefined || String(text).trim() === '') {
    return { values: null, errorKey: 'empty' };
  }
  const values = [];
  for (const part of String(text).split(/[,;\s]+/)) {
    if (part === '') {
      continue;
    }
    if (!/^[+-]?\d+$/.test(part)) {
      return { values: null, errorKey: 'format' };
    }
    const v = Number.parseInt(part, 10);
    if (v < MIN_DAYS || v > MAX_DAYS) {
      return { values: null, errorKey: 'range' };
    }
    if (values.length > 0 && v <= values[values.length - 1]) {
      return { values: null, errorKey: 'order' };
    }
    values.push(v);
  }
  if (values.length === 0) {
    return { values: null, errorKey: 'empty' };
  }
  if (values.length > MAX_COUNT) {
    return { values: null, errorKey: 'count' };
  }
  return { values, errorKey: null };
}

export function formatIntervals(values) {
  return Array.isArray(values) ? values.join(', ') : '';
}

/** Only fields that differ from the loaded settings; everything else is left out of the PUT body. */
export function partialSettings(current, edited) {
  const partial = {};
  for (const key of ['uiLanguage', 'defaultEntryLanguage', 'keepAudio', 'ttsAutoPlay', 'reviewIntervalsDays']) {
    if (!sameValue(current[key], edited[key])) {
      partial[key] = edited[key];
    }
  }
  return partial;
}

export function isEmptyPartial(partial) {
  return Object.keys(partial).length === 0;
}

function sameValue(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** The delete button is enabled only when the typed text equals the phrase exactly. */
export function phraseMatches(typed, phrase) {
  return typeof typed === 'string' && typed === phrase;
}

/** "{0}: {1} removed" with the store name and the count filled in. */
export function formatResult(template, store, count) {
  return String(template).replace('{0}', store).replace('{1}', String(count));
}
