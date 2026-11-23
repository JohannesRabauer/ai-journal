// Live waveform from an AnalyserNode. Pure helpers are unit-tested; WaveformView wires them to the DOM.

/** RMS level 0..1 of unsigned 8-bit time-domain samples (128 = silence). */
export function computeLevel(samples) {
  if (!samples || samples.length === 0) {
    return 0;
  }
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    const v = (samples[i] - 128) / 128;
    sum += v * v;
  }
  return Math.min(1, Math.sqrt(sum / samples.length) * 1.8);
}

/** Draws the oscilloscope line. ctx is a CanvasRenderingContext2D (or a fake). */
export function drawWaveform(ctx, width, height, samples, color) {
  ctx.clearRect(0, 0, width, height);
  ctx.lineWidth = 2;
  ctx.strokeStyle = color;
  ctx.beginPath();
  const step = width / Math.max(1, samples.length - 1);
  for (let i = 0; i < samples.length; i++) {
    const y = (samples[i] / 255) * height;
    if (i === 0) {
      ctx.moveTo(0, y);
    } else {
      ctx.lineTo(i * step, y);
    }
  }
  ctx.stroke();
}

/**
 * @param {object} o
 * @param {HTMLCanvasElement} o.canvas
 * @param {HTMLElement} o.levelFill element whose width shows the level (used with reduced motion)
 * @param {boolean} o.reducedMotion render a simple level bar instead of the animated waveform
 * @param {(cb: FrameRequestCallback) => number} o.raf
 * @param {(id: number) => void} o.caf
 * @param {() => string} o.color
 */
export class WaveformView {
  #o;
  #analyser = null;
  #frame = 0;
  #buffer = null;
  #lastLevelUpdate = 0;

  constructor(o) {
    this.#o = o;
  }

  attach(analyser) {
    this.#analyser = analyser;
    this.#buffer = new Uint8Array(analyser.fftSize);
    this.#schedule();
  }

  detach() {
    this.#o.caf(this.#frame);
    this.#analyser = null;
    this.clear();
  }

  clear() {
    const { canvas, levelFill } = this.#o;
    const ctx = canvas.getContext('2d');
    ctx?.clearRect(0, 0, canvas.width, canvas.height);
    levelFill.style.width = '0%';
  }

  #schedule() {
    this.#frame = this.#o.raf((t) => this.#tick(t));
  }

  #tick(timestamp) {
    if (!this.#analyser) {
      return;
    }
    this.#analyser.getByteTimeDomainData(this.#buffer);
    const { canvas, levelFill, reducedMotion, color } = this.#o;
    if (reducedMotion) {
      // Static level bar, updated a few times per second; no animated drawing, no transitions.
      if (timestamp - this.#lastLevelUpdate >= 250) {
        this.#lastLevelUpdate = timestamp;
        levelFill.style.width = `${Math.round(computeLevel(this.#buffer) * 100)}%`;
      }
    } else {
      const ctx = canvas.getContext('2d');
      if (ctx) {
        drawWaveform(ctx, canvas.width, canvas.height, this.#buffer, color());
      }
    }
    this.#schedule();
  }
}
