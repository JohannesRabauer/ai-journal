// Thinking Gym interactions: prepare an exercise, show it, save the answer, optional follow-up questions. The page is
// rendered by the server (categories, earlier answers); this module only drives the exercise card with the
// server-rendered <template>s and /api/gym. All texts come from data-* attributes and the templates.
import { post, ApiError } from './api.js';
import { durationSeconds, attemptRequest, clock, fill, errorInfo, hasFeedback } from './gym-logic.js';

export function initGym(doc = globalThis.document, deps = {}) {
  const root = doc.getElementById('gym-root');
  if (!root) {
    return null;
  }
  const apiPost = deps.post ?? post;
  const now = deps.now ?? (() => Date.now());
  const reload = deps.reload ?? (() => globalThis.window.location.reload());
  const timers = deps.timers ?? { setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval };
  const attr = (name) => root.getAttribute(`data-${name}`) ?? '';

  const status = doc.getElementById('gym-status');
  const errorBox = doc.getElementById('gym-error');
  const pick = doc.getElementById('gym-pick');
  const history = doc.getElementById('gym-history');
  const box = doc.getElementById('gym-exercise');
  const llm = attr('llm') === 'true';
  const startButtons = [...pick.querySelectorAll('button[data-category]')];

  let busy = false;
  let shownAt = 0;
  let timer = null;
  let saved = false; // something was written: the earlier-answers list is stale

  const setBusy = (key) => {
    busy = true;
    clearError();
    status.textContent = attr(key);
    startButtons.forEach((b) => { b.disabled = true; });
  };
  const clearBusy = () => {
    busy = false;
    status.textContent = '';
    startButtons.forEach((b) => { b.disabled = false; });
  };
  function clearError() {
    errorBox.textContent = '';
  }
  const showError = (code, retry) => {
    errorBox.textContent = '';
    const p = doc.createElement('p');
    p.textContent = attr(`err-${code.toLowerCase()}`) || attr('err-unreachable');
    errorBox.appendChild(p);
    if (retry) {
      const again = doc.createElement('button');
      again.type = 'button';
      again.className = 'diary-button diary-button-quiet';
      again.textContent = attr('retry');
      again.addEventListener('click', () => { clearError(); retry(); });
      errorBox.appendChild(again);
    }
  };
  const fail = (e, retry) => {
    clearBusy();
    const info = errorInfo(e instanceof ApiError ? e : { code: 'NETWORK', status: 0 });
    showError(info.code, info.retryable ? retry : null);
  };

  const ref = (scope, name) => scope.querySelector(`[data-ref="${name}"]`);
  const stopTimer = () => {
    if (timer !== null) {
      timers.clearInterval(timer);
      timer = null;
    }
  };
  const fillFromTemplate = (id) => {
    stopTimer();
    box.textContent = '';
    const fragment = doc.getElementById(id).content.cloneNode(true);
    box.appendChild(fragment);
    return box;
  };

  // ---- picker
  startButtons.forEach((b) => b.addEventListener('click', () => requestExercise(b.getAttribute('data-category') || null)));

  async function requestExercise(category) {
    if (busy) {
      return;
    }
    setBusy('preparing');
    try {
      const path = '/api/gym/exercises' + (category ? `?category=${encodeURIComponent(category)}` : '');
      const exercise = await apiPost(path, undefined, { headers: { 'Accept-Language': attr('lang') } });
      showExercise(exercise);
    } catch (e) {
      fail(e, () => requestExercise(category));
    }
  }

  // ---- exercise
  function showExercise(ex) {
    clearBusy();
    shownAt = now();
    pick.hidden = true;
    history.hidden = true;
    box.hidden = false;
    const el = fillFromTemplate('gym-tpl-exercise');
    const title = ref(el, 'title');
    title.textContent = ex.title;
    ref(el, 'category').textContent = attr(`cat-${String(ex.category).toLowerCase()}`);
    ref(el, 'minutes').textContent = fill(attr('meta'), ex.estimatedMinutes);
    ref(el, 'source').textContent = attr(ex.personalized ? 'source-personalized' : 'source-template');
    ref(el, 'prompt').textContent = ex.prompt;
    if (ex.sourceExcerpt && ex.sourceExcerpt.trim() !== '') {
      ref(el, 'sourceBox').hidden = false;
      ref(el, 'excerpt').textContent = ex.sourceExcerpt;
      if (ex.sourceEntryId) {
        const link = ref(el, 'sourceLink');
        link.href = `/journal/${ex.sourceEntryId}`;
        link.hidden = false;
      }
    }
    const steps = ref(el, 'steps');
    (ex.steps ?? []).forEach((s) => {
      const li = doc.createElement('li');
      li.textContent = s;
      steps.appendChild(li);
    });

    const toggle = ref(el, 'timerToggle');
    const timerBox = ref(el, 'timerBox');
    const timerValue = ref(el, 'timerValue');
    toggle.addEventListener('click', () => {
      const show = timerBox.hidden;
      timerBox.hidden = !show;
      toggle.setAttribute('aria-expanded', String(show));
      toggle.textContent = attr(show ? 'timer-hide' : 'timer-show');
      stopTimer();
      if (show) {
        // counts up in the browser: no server round trips and no live region, so nothing is announced
        const tick = () => { timerValue.textContent = clock(durationSeconds(shownAt, now())); };
        tick();
        timer = timers.setInterval(tick, 1000);
      }
    });

    const answer = ref(el, 'answer');
    const save = async () => {
      const request = attemptRequest(answer.value, durationSeconds(shownAt, now()));
      if (!request) {
        showError('answerEmpty', null);
        answer.focus();
        return;
      }
      if (busy) {
        return;
      }
      setBusy('saving');
      try {
        const attempt = await apiPost(`/api/gym/exercises/${ex.id}/attempts`, request);
        clearBusy();
        saved = true;
        showSaved(ex, attempt);
      } catch (e) {
        fail(e, save);
      }
    };
    ref(el, 'save').addEventListener('click', save);
    ref(el, 'another').addEventListener('click', () => requestExercise(ex.category));
    ref(el, 'back').addEventListener('click', backToPicker);
    title.focus();
  }

  function showSaved(ex, attemptDto) {
    const el = fillFromTemplate('gym-tpl-saved');
    const message = ref(el, 'message');
    const ask = ref(el, 'ask');
    const feedbackBox = ref(el, 'feedbackBox');
    if (llm) {
      ref(el, 'feedbackAsk').hidden = false;
    }
    const askFor = async () => {
      if (busy) {
        return;
      }
      setBusy('feedback-working');
      ask.disabled = true;
      try {
        const updated = await apiPost(`/api/gym/attempts/${attemptDto.id}/feedback`);
        clearBusy();
        if (!hasFeedback(updated)) {
          ask.disabled = false;
          return;
        }
        ref(el, 'feedbackAsk').hidden = true;
        const list = ref(el, 'questions');
        updated.feedbackQuestions.forEach((q) => {
          const li = doc.createElement('li');
          li.textContent = q;
          list.appendChild(li);
        });
        feedbackBox.hidden = false;
        ref(el, 'feedbackTitle').focus();
      } catch (e) {
        ask.disabled = false;
        fail(e, askFor);
      }
    };
    ask.addEventListener('click', askFor);
    ref(el, 'another').addEventListener('click', () => requestExercise(ex.category));
    ref(el, 'back').addEventListener('click', backToPicker);
    message.focus();
  }

  function backToPicker() {
    stopTimer();
    clearError();
    if (saved) {
      reload(); // the earlier answers are rendered by the server: show the new one
      return;
    }
    box.textContent = '';
    box.hidden = true;
    pick.hidden = false;
    history.hidden = false;
    const heading = doc.getElementById('gym-pick-title');
    heading.setAttribute('tabindex', '-1');
    heading.focus();
  }

  return { requestExercise };
}

if (globalThis.document && globalThis.document.getElementById) {
  initGym();
}
