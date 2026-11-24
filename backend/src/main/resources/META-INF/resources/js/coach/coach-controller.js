// The coach panel's behaviour (port of the Vaadin CoachView flow), free of DOM details so it runs under node --test.
// The server renders the conversation; this calls the JSON API, then asks the view to swap in the server's fresh
// panel body, and presents what is new (read aloud, announced, focused). Rules kept from the Vaadin screen:
//   - the coach starts only when the user chooses; every new message is the reply to something the user did
//   - while any recording is active the reply is held back: nothing is shown, spoken or announced until it ends
//   - one action at a time; the panel shows a calm "thinking" line meanwhile (no animation)
//   - a failed send keeps the answer; errors are calm and offer a retry when repeating is safe
//   - 409 (the conversation changed elsewhere): the current state is loaded and shown
import { createAnswerDraft } from './answer-draft.js';
import {
  actionsFor, currentQuestion, errorFor, newQuestions, speechLanguage, voiceFor,
} from './coach-state.js';

/**
 * @param {object} deps
 * @param {number} deps.entryId
 * @param {Record<string, string>} deps.texts
 * @param {{post: Function, get: Function}} deps.api api.js style: resolve parsed JSON, reject with {code, status}
 * @param {object} deps.view DOM side: setBusy(text|null), setVoiceBusy(bool), showError(text, retry|null),
 *   clearError(), readAnswer(), setAnswer(text), answerField(), swap({fresh}), focusAnswer(), focusControls(),
 *   focusStart(), reload()
 * @param {{enqueue: Function, play: Function, announce: Function, focusWhenIdle: Function}} deps.speech
 * @param {{isRecording: () => boolean, onChange: (fn: (recording: boolean) => void) => () => void}} deps.guard
 * @param {(options: object) => Promise<boolean>} deps.confirm
 * @param {{autoPlay: boolean, voiceDe: string|null, voiceEn: string|null, uiLanguage: string}} deps.settings
 * @param {number|null} [deps.conversationId] the conversation on the page, if any
 * @param {string} [deps.language] its language (de, en, auto)
 * @param {number[]} [deps.shownIds] ids of the messages already on the page
 */
export function createCoachController(deps) {
  const { entryId, texts, api, view, speech, guard, confirm, settings } = deps;
  const shown = new Set(deps.shownIds ?? []);
  const draft = createAnswerDraft();
  let conversationId = deps.conversationId || null;
  let language = deps.language ?? 'auto';
  let busy = false;
  let held = null;
  let refocus = false;
  const t = (key) => texts[key] ?? '';

  function present(questions, conversation) {
    if (questions.length === 0) {
      return;
    }
    const lang = speechLanguage(conversation.language, settings.uiLanguage);
    if (settings.autoPlay) {
      for (const q of questions) {
        speech.enqueue(q.id, q.content, lang, voiceFor(lang, settings.voiceDe, settings.voiceEn));
      }
    }
    speech.announce(t('coach.announce.question').replace('{0}', questions[questions.length - 1].content));
    if (actionsFor(conversation.status).answer) {
      speech.focusWhenIdle(view.answerField());
    }
  }

  async function accept(conversation, reveal, onAccepted) {
    onAccepted?.();
    conversationId = conversation.id;
    language = conversation.language ?? 'auto';
    let toPresent = [];
    if (reveal === 'new') {
      toPresent = newQuestions(shown, conversation);
    } else if (reveal === 'current') {
      const q = currentQuestion(conversation);
      toPresent = q ? [q] : [];
    }
    (conversation.messages ?? []).forEach((m) => shown.add(m.id));
    try {
      await view.swap({ fresh: false });
    } catch {
      // the server accepted the action but its page could not be loaded: a reload shows the real state
      view.reload();
      return;
    }
    busy = false;
    view.setBusy(null);
    if (draft.text()) {
      view.setAnswer(draft.text());
    }
    if (refocus) {
      refocus = false;
      view.focusControls();
    }
    present(toPresent, conversation);
  }

  async function reloadAfterConflict(info) {
    busy = false;
    view.setBusy(null);
    try {
      const fresh = await api.get(`/api/conversations/${conversationId}`);
      (fresh?.messages ?? []).forEach((m) => shown.add(m.id));
      await view.swap({ fresh: false });
    } catch {
      // keep what is shown; the message below still explains
    }
    view.showError(t(info.messageKey), null);
  }

  async function run(call, reveal, retry, onAccepted) {
    if (busy) {
      return;
    }
    busy = true;
    view.setBusy(t('coach.thinking'));
    view.clearError();
    let result;
    try {
      result = await call();
    } catch (failure) {
      const info = errorFor(failure);
      if (info.reload && conversationId) {
        await reloadAfterConflict(info);
        return;
      }
      busy = false;
      view.setBusy(null);
      view.showError(t(info.messageKey), info.retryable ? retry : null);
      return;
    }
    // The coach never posts while the user records: hold the reply until the recording is over.
    if (guard.isRecording()) {
      held = { result, reveal, onAccepted };
      view.setBusy(t('coach.waitingForRecording'));
      return;
    }
    await accept(result, reveal, onAccepted);
  }

  guard.onChange((recording) => {
    if (!recording && held) {
      const { result, reveal, onAccepted } = held;
      held = null;
      accept(result, reveal, onAccepted);
    }
  });

  function start() {
    return run(() => api.post('/api/conversations', { entryId }), 'new', start);
  }

  function answer() {
    draft.edited(view.readAnswer());
    const request = draft.request();
    if (busy || !conversationId || !request) {
      return Promise.resolve();
    }
    const id = conversationId;
    return run(() => api.post(`/api/conversations/${id}/answers`, request), 'new', answer, () => draft.sent());
  }

  function pause() {
    refocus = true;
    const id = conversationId;
    return run(() => api.post(`/api/conversations/${id}/pause`), 'quiet', pause);
  }

  function resume() {
    refocus = true;
    const id = conversationId;
    return run(() => api.post(`/api/conversations/${id}/resume`), 'current', resume);
  }

  function endNow() {
    refocus = true;
    const id = conversationId;
    return run(() => api.post(`/api/conversations/${id}/end`), 'quiet', endNow);
  }

  async function end() {
    const confirmed = await confirm({
      title: t('coach.end.title'),
      message: t('coach.end.text'),
      confirmLabel: t('coach.end.confirm'),
      cancelLabel: t('coach.cancel'),
      destructive: false,
    });
    if (confirmed) {
      await endNow();
    }
  }

  /** "Start a new reflection" after an ended one: back to the calm start state; nothing runs until the user starts. */
  async function startNew() {
    if (busy) {
      return;
    }
    try {
      await view.swap({ fresh: true });
    } catch {
      view.reload();
      return;
    }
    conversationId = null;
    language = 'auto';
    shown.clear();
    draft.sent();
    view.clearError();
    view.focusStart();
  }

  return {
    start, answer, pause, resume, end, startNew,
    /** The user typed in the answer field. */
    draftEdited: (value) => draft.edited(value),
    /** A voice answer was transcribed: add it to the field for review. */
    transcribed(text) {
      view.setAnswer(draft.transcribed(text));
      view.focusAnswer();
    },
    voiceBusy: (value) => view.setVoiceBusy(value),
    /** The per-message play button. */
    play(id, text) {
      const lang = speechLanguage(language, settings.uiLanguage);
      speech.play(id, text, lang, voiceFor(lang, settings.voiceDe, settings.voiceEn));
    },
    get busy() { return busy; },
    get conversationId() { return conversationId; },
  };
}
