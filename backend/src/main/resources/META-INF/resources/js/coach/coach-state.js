// Small pure decisions of the coach panel (ports of the Vaadin CoachPresenter helpers). The server renders the
// conversation; these only decide what to present after an in-place update.

export function isQuestion(kind) {
  return kind === 'OPENING_QUESTION' || kind === 'FOLLOW_UP_QUESTION';
}

/** What a conversation status offers (SPEC 5.9). */
export function actionsFor(status) {
  switch (status) {
    case 'ACTIVE': return { answer: true, pause: true, resume: false, end: true, startNew: false };
    case 'PAUSED': return { answer: false, pause: false, resume: true, end: true, startNew: false };
    default: return { answer: false, pause: false, resume: false, end: false, startNew: true };
  }
}

/** The question the user is answering now: the last message, when it is a coach question and the chat is active. */
export function currentQuestion(conversation) {
  const messages = conversation?.messages ?? [];
  if (conversation?.status !== 'ACTIVE' || messages.length === 0) {
    return null;
  }
  const last = messages[messages.length - 1];
  return last.role === 'COACH' && isQuestion(last.kind) ? last : null;
}

/** Coach questions that were not shown before; these are the only ones read aloud automatically. */
export function newQuestions(shown, conversation) {
  return (conversation?.messages ?? [])
    .filter((m) => m.role === 'COACH' && isQuestion(m.kind) && !shown.has(m.id));
}

/** The language to speak in: the conversation language, else the UI language, else English. */
export function speechLanguage(conversationLanguage, uiLanguage) {
  const c = String(conversationLanguage ?? '').toUpperCase();
  if (c === 'DE' || c === 'EN') {
    return c;
  }
  return String(uiLanguage ?? '').toLowerCase().startsWith('de') ? 'DE' : 'EN';
}

export function voiceFor(language, germanVoice, englishVoice) {
  const voice = language === 'DE' ? germanVoice : englishVoice;
  return voice && String(voice).trim() ? voice : null;
}

/**
 * A calm explanation of a failed call, as an i18n key. Never uses the backend's message text.
 * @param {{code?: string, status?: number}} failure an ApiError (api.js)
 * @returns {{messageKey: string, retryable: boolean, reload: boolean}}
 */
export function errorFor(failure) {
  const code = failure?.code ?? '';
  if (code === 'LLM_OUTPUT_INVALID') {
    return { messageKey: 'coach.error.invalid', retryable: true, reload: false };
  }
  if (code.startsWith('PROVIDER_')) {
    return { messageKey: 'coach.error.provider', retryable: true, reload: false };
  }
  switch (failure?.status) {
    case 409: return { messageKey: 'coach.error.state', retryable: false, reload: true };
    case 404: return { messageKey: 'coach.error.notFound', retryable: false, reload: false };
    case 501: return { messageKey: 'coach.error.unavailable', retryable: false, reload: false };
    default: return { messageKey: 'coach.error.unreachable', retryable: true, reload: false };
  }
}
