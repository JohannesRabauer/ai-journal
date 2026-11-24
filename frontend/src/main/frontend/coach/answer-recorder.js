// Answer recorder: one short MediaRecorder clip (single blob, max ~2 minutes) that is transcribed into text for the
// user to review. Nothing is stored in the browser or sent anywhere but the transcribe proxy. No AI output is
// produced here. States: idle -> requesting -> recording -> transcribing -> idle. Errors return to idle with an
// error kind so typing stays available.
//
// Dependencies are injected for node --test: mediaDevices, MediaRecorder, guard, transcribe, now, timers.
//   transcribe(blob, mimeType) -> Promise<{text, detectedLanguage}>; rejects with an Error that carries `status`.

export const AnswerState = Object.freeze({
  IDLE: 'idle',
  REQUESTING: 'requesting',
  RECORDING: 'recording',
  TRANSCRIBING: 'transcribing',
});

export const MAX_ANSWER_MS = 120_000;
const MIME_PREFERENCE = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus', 'audio/ogg'];

/** Maps a failed transcribe call to a stable error kind the UI turns into a calm message. */
export function classifyTranscribeError(error) {
  const status = error?.status;
  if (status === 413) {
    return 'too-large';
  }
  if (status === 501 || status === 503) {
    return 'unavailable';
  }
  if (status === undefined || status === 0 || status >= 500) {
    return 'unavailable';
  }
  return 'failed';
}

export function pickMimeType(MediaRecorderImpl) {
  if (!MediaRecorderImpl?.isTypeSupported) {
    return '';
  }
  return MIME_PREFERENCE.find((type) => MediaRecorderImpl.isTypeSupported(type)) ?? '';
}

export function createAnswerRecorder({
  mediaDevices, MediaRecorder, guard, transcribe, now, setTimer, clearTimer, maxMs = MAX_ANSWER_MS, onEvent = () => {},
}) {
  const GUARD_KEY = 'answer-recorder';
  let state = AnswerState.IDLE;
  let recorder = null;
  let stream = null;
  let chunks = [];
  let startedAt = 0;
  let limitTimer = null;
  let token = 0; // bumped by cancel() so late results are ignored

  function setState(next) {
    state = next;
    onEvent({ type: 'state', state });
  }

  function release() {
    clearTimer(limitTimer);
    limitTimer = null;
    stream?.getTracks?.().forEach((track) => track.stop());
    stream = null;
    recorder = null;
    guard.setActive(GUARD_KEY, false);
  }

  function fail(kind) {
    release();
    setState(AnswerState.IDLE);
    onEvent({ type: 'error', kind });
  }

  async function start() {
    if (state !== AnswerState.IDLE) {
      return;
    }
    if (!mediaDevices?.getUserMedia || !MediaRecorder) {
      onEvent({ type: 'error', kind: 'unsupported' });
      return;
    }
    const myToken = ++token;
    // Claim the microphone for the guard right away: speech stops the moment the user presses record.
    guard.setActive(GUARD_KEY, true);
    setState(AnswerState.REQUESTING);
    try {
      stream = await mediaDevices.getUserMedia({ audio: true });
    } catch {
      if (myToken === token) {
        fail('mic-denied');
      }
      return;
    }
    if (myToken !== token) {
      stream.getTracks?.().forEach((track) => track.stop());
      return;
    }
    const mimeType = pickMimeType(MediaRecorder);
    chunks = [];
    try {
      recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    } catch {
      fail('unsupported');
      return;
    }
    const mine = recorder;
    mine.addEventListener('dataavailable', (event) => {
      if (event.data && event.data.size > 0) {
        chunks.push(event.data);
      }
    });
    mine.addEventListener('stop', () => {
      if (myToken !== token) {
        return;
      }
      const type = mine.mimeType || mimeType || 'audio/webm';
      const blob = new Blob(chunks, { type });
      release();
      finish(blob, type, myToken);
    });
    mine.addEventListener('error', () => {
      if (myToken === token) {
        fail('failed');
      }
    });
    startedAt = now();
    mine.start();
    limitTimer = setTimer(() => stop(), maxMs);
    setState(AnswerState.RECORDING);
  }

  async function finish(blob, type, myToken) {
    if (blob.size === 0) {
      setState(AnswerState.IDLE);
      onEvent({ type: 'error', kind: 'empty' });
      return;
    }
    setState(AnswerState.TRANSCRIBING);
    let result;
    try {
      result = await transcribe(blob, type);
    } catch (error) {
      if (myToken === token) {
        fail(classifyTranscribeError(error));
      }
      return;
    }
    if (myToken !== token) {
      return;
    }
    setState(AnswerState.IDLE);
    const text = (result?.text ?? '').trim();
    if (!text) {
      onEvent({ type: 'error', kind: 'empty' });
      return;
    }
    onEvent({ type: 'transcript', text, detectedLanguage: result.detectedLanguage ?? null });
  }

  /** Ends the recording and starts transcription. The guard is released as soon as recording has ended. */
  function stop() {
    if (state !== AnswerState.RECORDING || !recorder) {
      return;
    }
    clearTimer(limitTimer);
    limitTimer = null;
    guard.setActive(GUARD_KEY, false);
    recorder.stop();
  }

  /** Throws the clip away; a transcription in flight is ignored when it returns. */
  function cancel() {
    if (state === AnswerState.IDLE) {
      return;
    }
    token += 1;
    const active = recorder;
    release();
    if (active && active.state !== 'inactive') {
      try {
        active.stop();
      } catch {
        // already inactive
      }
    }
    setState(AnswerState.IDLE);
  }

  return {
    start,
    stop,
    cancel,
    get state() {
      return state;
    },
    elapsedMs() {
      return state === AnswerState.RECORDING ? Math.min(now() - startedAt, maxMs) : 0;
    },
  };
}
