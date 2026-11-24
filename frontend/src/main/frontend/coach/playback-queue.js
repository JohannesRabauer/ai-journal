// Speech playback queue for coach questions: one clip at a time, never while a recording is active (SPEC 5.8, 9).
// Everything environment-specific is injected (audio factory, fetch, object URLs) so this runs under node --test.
//
//   fetchAudio(item) -> Promise<Blob|null>   null means "no audio" (HTTP 204, error): the item is skipped silently
//   createAudio(url) -> element with play() / pause() / addEventListener('ended'|'error')
//   objectUrl: { create(blob) -> url, revoke(url) }

export function createPlaybackQueue({ guard, fetchAudio, createAudio, objectUrl, onChange = () => {} }) {
  let queue = [];
  let current = null;
  let loading = null;
  let generation = 0;

  const notify = () => onChange({
    playingId: current?.item.id ?? null,
    loadingId: loading?.id ?? null,
    queued: queue.length,
  });

  function releaseCurrent() {
    if (!current) {
      return;
    }
    const finished = current;
    current = null;
    try {
      finished.audio.pause();
    } catch {
      // already stopped
    }
    objectUrl.revoke(finished.url);
  }

  async function pump() {
    if (current || loading || guard.isRecording()) {
      return;
    }
    const item = queue.shift();
    if (!item) {
      notify();
      return;
    }
    const myGeneration = generation;
    loading = item;
    notify();
    let data = item.data ?? null;
    if (!data) {
      try {
        data = await fetchAudio(item);
      } catch {
        data = null;
      }
    }
    if (myGeneration !== generation) {
      return; // stopped or interrupted while fetching; whoever bumped the generation re-queued what mattered
    }
    loading = null;
    if (!data) {
      notify();
      await pump();
      return;
    }
    if (guard.isRecording()) {
      queue.unshift({ ...item, data });
      notify();
      return;
    }
    const url = objectUrl.create(data);
    const audio = createAudio(url);
    const clip = { item, audio, url };
    current = clip;
    const done = () => {
      if (current !== clip) {
        return;
      }
      releaseCurrent();
      notify();
      pump();
    };
    audio.addEventListener('ended', done);
    audio.addEventListener('error', done);
    notify();
    try {
      await audio.play();
    } catch {
      done();
    }
  }

  guard.onChange((recording) => {
    if (recording) {
      // Starting a recording stops speech immediately. A clip that was still loading waits at the front.
      if (loading) {
        queue.unshift(loading);
        loading = null;
      }
      generation += 1;
      releaseCurrent();
      notify();
    } else {
      pump();
    }
  });

  return {
    /** Adds a coach question. Duplicates (same id already queued, loading or playing) are ignored. */
    enqueue(item) {
      const known = [...queue, loading, current?.item].some((other) => other && other.id === item.id);
      if (known) {
        return false;
      }
      queue.push(item);
      notify();
      pump();
      return true;
    },

    /** Explicit "play" button: replaces everything else. Does nothing while recording. */
    playNow(item) {
      if (guard.isRecording()) {
        return false;
      }
      this.stop();
      queue.push(item);
      pump();
      return true;
    },

    /** Stops playback and drops everything queued (navigation, "stop audio"). */
    stop() {
      generation += 1;
      queue = [];
      loading = null;
      releaseCurrent();
      notify();
    },

    state() {
      return { playingId: current?.item.id ?? null, loadingId: loading?.id ?? null, queued: queue.length };
    },
  };
}
