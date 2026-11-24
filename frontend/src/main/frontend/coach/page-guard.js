// The one recording guard of the page, shared by every coach element and fed by every <diary-recorder>.
import { createRecordingGuard, watchDiaryRecorders } from './recording-guard.js';

const KEY = Symbol.for('ai-diary.recording-guard');

export function pageGuard() {
  if (!globalThis[KEY]) {
    const guard = createRecordingGuard();
    watchDiaryRecorders({ guard, root: document });
    globalThis[KEY] = guard;
  }
  return globalThis[KEY];
}
