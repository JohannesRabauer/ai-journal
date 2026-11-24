// Coach panel script (entry page /journal/{id} and /journal/{id}/reflect). Progressive enhancement: without it the
// server-rendered panel works through real forms (templates/JournalResource/coachPanel.html, coachBody.html); with it
// the actions run in place: the JSON API is called with api.js, then the panel body is replaced by the server's fresh
// rendering (GET /journal/{id}/reflect?fragment=1). The behaviour lives in coach/coach-controller.js; this file only
// connects it to the DOM. Texts come from the page (data-texts); the modules contain none of their own.
import { get, post } from './api.js';
import { confirmDialog } from './confirm.js';
import './coach/coach-speech.js';
import './coach/diary-answer-recorder.js';
import { pageGuard } from './coach/page-guard.js';
import { createCoachController } from './coach/coach-controller.js';

function enhance(root) {
  // Voice answers, read-aloud buttons and the end dialog exist only with JavaScript.
  root.querySelectorAll('[data-action="play"][hidden]').forEach((b) => { b.hidden = false; });
  root.querySelectorAll('form[data-coach-form="end"]').forEach((form) => {
    const summary = form.querySelector('summary');
    const details = form.querySelector('details');
    if (!summary || !details) {
      return;
    }
    const button = document.createElement('button');
    button.type = 'submit';
    button.className = 'entries-ghost';
    summary.childNodes.forEach((n) => button.appendChild(n.cloneNode(true)));
    details.replaceWith(button);
  });
}

function init() {
  const root = document.getElementById('coach');
  if (!root) {
    return;
  }
  const texts = JSON.parse(root.getAttribute('data-texts') || '{}');
  const entryId = Number(root.getAttribute('data-entry-id'));
  const thinking = root.querySelector('[data-coach-thinking]');
  const errorBox = root.querySelector('[data-coach-error]');
  const errorText = root.querySelector('[data-coach-error-text]');
  const retryButton = root.querySelector('[data-coach-retry]');
  const speech = root.querySelector('diary-coach-speech');
  let busyFlag = false;
  let voiceFlag = false;
  let retryAction = null;

  const body = () => root.querySelector('[data-coach-body]');
  const answerField = () => root.querySelector('#coach-answer');

  /** Pause, end and send are held while the coach is busy or a voice answer is being recorded; the text is read-only while busy. */
  function applyEnabled() {
    const off = busyFlag || voiceFlag;
    const field = answerField();
    if (field) {
      field.readOnly = busyFlag;
    }
    body()?.querySelectorAll('[data-coach-send], [data-coach-start], .coach-controls button, .coach-controls summary')
      .forEach((c) => { c.disabled = off; });
  }

  const view = {
    setBusy(text) {
      busyFlag = Boolean(text);
      thinking.hidden = !text;
      thinking.textContent = text ?? '';
      applyEnabled();
    },
    setVoiceBusy(value) {
      voiceFlag = value;
      applyEnabled();
    },
    clearError() {
      errorBox.hidden = true;
      errorText.textContent = '';
      retryButton.hidden = true;
      retryAction = null;
    },
    showError(text, retry) {
      errorText.textContent = text;
      errorBox.hidden = false;
      retryAction = retry;
      retryButton.hidden = !retry;
      // The button that was pressed was disabled while busy; keep keyboard users in the error box.
      if (retry) {
        retryButton.focus();
      }
    },
    readAnswer: () => answerField()?.value ?? '',
    setAnswer(text) {
      const field = answerField();
      if (field) {
        field.value = text;
      }
    },
    answerField,
    focusAnswer: () => answerField()?.focus(),
    focusControls: () => body()?.querySelector('.coach-controls button, .coach-controls a')?.focus(),
    focusStart: () => body()?.querySelector('[data-coach-start]')?.focus(),
    async swap({ fresh }) {
      const response = await fetch(`/journal/${entryId}/reflect?fragment=1${fresh ? '&new=1' : ''}`, {
        credentials: 'same-origin', headers: { Accept: 'text/html' },
      });
      if (!response.ok) {
        throw new Error(`panel ${response.status}`);
      }
      const holder = document.createElement('template');
      holder.innerHTML = await response.text();
      const next = holder.content.querySelector('[data-coach-body]');
      if (!next) {
        throw new Error('panel body missing');
      }
      body().replaceWith(next);
      enhance(root);
      applyEnabled();
    },
    reload: () => globalThis.location.reload(),
  };

  const bodyEl = body();
  const controller = createCoachController({
    entryId,
    texts,
    api: { get, post },
    view,
    speech: {
      enqueue: (...a) => speech.enqueue(...a),
      play: (...a) => speech.play(...a),
      announce: (...a) => speech.announce(...a),
      focusWhenIdle: (...a) => speech.focusWhenIdle(...a),
    },
    guard: pageGuard(),
    confirm: confirmDialog,
    settings: {
      autoPlay: root.getAttribute('data-autoplay') === 'true',
      voiceDe: root.getAttribute('data-voice-de') || null,
      voiceEn: root.getAttribute('data-voice-en') || null,
      uiLanguage: document.documentElement.lang,
    },
    conversationId: Number(bodyEl.getAttribute('data-conversation-id')) || null,
    language: bodyEl.getAttribute('data-conv-language') || 'auto',
    shownIds: [...bodyEl.querySelectorAll('[data-message-id]')].map((e) => Number(e.getAttribute('data-message-id'))),
  });

  enhance(root);

  const handlers = {
    start: () => controller.start(),
    answer: () => controller.answer(),
    pause: () => controller.pause(),
    resume: () => controller.resume(),
    end: () => controller.end(),
  };
  root.addEventListener('submit', (event) => {
    const form = event.target.closest?.('form[data-coach-form]');
    const handler = form && handlers[form.getAttribute('data-coach-form')];
    if (handler) {
      event.preventDefault();
      handler();
    }
  });
  root.addEventListener('click', (event) => {
    const play = event.target.closest?.('[data-action="play"]');
    if (play) {
      const item = play.closest('[data-message-id]');
      controller.play(Number(item.getAttribute('data-message-id')),
        item.querySelector('.coach-message__body').textContent);
      return;
    }
    const startNew = event.target.closest?.('a[data-coach-new]');
    if (startNew) {
      event.preventDefault();
      controller.startNew();
      return;
    }
    if (event.target.closest?.('[data-coach-retry]') && retryAction) {
      retryAction();
    }
  });
  root.addEventListener('input', (event) => {
    if (event.target.id === 'coach-answer') {
      controller.draftEdited(event.target.value);
    }
  });
  // Ctrl+Enter sends the answer.
  root.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && event.target.id === 'coach-answer') {
      event.preventDefault();
      controller.answer();
    }
  });
  root.addEventListener('answer-transcribed', (event) => controller.transcribed(event.detail.text));
  root.addEventListener('answer-busy', (event) => controller.voiceBusy(event.detail.busy));
}

if (typeof document !== 'undefined' && document.getElementById) {
  init();
}
