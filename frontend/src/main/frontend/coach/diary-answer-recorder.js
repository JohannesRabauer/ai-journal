// <diary-answer-recorder>: record a short spoken answer and turn it into text for review. Single blob via
// MediaRecorder, hard limit ~2 minutes, visible timer, stop and cancel. The clip is uploaded once to the transcribe
// proxy and is not kept anywhere. No AI output is produced here.
// Attributes: language (auto|de|en), labels (JSON), transcribe-url (default /upload/transcribe).
// Events (bubbling, composed):
//   answer-transcribed {text, detectedLanguage}
//   answer-error       {kind}  kind: mic-denied | unsupported | unavailable | too-large | empty | failed
//   answer-busy        {busy}  true while recording or transcribing

import { AnswerState, MAX_ANSWER_MS, createAnswerRecorder } from './answer-recorder.js';
import { pageGuard } from './page-guard.js';

const DEFAULT_LABELS = {
  record: 'Answer by voice',
  stop: 'Stop and transcribe',
  cancel: 'Cancel recording',
  elapsed: 'Elapsed time',
  requesting: 'Waiting for microphone permission…',
  recording: 'Recording. Press stop when you are done.',
  transcribing: 'Turning your answer into text…',
  done: 'Your answer is in the text field. Read it, change it if needed, then send it.',
  cancelled: 'Recording discarded.',
  'error.mic-denied': 'The microphone is not available. You can type your answer instead.',
  'error.unsupported': 'Voice answers are not supported in this browser. You can type your answer instead.',
  'error.unavailable': 'Speech recognition is not available right now. You can type your answer instead.',
  'error.too-large': 'The recording is too long. Try a shorter answer or type it.',
  'error.empty': 'No speech was recognised. Try again or type your answer.',
  'error.failed': 'Something went wrong. Try again or type your answer.',
};

const TEMPLATE = `
<style>
  :host { display: block; }
  .row { display: flex; align-items: center; gap: .75rem; flex-wrap: wrap; }
  .time { font-variant-numeric: tabular-nums; font-size: 1.25rem; min-width: 5ch; }
  button { font: inherit; min-height: 44px; min-width: 44px; padding: 0 1.1rem; border-radius: 22px; cursor: pointer;
           border: 1px solid var(--lumo-contrast-60pct, #777); background: var(--lumo-base-color, #fff);
           color: var(--lumo-body-text-color, #1b1b1b); }
  button:focus-visible { outline: 3px solid var(--lumo-primary-color, #2c5f66); outline-offset: 2px; }
  button[hidden], .time[hidden] { display: none; }
  button[disabled] { cursor: default; opacity: .7; }
  .dot { display: inline-block; width: .7em; height: .7em; border-radius: 50%; margin-right: .4em;
         background: var(--lumo-error-color, #a4262c); }
  :host([data-state="recording"]) .dot { animation: pulse 1.4s ease-in-out infinite; }
  @media (prefers-reduced-motion: reduce) { .dot { animation: none !important; } }
  @keyframes pulse { 50% { opacity: .35; } }
  .status { min-height: 1.4em; margin-top: .5rem; color: var(--lumo-secondary-text-color, #575d64); }
</style>
<div class="row">
  <button type="button" id="record"><span class="dot" aria-hidden="true"></span><span id="record-text"></span></button>
  <output class="time" id="time" role="timer" aria-live="off" hidden>00:00</output>
  <button type="button" id="stop" hidden></button>
  <button type="button" id="cancel" hidden></button>
</div>
<div class="status" id="status" role="status" aria-live="polite"></div>`;

