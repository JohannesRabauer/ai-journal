// <diary-recorder>: accessible voice recorder (record / pause / stop, live waveform, elapsed time, resume panel).
// Plain custom element, no external dependencies. Events (bubbling, composed):
//   recording-started   {recordingId, entryId}
//   recording-completed {recordingId, entryId, durationMs}
//   upload-error        {kind, message}
// Attributes: entry-id (optional, existing entry), language (auto|de|en), labels (JSON, i18n), upload-url.
// No AI output of any kind is produced or shown here.

import { createUploadApi } from './upload-api.js';
import { formatElapsed } from './format.js';
import { RecorderSession } from './recorder-session.js';
import { createDefaultStorage } from './storage.js';
import { State } from './state-machine.js';
import { WaveformView } from './waveform.js';

const DEFAULT_LABELS = {
  record: 'Record',
  pause: 'Pause',
  resume: 'Resume',
  stop: 'Stop and save',
  elapsed: 'Elapsed time',
  level: 'Input level',
  idle: 'Ready to record.',
  requesting: 'Waiting for microphone permission…',
  recording: 'Recording.',
  paused: 'Paused.',
  stopping: 'Finishing recording…',
  uploading: 'Saving your recording…',
  pending: 'Saving… {0} part(s) still to upload.',
  waiting: 'Waiting for the connection. Your audio is safe on this device and will be uploaded automatically.',
  done: 'Recording saved.',
  retry: 'Try again',
  recoverTitle: 'An unfinished recording was found',
  recoverText: 'Your audio is safe. Finish it to save and transcribe what was recorded.',
  finish: 'Finish recording',
};

const TEMPLATE = `
<style>
  :host { display: block; }
  .root { display: flex; flex-direction: column; gap: .75rem; align-items: stretch; }
  .wave { position: relative; height: 64px; border-radius: 12px; overflow: hidden;
          background: var(--lumo-contrast-5pct, rgba(0,0,0,.05)); }
  canvas { width: 100%; height: 100%; display: block; }
  .level { display: none; height: 100%; align-items: center; padding: 0 .75rem; }
  .level-track { width: 100%; height: 10px; border-radius: 5px; background: var(--lumo-contrast-20pct, rgba(0,0,0,.2)); overflow: hidden; }
  .level-fill { height: 100%; width: 0%; background: var(--lumo-primary-color, #1a5fb4); }
  :host(.reduced) canvas { display: none; }
  :host(.reduced) .level { display: flex; }
  .row { display: flex; align-items: center; gap: .75rem; flex-wrap: wrap; }
  .time { font-variant-numeric: tabular-nums; font-size: 1.5rem; min-width: 5ch; }
  button { font: inherit; min-height: 44px; min-width: 44px; padding: 0 1.1rem; border-radius: 22px; cursor: pointer;
           border: 1px solid var(--lumo-contrast-60pct, #777); background: var(--lumo-base-color, #fff);
           color: var(--lumo-body-text-color, #1b1b1b); }
  button.primary { background: var(--lumo-primary-color, #1a5fb4); color: var(--lumo-primary-contrast-color, #fff); border-color: transparent; }
  button:focus-visible { outline: 3px solid var(--lumo-primary-color, #1a5fb4); outline-offset: 2px; }
  button[hidden] { display: none; }
  .dot { display: inline-block; width: .7em; height: .7em; border-radius: 50%; margin-right: .4em;
         background: var(--lumo-error-color, #c01c28); vertical-align: baseline; }
  :host([data-state="recording"]) .dot { animation: pulse 1.4s ease-in-out infinite; }
  @media (prefers-reduced-motion: reduce) { .dot { animation: none !important; } }
  @keyframes pulse { 50% { opacity: .35; } }
  .status { min-height: 1.4em; color: var(--lumo-secondary-text-color, #4a4a4a); }
  .recover { padding: .75rem 1rem; border-radius: 12px; border: 1px solid var(--lumo-contrast-60pct, #777); }
  .recover[hidden] { display: none; }
  .recover p { margin: 0 0 .5rem; }
</style>
<div class="root">
  <div class="recover" part="recover" hidden role="group" aria-labelledby="rt">
    <p><strong id="rt"></strong></p>
    <p id="rx"></p>
    <div class="row">
      <button type="button" class="primary" id="rfinish"></button>
    </div>
  </div>
  <div class="wave" aria-hidden="true">
    <canvas width="600" height="64"></canvas>
    <div class="level"><div class="level-track"><div class="level-fill"></div></div></div>
  </div>
  <div class="row">
    <output class="time" id="time" role="timer" aria-label="" aria-live="off">00:00</output>
    <button type="button" class="primary" id="record"><span class="dot" aria-hidden="true"></span><span class="t"></span></button>
    <button type="button" id="pause" hidden></button>
    <button type="button" id="stop" hidden></button>
    <button type="button" id="retry" hidden></button>
  </div>
  <div class="status" id="status" role="status" aria-live="polite"></div>
</div>`;

