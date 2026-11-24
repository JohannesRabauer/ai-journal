// Recorder state machine (pure). Illegal transitions throw.
// idle -> requesting-permission -> recording <-> paused -> stopping -> uploading -> done | error

export const State = Object.freeze({
  IDLE: 'idle',
  REQUESTING: 'requesting-permission',
  RECORDING: 'recording',
  PAUSED: 'paused',
  STOPPING: 'stopping',
  UPLOADING: 'uploading',
  DONE: 'done',
  ERROR: 'error',
});

const TRANSITIONS = {
  [State.IDLE]: { request: State.REQUESTING, finish: State.UPLOADING },
  [State.REQUESTING]: { granted: State.RECORDING, fail: State.ERROR },
  [State.RECORDING]: { pause: State.PAUSED, stop: State.STOPPING, fail: State.ERROR },
  [State.PAUSED]: { resume: State.RECORDING, stop: State.STOPPING, fail: State.ERROR },
  [State.STOPPING]: { stopped: State.UPLOADING, fail: State.ERROR },
  [State.UPLOADING]: { uploaded: State.DONE, fail: State.ERROR },
  [State.DONE]: { reset: State.IDLE },
  [State.ERROR]: { reset: State.IDLE, retry: State.UPLOADING },
};

export class IllegalTransitionError extends Error {
  constructor(from, event) {
    super(`Illegal transition: "${event}" in state "${from}"`);
    this.name = 'IllegalTransitionError';
    this.from = from;
    this.event = event;
  }
}

export class RecorderStateMachine {
  #state = State.IDLE;
  #listener;

  /** @param {(change: {from: string, to: string, event: string}) => void} [listener] */
  constructor(listener = () => {}) {
    this.#listener = listener;
  }

  get state() {
    return this.#state;
  }

  can(event) {
    return Object.hasOwn(TRANSITIONS[this.#state], event);
  }

  send(event) {
    const next = TRANSITIONS[this.#state][event];
    if (next === undefined) {
      throw new IllegalTransitionError(this.#state, event);
    }
    const from = this.#state;
    this.#state = next;
    this.#listener({ from, to: next, event });
    return next;
  }
}
