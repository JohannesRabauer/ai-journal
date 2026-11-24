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

const MIC_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><g fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M19 10v1a7 7 0 0 1-14 0v-1M12 18v4"/></g></svg>';
const STOP_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/></svg>';

const TEMPLATE = `
<style>
  :host { display: block; color: var(--diary-text); font-family: inherit; }
  .root { display: flex; flex-direction: column; gap: 1rem; align-items: center; text-align: center; padding: 1.5rem 0 .5rem; }
  .stage { display: flex; flex-direction: column; align-items: center; gap: .6rem; }
  .big { width: 6.5rem; height: 6.5rem; min-width: 6.5rem; min-height: 6.5rem; padding: 0; border-radius: 50%; border: 0; cursor: pointer;
         display: grid; place-items: center; background: var(--diary-accent); color: var(--diary-on-accent);
         box-shadow: 0 0 0 10px var(--diary-accent-soft), var(--diary-shadow); }
  .big svg { width: 2.4rem; height: 2.4rem; }
  .big.stop { background: var(--diary-error); color: var(--diary-on-accent);
              box-shadow: 0 0 0 10px color-mix(in srgb, var(--diary-error) 18%, transparent), var(--diary-shadow); }
  .big:disabled { opacity: .5; cursor: not-allowed; }
  .big:focus-visible, button:focus-visible { outline: 3px solid var(--diary-text); outline-offset: 4px; }
  .cap { font-size: .9rem; font-weight: 600; color: var(--diary-muted); min-height: 1.3em; }
  .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .wave { position: relative; height: 2.5rem; width: min(100%, 22rem); overflow: hidden; }
  :host(:not([data-state="recording"]):not([data-state="paused"])) .wave { display: none; }
  canvas { width: 100%; height: 100%; display: block; }
  .level { display: none; height: 100%; align-items: center; padding: 0 .75rem; }
  .level-track { width: 100%; height: 10px; border-radius: 5px; background: var(--diary-rule); overflow: hidden; }
  .level-fill { height: 100%; width: 0%; background: var(--diary-accent); }
  :host(.reduced) canvas { display: none; }
  :host(.reduced) .level { display: flex; }
  .row { display: flex; align-items: center; justify-content: center; gap: .75rem; flex-wrap: wrap; }
  .time { font-variant-numeric: tabular-nums; font-size: 1.5rem; min-width: 5ch; }
  button.pill { font: inherit; font-weight: 550; min-height: 44px; min-width: 44px; padding: 0 1.1rem; border-radius: 999px; cursor: pointer;
           border: 1px solid var(--diary-border); background: var(--diary-surface); color: var(--diary-text); }
  button.primary { background: var(--diary-accent); color: var(--diary-on-accent); border-color: transparent; }
  button[hidden] { display: none; }
  .status { min-height: 1.4em; color: var(--diary-muted); max-width: 32rem; }
  .recover { padding: .75rem 1rem; border-radius: var(--diary-radius-md); border: 1px solid var(--diary-border);
             background: var(--diary-surface-2); text-align: start; align-self: stretch; }
  .recover[hidden] { display: none; }
  .recover p { margin: 0 0 .5rem; }
</style>
<div class="root">
  <div class="recover" part="recover" hidden role="group" aria-labelledby="rt">
    <p><strong id="rt"></strong></p>
    <p id="rx"></p>
    <div class="row">
      <button type="button" class="pill primary" id="rfinish"></button>
    </div>
  </div>
  <div class="stage">
    <button type="button" class="big" id="record">${MIC_ICON}<span class="sr t"></span></button>
    <button type="button" class="big stop" id="stop" hidden>${STOP_ICON}<span class="sr t"></span></button>
    <div class="cap" id="cap" aria-hidden="true"></div>
  </div>
  <div class="wave" aria-hidden="true">
    <canvas width="600" height="64"></canvas>
    <div class="level"><div class="level-track"><div class="level-fill"></div></div></div>
  </div>
  <div class="row">
    <output class="time" id="time" role="timer" aria-label="" aria-live="off">00:00</output>
    <button type="button" class="pill" id="pause" hidden></button>
    <button type="button" class="pill" id="retry" hidden></button>
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
      color: () => getComputedStyle(this).getPropertyValue('--diary-accent').trim() || getComputedStyle(this).color,
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
    stop.querySelector('.t').textContent = L.stop;
    stop.setAttribute('aria-label', L.stop);
    // Visible caption under the big button (the button itself carries the accessible name).
    this.shadowRoot.querySelector('#cap').textContent = live ? L.stop : (busy ? '' : L.record);
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