function formatClock(ms) {
  const total = Math.floor(ms / 1000);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

export class DiaryAnswerRecorder extends HTMLElement {
  #session;
  #labels = DEFAULT_LABELS;
  #tick = 0;

  connectedCallback() {
    if (this.shadowRoot) {
      return;
    }
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = TEMPLATE;
    try {
      this.#labels = { ...DEFAULT_LABELS, ...JSON.parse(this.getAttribute('labels') ?? '{}') };
    } catch {
      // keep defaults
    }
    const $ = (selector) => root.querySelector(selector);
    this.els = {
      record: $('#record'), recordText: $('#record-text'), time: $('#time'), stop: $('#stop'),
      cancel: $('#cancel'), status: $('#status'),
    };
    this.els.recordText.textContent = this.#labels.record;
    this.els.stop.textContent = this.#labels.stop;
    this.els.cancel.textContent = this.#labels.cancel;
    this.els.time.setAttribute('aria-label', this.#labels.elapsed);

    this.#session = createAnswerRecorder({
      mediaDevices: navigator.mediaDevices,
      MediaRecorder: globalThis.MediaRecorder,
      guard: pageGuard(),
      transcribe: (blob, type) => this.#transcribe(blob, type),
      now: () => Date.now(),
      setTimer: (fn, ms) => setTimeout(fn, ms),
      clearTimer: (id) => clearTimeout(id),
      onEvent: (event) => this.#onEvent(event),
    });
    this.els.record.addEventListener('click', () => this.#session.start());
    this.els.stop.addEventListener('click', () => this.#session.stop());
    this.els.cancel.addEventListener('click', () => {
      this.#session.cancel();
      this.#say(this.#labels.cancelled);
      this.els.record.focus();
    });
    this.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && this.#session.state === AnswerState.RECORDING) {
        this.#session.cancel();
        this.#say(this.#labels.cancelled);
        this.els.record.focus();
      }
    });
    this.#render(AnswerState.IDLE);
  }

  disconnectedCallback() {
    clearInterval(this.#tick);
    this.#session?.cancel();
  }

  async #transcribe(blob, type) {
    const language = (this.getAttribute('language') ?? 'auto').toUpperCase();
    const base = this.getAttribute('transcribe-url') ?? '/upload/transcribe';
    const response = await fetch(`${base}?language=${encodeURIComponent(language)}`, {
      method: 'POST',
      headers: { 'Content-Type': type.split(';')[0], 'X-AI-Diary': '1' },
      body: blob,
    });
    if (!response.ok) {
      throw Object.assign(new Error('transcribe failed'), { status: response.status });
    }
    return response.json();
  }

  #emit(name, detail) {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  #say(text) {
    this.els.status.textContent = text;
  }

  #onEvent(event) {
    if (event.type === 'state') {
      this.#render(event.state);
    } else if (event.type === 'transcript') {
      this.#say(this.#labels.done);
      this.#emit('answer-transcribed', { text: event.text, detectedLanguage: event.detectedLanguage });
    } else if (event.type === 'error') {
      this.#say(this.#labels[`error.${event.kind}`] ?? this.#labels['error.failed']);
      this.#emit('answer-error', { kind: event.kind });
    }
  }

  #render(state) {
    this.setAttribute('data-state', `answer-${state}`);
    const recording = state === AnswerState.RECORDING;
    const busy = state !== AnswerState.IDLE;
    this.els.record.disabled = busy;
    this.els.record.hidden = recording;
    this.els.stop.hidden = !recording;
    this.els.cancel.hidden = !(recording || state === AnswerState.TRANSCRIBING);
    this.els.time.hidden = !recording;
    clearInterval(this.#tick);
    if (recording) {
      const update = () => {
        this.els.time.textContent = `${formatClock(this.#session.elapsedMs())} / ${formatClock(MAX_ANSWER_MS)}`;
      };
      update();
      this.#tick = setInterval(update, 500);
      this.#say(this.#labels.recording);
      this.els.stop.focus();
    } else if (state === AnswerState.REQUESTING) {
      this.#say(this.#labels.requesting);
    } else if (state === AnswerState.TRANSCRIBING) {
      this.#say(this.#labels.transcribing);
    }
    this.#emit('answer-busy', { busy });
  }
}

if (!customElements.get('diary-answer-recorder')) {
  customElements.define('diary-answer-recorder', DiaryAnswerRecorder);
}
