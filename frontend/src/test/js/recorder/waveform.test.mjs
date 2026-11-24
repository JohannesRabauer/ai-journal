import assert from 'node:assert/strict';
import { test } from 'node:test';
import { WaveformView, computeLevel } from '../../../main/frontend/recorder/waveform.js';

function setup(reducedMotion) {
  let draws = 0;
  const ctx = { clearRect() {}, beginPath() {}, moveTo() { draws++; }, lineTo() {}, stroke() {} };
  const canvas = { width: 600, height: 64, getContext: () => ctx };
  const levelFill = { style: { width: '' } };
  const frames = [];
  const view = new WaveformView({
    canvas, levelFill, reducedMotion, color: () => '#000',
    raf: (cb) => { frames.push(cb); return frames.length; },
    caf: () => {},
  });
  const analyser = { fftSize: 8, getByteTimeDomainData: (b) => b.fill(255) };
  return { view, analyser, frames, levelFill, draws: () => draws };
}

test('reduced motion: a level bar is updated and nothing is drawn (SPEC 12)', () => {
  const t = setup(true);
  t.view.attach(t.analyser);
  t.frames.shift()(1000);
  assert.equal(t.draws(), 0);
  assert.equal(t.levelFill.style.width, `${Math.round(computeLevel(new Uint8Array(8).fill(255)) * 100)}%`);
});

test('reduced motion: the level bar is throttled to a few updates per second', () => {
  const t = setup(true);
  t.view.attach(t.analyser);
  t.frames.shift()(1000);
  t.levelFill.style.width = 'x';
  t.frames.shift()(1100);
  assert.equal(t.levelFill.style.width, 'x');
  t.frames.shift()(1300);
  assert.notEqual(t.levelFill.style.width, 'x');
});

test('without reduced motion the waveform is drawn', () => {
  const t = setup(false);
  t.view.attach(t.analyser);
  t.frames.shift()(1000);
  assert.equal(t.draws(), 1);
});