export class DiaryRecorder extends HTMLElement {
  #session;
  #waveform;
  #audioContext = null;
  #timer = 0;
  #wakeLock = null;
  #labels = DEFAULT_LABELS;
  #recovery = null;
  #lastError = null;
  #waiting = false;
  #pending = 0;
  #focusLost = false;
  #onOnline = () => this.#session?.networkRestored();
  #onBeforeUnload = (e) => {
    if (this.#session && [State.RECORDING, State.PAUSED].includes(this.#session.state)) {
      e.preventDefault();
    }
  };

  connectedCallback() {
    if (this.shadowRoot) {
      return;
    }
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = TEMPLATE;
    this.#labels = this.#parseLabels();
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    this.classList.toggle('reduced', Boolean(reduced?.matches));
    reduced?.addEventListener?.('change', (e) => this.classList.toggle('reduced', e.matches));

    const $ = (sel) => root.querySelector(sel);
    this.els = {
      time: $('#time'), record: $('#record'), pause: $('#pause'), stop: $('#stop'), retry: $('#retry'),
      status: $('#status'), recover: $('.recover'), rtitle: $('#rt'), rtext: $('#rx'),
      rfinish: $('#rfinish'),
    };
    this.#waveform = new WaveformView({
      canvas: $('canvas'),
      levelFill: $('.level-fill'),
      get reducedMotion() { return this.host.classList.contains('reduced'); },
      host: this,
      raf: (cb) => requestAnimationFrame(cb),
      caf: (id) => cancelAnimationFrame(id),
      color: () => getComputedStyle(this).getPropertyValue('--lumo-primary-color').trim() || '#1a5fb4',
    });
    this.#session = new RecorderSession({
      mediaDevices: navigator.mediaDevices,
      MediaRecorder: globalThis.MediaRecorder,
      storage: createDefaultStorage(),
      api: createUploadApi({ fetch: (...a) => fetch(...a), baseUrl: this.getAttribute('upload-url') ?? '/upload/recordings' }),
      now: () => Date.now(),
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    });
    this.#wire();
    window.addEventListener('online', this.#onOnline);
    window.addEventListener('beforeunload', this.#onBeforeUnload);
    this.#render();
    this.#session.recoverPending().catch(() => {});
  }

  disconnectedCallback() {
    window.removeEventListener('online', this.#onOnline);
    window.removeEventListener('beforeunload', this.#onBeforeUnload);
    clearInterval(this.#timer);
    this.#waveform?.detach();
    this.#audioContext?.close?.().catch(() => {});
  }

  #parseLabels() {
    try {
      return { ...DEFAULT_LABELS, ...JSON.parse(this.getAttribute('labels') ?? '{}') };
    } catch {
      return DEFAULT_LABELS;
    }
  }

  #emit(name, detail) {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  #wire() {
    const s = this.#session;
    const { record, pause, stop, retry, rfinish } = this.els;
    record.addEventListener('click', () => {
      this.#lastError = null;
      const entryAttr = this.getAttribute('entry-id');
      s.start({ entryId: entryAttr ? Number(entryAttr) : null, language: this.getAttribute('language') ?? 'auto' });
    });
    pause.addEventListener('click', () => (s.state === State.PAUSED ? s.resume() : s.pause()));
    stop.addEventListener('click', () => s.stop());
    retry.addEventListener('click', () => {
      this.#lastError = null;
      if (s.state === State.ERROR && s.recordingId) {
        s.retryUpload();
      } else if (s.state === State.ERROR) {
        s.reset();
      }
    });
    rfinish.addEventListener('click', () => {
      const info = this.#recovery;
      this.#recovery = null;
      s.finishRecovered(info);
    });

    s.on('state', ({ state }) => {
      this.dataset.state = state;
      this.setAttribute('data-state', state);
      if (state === State.RECORDING || state === State.PAUSED) {
        this.#startTimer();
      }
      if (state === State.RECORDING) {
        this.#acquireWakeLock();
      }
      if (![State.RECORDING, State.PAUSED].includes(state)) {
        this.#stopMeter();
      }
      if (state === State.UPLOADING || state === State.DONE || state === State.IDLE) {
        this.#waiting = false;
      }
      this.#render();
    });
    s.on('stream', ({ stream }) => this.#startMeter(stream));
    s.on('started', (d) => this.#emit('recording-started', d));
    s.on('completed', (d) => {
      this.#emit('recording-completed', d);
      this.#pending = 0;
      this.els.time.textContent = formatElapsed(d.durationMs);
      this.#render();
      this.#session.reset();
    });
    s.on('progress', ({ pendingChunks }) => {
      this.#pending = pendingChunks;
      this.#waiting = false;
      this.#render();
    });
    s.on('retrying', () => {
      this.#waiting = true;
      this.#render();
    });
    s.on('error', (e) => {
      this.#lastError = e;
      this.#emit('upload-error', e);
      this.#render();
    });
    s.on('recovered', ({ info }) => {
      this.#recovery = info;
      this.els.time.textContent = formatElapsed(info.elapsedMs);
      this.#render();
    });
  }

  async #startMeter(stream) {
    try {
      const Ctx = window.AudioContext ?? window.webkitAudioContext;
      this.#audioContext = new Ctx();
      const analyser = this.#audioContext.createAnalyser();
      analyser.fftSize = 1024;
      this.#audioContext.createMediaStreamSource(stream).connect(analyser);
      this.#waveform.attach(analyser);
    } catch {
      // Waveform is cosmetic; recording works without it.
    }
  }

  #stopMeter() {
    this.#waveform?.detach();
    this.#audioContext?.close?.().catch(() => {});
    this.#audioContext = null;
    clearInterval(this.#timer);
    this.#timer = 0;
    this.#releaseWakeLock();
  }

  #startTimer() {
    if (!this.#timer) {
      this.#timer = setInterval(() => this.#renderTime(), 250);
    }
  }

  #renderTime() {
    this.els.time.textContent = formatElapsed(this.#session.elapsedMs());
  }

  async #acquireWakeLock() {
    try {
      this.#wakeLock = await navigator.wakeLock?.request('screen');
    } catch {
      // optional, e.g. denied or unsupported
    }
  }

  #releaseWakeLock() {
    this.#wakeLock?.release?.().catch(() => {});
    this.#wakeLock = null;
  }

  #render() {
    const L = this.#labels;
    const state = this.#session.state;
    const { record, pause, stop, retry, status, time, recover, rtitle, rtext, rfinish } = this.els;
    const live = state === State.RECORDING || state === State.PAUSED;
    const busy = state === State.REQUESTING || state === State.STOPPING || state === State.UPLOADING;
    // A button that had keyboard focus may be hidden below (record -> pause/stop and back); remember to move focus on.
    const focused = this.shadowRoot.activeElement;

    record.querySelector('.t').textContent = L.record;
    record.setAttribute('aria-label', L.record);
    record.hidden = live || busy;
    record.disabled = Boolean(this.#recovery);
    pause.hidden = !live;
    pause.textContent = state === State.PAUSED ? L.resume : L.pause;
    stop.hidden = !live;
    stop.textContent = L.stop;
    retry.hidden = state !== State.ERROR;
    retry.textContent = L.retry;
    time.setAttribute('aria-label', L.elapsed);

    recover.hidden = !this.#recovery;
    rtitle.textContent = L.recoverTitle;
    rtext.textContent = L.recoverText;
    rfinish.textContent = L.finish;

    let text = L[{ [State.IDLE]: 'idle', [State.REQUESTING]: 'requesting', [State.RECORDING]: 'recording',
      [State.PAUSED]: 'paused', [State.STOPPING]: 'stopping', [State.UPLOADING]: 'uploading', [State.DONE]: 'done',
      [State.ERROR]: 'idle' }[state]];
    if (this.#waiting) {
      text = L.waiting;
    } else if (this.#pending > 0 && (live || state === State.UPLOADING)) {
      text = `${text} ${L.pending.replace('{0}', String(this.#pending))}`;
    }
    if (this.#lastError && state === State.ERROR) {
      text = this.#lastError.message;
    }
    if (this.#recovery) {
      text = '';
    }
    status.textContent = text;
    this.#rescueFocus(focused);
  }

  /** Keeps keyboard focus inside the recorder when the focused button was hidden or disabled by this render. */
  #rescueFocus(previous) {
    const usable = (b) => b && !b.hidden && !b.disabled;
    if (previous && !usable(previous)) {
      this.#focusLost = true;
    }
    if (!this.#focusLost) {
      return;
    }
    const elsewhere = document.activeElement;
    if (elsewhere && elsewhere !== document.body && elsewhere !== this) {
      this.#focusLost = false; // the user moved on; do not pull focus back
      return;
    }
    const { record, pause, stop, retry, rfinish } = this.els;
    const next = [pause, retry, rfinish, record].find((b) => usable(b) && b.getClientRects().length > 0)
      ?? (usable(stop) ? stop : null);
    if (next) {
      this.#focusLost = false;
      next.focus();
    }
  }
}

if (!customElements.get('diary-recorder')) {
  customElements.define('diary-recorder', DiaryRecorder);
}
