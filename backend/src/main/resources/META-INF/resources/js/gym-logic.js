// Pure logic of the Thinking Gym page (no DOM). Mirrors GymPresenter on the server.

/** The longest practice time that is reported; a tab left open for hours is not "practice time". */
export const MAX_DURATION_SECONDS = 2 * 60 * 60;

/** Seconds spent on an exercise since it was shown: never negative, capped. */
export function durationSeconds(shownAtMs, nowMs) {
  if (!Number.isFinite(shownAtMs) || !Number.isFinite(nowMs)) {
    return 0;
  }
  return Math.max(0, Math.min(MAX_DURATION_SECONDS, Math.floor((nowMs - shownAtMs) / 1000)));
}

/** The attempt to save, or null when nothing is written yet. */
export function attemptRequest(answer, seconds) {
  return answer === null || answer === undefined || String(answer).trim() === ''
    ? null
    : { answer: String(answer).trim(), durationSeconds: Math.max(0, seconds) };
}

/** "m:ss" for the optional timer. */
export function clock(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** Fills "About {0} minutes". */
export function fill(template, value) {
  return String(template).replace('{0}', String(value));
}

/**
 * Calm, user-presentable explanation of a failed call: a short code that selects a data-err-<code> text of the page.
 * @param {{code?: string, status?: number}|null|undefined} error
 * @returns {{code: string, retryable: boolean}} code: unreachable, aiInvalid, aiUnavailable, answerEmpty, answerTooLong, notFound, unavailable
 */
export function errorInfo(error) {
  const code = error && typeof error.code === 'string' ? error.code : '';
  const status = error && typeof error.status === 'number' ? error.status : 0;
  if (code === 'LLM_OUTPUT_INVALID') {
    return { code: 'aiInvalid', retryable: true };
  }
  if (code.startsWith('PROVIDER_')) {
    return { code: 'aiUnavailable', retryable: true };
  }
  if (code === 'ANSWER_EMPTY') {
    return { code: 'answerEmpty', retryable: false };
  }
  if (code === 'ANSWER_TOO_LONG') {
    return { code: 'answerTooLong', retryable: false };
  }
  if (status === 404) {
    return { code: 'notFound', retryable: false };
  }
  if (status === 501) {
    return { code: 'unavailable', retryable: false };
  }
  return { code: 'unreachable', retryable: true };
}

/** True when the answer has follow-up questions to show. */
export function hasFeedback(attempt) {
  return !!attempt && Array.isArray(attempt.feedbackQuestions) && attempt.feedbackQuestions.length > 0;
}
