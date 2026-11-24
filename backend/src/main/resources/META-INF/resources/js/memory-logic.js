// Pure logic of the Remember page (no DOM). Mirrors MemoryPresenter on the server: the grade buttons, the review
// session (prompt first, answer only after an attempt), calm error codes, validation of an edited suggestion. No scores,
// percentages or streaks: only the cards themselves and "remaining".

export const MAX_PROMPT = 500;
export const MAX_ANSWER = 2000;

/** The four grade buttons with their SM-2 quality (SPEC section 6) and the keyboard key. */
export const GRADES = [
  { key: 'forgot', quality: 1, digit: '1' },
  { key: 'hard', quality: 3, digit: '2' },
  { key: 'good', quality: 4, digit: '3' },
  { key: 'easy', quality: 5, digit: '4' },
];

export function gradeForDigit(digit) {
  return GRADES.find((g) => g.digit === digit) ?? null;
}

/** "in 1 day" / "in 6 days": the projected next interval shown on a grade button. */
export function formatInterval(days, oneText, otherText) {
  return days === 1 ? String(oneText) : String(otherText).replace('{0}', String(days));
}

/** Typed answer to send; blank means "I answered in my head" (null). */
export function attemptText(typed) {
  return typed === null || typed === undefined || String(typed).trim() === '' ? null : String(typed).trim();
}

/** Validation of an edited suggestion before it is sent: a short code (promptEmpty ...) or null. */
export function editProblem(prompt, answer) {
  if (prompt === null || prompt === undefined || String(prompt).trim() === '') {
    return 'promptEmpty';
  }
  if (String(prompt).trim().length > MAX_PROMPT) {
    return 'promptTooLong';
  }
  if (answer === null || answer === undefined || String(answer).trim() === '') {
    return 'answerEmpty';
  }
  if (String(answer).trim().length > MAX_ANSWER) {
    return 'answerTooLong';
  }
  return null;
}

/**
 * Calm explanation of a failed call as a short code selecting a data-err-<code> text.
 * @param {{code?: string, status?: number}|null|undefined} error
 * @returns {{code: string, retryable: boolean, refresh: boolean}} refresh: the data changed elsewhere, reload the lists
 */
export function errorInfo(error) {
  const code = error && typeof error.code === 'string' ? error.code : '';
  const status = error && typeof error.status === 'number' ? error.status : 0;
  if (code === 'LLM_OUTPUT_INVALID') {
    return { code: 'invalid', retryable: true, refresh: false };
  }
  if (code.startsWith('PROVIDER_')) {
    return { code: 'provider', retryable: true, refresh: false };
  }
  if (status === 409) {
    if (code === 'ENTRY_NOT_FINISHED') {
      return { code: 'notFinished', retryable: false, refresh: false };
    }
    if (code === 'ENTRY_EMPTY' || code === 'CONVERSATION_EMPTY') {
      return { code: 'empty', retryable: false, refresh: false };
    }
    return { code: 'state', retryable: false, refresh: true };
  }
  if (status === 404) {
    return { code: 'notFound', retryable: false, refresh: true };
  }
  if (status === 400) {
    return { code: 'badInput', retryable: false, refresh: false };
  }
  if (status === 501) {
    return { code: 'unavailable', retryable: false, refresh: false };
  }
  return { code: 'unreachable', retryable: true, refresh: false };
}

/** "entry" or "conversation" plus a positive id from the page address, otherwise null. */
export function extractionTarget(source, id) {
  const kind = String(source ?? '').toLowerCase();
  const n = Number.parseInt(String(id ?? '').trim(), 10);
  if ((kind !== 'entry' && kind !== 'conversation') || !Number.isInteger(n) || n <= 0) {
    return null;
  }
  return { sourceType: kind.toUpperCase(), sourceId: n };
}

export const PHASE = { PROMPT: 'prompt', REVEALED: 'revealed', DONE: 'done' };

/**
 * The due cards in order. The answer is only known to the session after revealed(); before that there is nothing to show
 * but the prompt (retrieval practice).
 */
export class ReviewSession {
  constructor(cards) {
    this.queue = Array.isArray(cards) ? [...cards] : [];
    this.total = this.queue.length;
    this.reveal = null;
  }

  /** 1-based position of the current card within the session. */
  position() {
    return Math.min(this.total, this.total - this.queue.length + 1);
  }

  phase() {
    if (this.queue.length === 0) {
      return PHASE.DONE;
    }
    return this.reveal === null ? PHASE.PROMPT : PHASE.REVEALED;
  }

  current() {
    return this.queue.length === 0 ? null : this.queue[0];
  }

  /** Cards still due in this session including the current one. */
  remaining() {
    return this.queue.length;
  }

  revealed(dto) {
    if (this.queue.length > 0 && dto && dto.itemId === this.queue[0].id) {
      this.reveal = dto;
    }
  }

  /** The current card was graded (or no longer exists): move on. */
  graded() {
    if (this.queue.length > 0) {
      this.queue.shift();
    }
    this.reveal = null;
  }

  /** The card was deleted elsewhere on the page. */
  removeItem(id) {
    const wasCurrent = this.queue.length > 0 && this.queue[0].id === id;
    const before = this.queue.length;
    this.queue = this.queue.filter((c) => c.id !== id);
    this.total -= before - this.queue.length;
    if (wasCurrent) {
      this.reveal = null;
    }
  }
}
