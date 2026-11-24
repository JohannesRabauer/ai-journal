// <diary-coach-speech>: invisible helper of the coach screen. Plays coach questions through the same-origin TTS
// endpoint (one at a time, never while a recording is active), owns the polite live region that announces new
// questions, and moves focus to the answer field once nothing is recording. It never creates content itself:
// Java calls enqueue/play/announce only as a reaction to the user's own action (SPEC 5.8).
// Attributes: labels (JSON: stop, playing), tts-url (default /api/tts).
// Events (bubbling, composed): speech-state {playingId, loadingId, queued}; recording-changed {recording}.

import { createPlaybackQueue } from './playback-queue.js';
import { pageGuard } from './page-guard.js';

const TEMPLATE = `
<link rel="stylesheet" href="/css/coach-speech.css">
<div class="sr" role="status" aria-live="polite" aria-atomic="true" id="live"></div>
<button type="button" id="stop" hidden></button>`;

export class DiaryCoachSpeech extends HTMLElement {
  #queue;
  #guard;
  #labels = { stop: 'Stop audio' };
  #unsubscribe = null;

  connectedCallback() {
    if (this.shadowRoot) {
      return;
    }
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = TEMPLATE;
    try {
      this.#labels = { ...this.#labels, ...JSON.parse(this.getAttribute('labels') ?? '{}') };
    } catch {
      // keep defaults
    }
    this.live = root.querySelector('#live');
    this.stopButton = root.querySelector('#stop');
    this.stopButton.textContent = this.#labels.stop;
    this.stopButton.addEventListener('click', () => {
      this.stop();
      // The stop button hides itself once nothing plays; hand keyboard focus to the answer field, not <body>.
      document.getElementById('coach-answer')?.focus?.();
    });

    this.#guard = pageGuard();
    this.#queue = createPlaybackQueue({
      guard: this.#guard,
      fetchAudio: (item) => this.#fetchAudio(item),
      createAudio: (url) => new Audio(url),
      objectUrl: { create: (blob) => URL.createObjectURL(blob), revoke: (url) => URL.revokeObjectURL(url) },
      onChange: (state) => {
        this.stopButton.hidden = !(state.playingId || state.loadingId || state.queued);
        this.dispatchEvent(new CustomEvent('speech-state', { detail: state, bubbles: true, composed: true }));
      },
    });
    window.addEventListener('pagehide', this.#onPageHide);
    this.#unsubscribe = this.#guard.onChange((recording) => {
      this.dispatchEvent(new CustomEvent('recording-changed', { detail: { recording }, bubbles: true, composed: true }));
    });
  }

  disconnectedCallback() {
    window.removeEventListener('pagehide', this.#onPageHide);
    this.#queue?.stop();
    this.#unsubscribe?.();
    this.#guard?.cancelDeferred(`${this.localName}-announce`);
    this.#guard?.cancelDeferred(`${this.localName}-focus`);
  }

  #onPageHide = () => this.#queue?.stop();

  async #fetchAudio(item) {
    const response = await fetch(this.getAttribute('tts-url') ?? '/api/tts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-AI-Diary': '1' },
      body: JSON.stringify({ text: item.text, language: item.language, voice: item.voice ?? null }),
    });
    if (response.status !== 200) {
      return null; // 204 (TTS off or failing) and any error: text only, silently
    }
    const blob = await response.blob();
    return blob.size > 0 ? blob : null;
  }

  /** Queues a coach question for automatic playback (waits while a recording is active). */
  enqueue(id, text, language, voice) {
    return this.#queue?.enqueue({ id, text, language, voice }) ?? false;
  }

  /** The per-message play button. */
  play(id, text, language, voice) {
    return this.#queue?.playNow({ id, text, language, voice }) ?? false;
  }

  stop() {
    this.#queue?.stop();
  }

  /** True while any recording (answer recorder or <diary-recorder>) is active. */
  isRecording() {
    return Boolean(this.#guard?.isRecording());
  }

  /** Polite announcement for screen readers; held back while a recording is active. */
  announce(text) {
    this.#guard?.whenIdle(`${this.localName}-announce`, () => {
      this.live.textContent = '';
      // Re-set on the next tick so the same text is announced again.
      setTimeout(() => { this.live.textContent = text; }, 50);
    });
  }

  /** Moves focus to `target` (the answer field) once nothing is recording. */
  focusWhenIdle(target) {
    this.#guard?.whenIdle(`${this.localName}-focus`, () => target?.focus?.());
  }
}

if (!customElements.get('diary-coach-speech')) {
  customElements.define('diary-coach-speech', DiaryCoachSpeech);
}
