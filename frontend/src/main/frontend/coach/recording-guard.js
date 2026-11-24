// Recording guard (SPEC 5.8, "the coach never interrupts"): the single place that knows whether ANY recording is
// active in the page. Speech playback, announcements and focus moves ask it before they start; starting a recording
// makes listeners stop what they are doing. Pure and DOM-free so it can be tested with node --test; the DOM
// adapter below connects it to <diary-recorder> elements.

/** diary-recorder `data-state` values during which the microphone is (about to be) in use. */
export const ACTIVE_RECORDER_STATES = Object.freeze(['requesting-permission', 'recording', 'paused']);

export function createRecordingGuard() {
  const sources = new Set();
  const listeners = new Set();
  const deferred = new Map();

  function flushDeferred() {
    const jobs = [...deferred.values()];
    deferred.clear();
    for (const job of jobs) {
      job();
    }
  }

  return {
    /** Marks a recording source (any string/object key) as recording or not. */
    setActive(source, active) {
      const before = sources.size > 0;
      if (active) {
        sources.add(source);
      } else {
        sources.delete(source);
      }
      const after = sources.size > 0;
      if (before === after) {
        return;
      }
      for (const listener of [...listeners]) {
        listener(after);
      }
      if (!after) {
        flushDeferred();
      }
    },

    isRecording() {
      return sources.size > 0;
    },

    /** Called with true when the first recording starts and with false when the last one stops. */
    onChange(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    /**
     * Runs `job` now when nothing is recording, otherwise once recording has stopped. A later call with the same
     * key replaces the earlier pending job (only the latest announcement / focus request matters).
     * @returns {boolean} true when the job ran immediately
     */
    whenIdle(key, job) {
      if (sources.size === 0) {
        deferred.delete(key);
        job();
        return true;
      }
      deferred.set(key, job);
      return false;
    },

    cancelDeferred(key) {
      deferred.delete(key);
    },
  };
}

/**
 * Feeds the guard from every <diary-recorder> element under `root`: its `data-state` attribute (observed), plus the
 * bubbling recording-started / recording-completed / upload-error events. Removing an element clears its source.
 * @returns {() => void} stop watching
 */
export function watchDiaryRecorders({ guard, root, MutationObserverImpl = globalThis.MutationObserver }) {
  const apply = (element) => {
    const state = element.getAttribute?.('data-state');
    guard.setActive(element, ACTIVE_RECORDER_STATES.includes(state));
  };
  const isRecorder = (node) => node?.localName === 'diary-recorder';
  const recordersIn = (node) => {
    if (isRecorder(node)) {
      return [node];
    }
    return node?.querySelectorAll ? [...node.querySelectorAll('diary-recorder')] : [];
  };

  const onStarted = (event) => {
    if (isRecorder(event.target)) {
      guard.setActive(event.target, true);
    }
  };
  const onEnded = (event) => {
    if (isRecorder(event.target)) {
      apply(event.target);
    }
  };
  root.addEventListener('recording-started', onStarted);
  root.addEventListener('recording-completed', onEnded);
  root.addEventListener('upload-error', onEnded);

  recordersIn(root).forEach(apply);

  let observer = null;
  if (MutationObserverImpl) {
    observer = new MutationObserverImpl((mutations) => {
      for (const mutation of mutations) {
        if (mutation.type === 'attributes' && isRecorder(mutation.target)) {
          apply(mutation.target);
        }
        for (const removed of mutation.removedNodes ?? []) {
          recordersIn(removed).forEach((el) => guard.setActive(el, false));
        }
        for (const added of mutation.addedNodes ?? []) {
          recordersIn(added).forEach(apply);
        }
      }
    });
    observer.observe(root.documentElement ?? root, {
      subtree: true, childList: true, attributes: true, attributeFilter: ['data-state'],
    });
  }

  return () => {
    root.removeEventListener('recording-started', onStarted);
    root.removeEventListener('recording-completed', onEnded);
    root.removeEventListener('upload-error', onEnded);
    observer?.disconnect();
  };
}
