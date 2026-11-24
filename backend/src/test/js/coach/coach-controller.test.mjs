import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRecordingGuard } from '../../../main/resources/META-INF/resources/js/coach/recording-guard.js';
import { createCoachController } from '../../../main/resources/META-INF/resources/js/coach/coach-controller.js';

const TEXTS = {
  'coach.thinking': 'thinking', 'coach.waitingForRecording': 'waiting', 'coach.error.provider': 'provider',
  'coach.error.state': 'state', 'coach.error.unreachable': 'unreachable', 'coach.announce.question': 'Question: {0}',
  'coach.end.title': 'End?', 'coach.end.text': 'text', 'coach.end.confirm': 'End', 'coach.cancel': 'Cancel',
};

const q = (id, content = `q${id}`) => ({ id, role: 'COACH', kind: id === 1 ? 'OPENING_QUESTION' : 'FOLLOW_UP_QUESTION', content });
const a = (id) => ({ id, role: 'USER', kind: 'USER_ANSWER', content: `a${id}` });
const conv = (status, ...messages) => ({ id: 7, entryId: 3, status, language: 'EN', messages });

function setup({ settings = {}, conversationId = null, shownIds = [], confirmAnswer = true } = {}) {
  const log = [];
  const calls = [];
  let answerText = '';
  let recording = false;
  const listeners = new Set();
  const guard = {
    isRecording: () => recording,
    onChange: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
  };
  const env = { responses: [], swapFails: false };
  const api = {
    post: async (path, body) => {
      calls.push(['post', path, body]);
      const next = env.responses.shift();
      if (next instanceof Error || (next && next.code)) {
        throw next;
      }
      return next ?? null;
    },
    get: async (path) => { calls.push(['get', path]); return env.fresh ?? conv('ACTIVE', q(1)); },
  };
  const view = {
    setBusy: (t) => log.push(['busy', t]),
    setVoiceBusy: (v) => log.push(['voiceBusy', v]),
    showError: (text, retry) => log.push(['error', text, Boolean(retry)]) && (env.retry = retry),
    clearError: () => log.push(['clearError']),
    readAnswer: () => answerText,
    setAnswer: (t) => { answerText = t; log.push(['setAnswer', t]); },
    answerField: () => 'FIELD',
    swap: async (o) => { log.push(['swap', o.fresh]); if (env.swapFails) { throw new Error('x'); } },
    focusAnswer: () => log.push(['focusAnswer']),
    focusControls: () => log.push(['focusControls']),
    focusStart: () => log.push(['focusStart']),
    reload: () => log.push(['reload']),
  };
  const speech = {
    enqueue: (...args) => log.push(['enqueue', ...args]),
    play: (...args) => log.push(['play', ...args]),
    announce: (text) => log.push(['announce', text]),
    focusWhenIdle: (target) => log.push(['focusWhenIdle', target]),
  };
  const controller = createCoachController({
    entryId: 3, texts: TEXTS, api, view, speech, guard,
    confirm: async () => confirmAnswer,
    settings: { autoPlay: true, voiceDe: 'de_a', voiceEn: 'en_b', uiLanguage: 'en', ...settings },
    conversationId, language: 'EN', shownIds,
  });
  return {
    controller, log, calls, env, setAnswer: (t) => { answerText = t; },
    setRecording(value) { recording = value; listeners.forEach((fn) => fn(value)); },
    names: () => log.map((e) => e[0]),
  };
}

test('starting posts the entry, swaps the panel, speaks and announces the opening question, then focuses the answer', async () => {
  const s = setup();
  s.env.responses.push(conv('ACTIVE', q(1, 'What happened?')));
  await s.controller.start();
  assert.deepEqual(s.calls[0], ['post', '/api/conversations', { entryId: 3 }]);
  assert.deepEqual(s.log.slice(0, 3), [['busy', 'thinking'], ['clearError'], ['swap', false]]);
  assert.deepEqual(s.log.find((e) => e[0] === 'enqueue'), ['enqueue', 1, 'What happened?', 'EN', 'en_b']);
  assert.deepEqual(s.log.find((e) => e[0] === 'announce'), ['announce', 'Question: What happened?']);
  assert.deepEqual(s.log.find((e) => e[0] === 'focusWhenIdle'), ['focusWhenIdle', 'FIELD']);
  assert.equal(s.controller.conversationId, 7);
  assert.equal(s.controller.busy, false);
});

test('nothing is read aloud when auto play is off, but the question is still announced', async () => {
  const s = setup({ settings: { autoPlay: false } });
  s.env.responses.push(conv('ACTIVE', q(1)));
  await s.controller.start();
  assert.ok(!s.names().includes('enqueue'));
  assert.ok(s.names().includes('announce'));
});

test('an answer is posted with the voice flag and only the new question is presented', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.controller.transcribed('spoken words');
  s.setAnswer('spoken words, edited');
  s.controller.draftEdited('spoken words, edited');
  s.env.responses.push(conv('ACTIVE', q(1), a(2), q(3, 'And then?')));
  await s.controller.answer();
  assert.deepEqual(s.calls[0], ['post', '/api/conversations/7/answers', { text: 'spoken words, edited', voice: true }]);
  assert.deepEqual(s.log.filter((e) => e[0] === 'enqueue').map((e) => e[1]), [3]);
  assert.deepEqual(s.log.find((e) => e[0] === 'announce'), ['announce', 'Question: And then?']);
});

test('a blank answer is not sent', async () => {
  const s = setup({ conversationId: 7 });
  s.setAnswer('   ');
  await s.controller.answer();
  assert.equal(s.calls.length, 0);
});

test('a failed answer keeps the text, shows a calm retry, and retrying sends the same text', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.setAnswer('my answer');
  s.env.responses.push(Object.assign(new Error('x'), { code: 'PROVIDER_UNAVAILABLE', status: 502 }));
  await s.controller.answer();
  assert.deepEqual(s.log.find((e) => e[0] === 'error'), ['error', 'provider', true]);
  assert.ok(!s.names().includes('swap'));
  assert.equal(s.controller.busy, false);
  s.env.responses.push(conv('ACTIVE', q(1), a(2), q(3)));
  await s.env.retry();
  assert.equal(s.calls[1][2].text, 'my answer');
  assert.ok(s.names().includes('swap'));
});

test('a reply that arrives while recording is held back until the recording ends', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.setAnswer('hello');
  s.env.responses.push(conv('ACTIVE', q(1), a(2), q(3, 'Held?')));
  s.setRecording(true);
  await s.controller.answer();
  assert.deepEqual(s.log.filter((e) => e[0] === 'busy').at(-1), ['busy', 'waiting']);
  for (const name of ['swap', 'enqueue', 'announce', 'focusWhenIdle']) {
    assert.ok(!s.names().includes(name), `${name} must wait`);
  }
  assert.equal(s.controller.busy, true);
  s.setRecording(false);
  await new Promise((resolve) => setImmediate(resolve));
  assert.ok(s.names().includes('swap'));
  assert.deepEqual(s.log.find((e) => e[0] === 'announce'), ['announce', 'Question: Held?']);
  assert.equal(s.controller.busy, false);
});

test('a conflict reloads the current conversation and explains, without a retry', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.setAnswer('late');
  s.env.responses.push(Object.assign(new Error('x'), { code: 'CONVERSATION_ENDED', status: 409 }));
  await s.controller.answer();
  assert.deepEqual(s.calls[1], ['get', '/api/conversations/7']);
  assert.ok(s.names().includes('swap'));
  assert.deepEqual(s.log.findLast((e) => e[0] === 'error'), ['error', 'state', false]);
  assert.ok(!s.names().includes('enqueue'));
});

test('pause and end are quiet and hand focus to the first control', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.env.responses.push(conv('PAUSED', q(1)));
  await s.controller.pause();
  assert.equal(s.calls[0][1], '/api/conversations/7/pause');
  assert.ok(s.names().includes('focusControls'));
  assert.ok(!s.names().includes('enqueue') && !s.names().includes('announce'));
});

test('resume presents the open question again', async () => {
  const s = setup({ conversationId: 7, shownIds: [1] });
  s.env.responses.push(conv('ACTIVE', q(1, 'Still open?')));
  await s.controller.resume();
  assert.deepEqual(s.log.find((e) => e[0] === 'enqueue'), ['enqueue', 1, 'Still open?', 'EN', 'en_b']);
});

test('ending asks first; declining does nothing', async () => {
  const no = setup({ conversationId: 7, confirmAnswer: false });
  await no.controller.end();
  assert.equal(no.calls.length, 0);
  const yes = setup({ conversationId: 7 });
  yes.env.responses.push(conv('ENDED', q(1)));
  await yes.controller.end();
  assert.equal(yes.calls[0][1], '/api/conversations/7/end');
});

test('start new shows the calm start state and runs nothing', async () => {
  const s = setup({ conversationId: 7, shownIds: [1, 2] });
  await s.controller.startNew();
  assert.deepEqual(s.log, [['swap', true], ['clearError'], ['focusStart']]);
  assert.equal(s.calls.length, 0);
  assert.equal(s.controller.conversationId, null);
});

test('when the fresh panel cannot be loaded after a successful action the page reloads', async () => {
  const s = setup();
  s.env.swapFails = true;
  s.env.responses.push(conv('ACTIVE', q(1)));
  await s.controller.start();
  assert.ok(s.names().includes('reload'));
  assert.ok(!s.names().includes('announce'));
});

test('a second action while busy is ignored', async () => {
  const s = setup();
  s.env.responses.push(conv('ACTIVE', q(1)));
  const first = s.controller.start();
  const second = s.controller.start();
  await Promise.all([first, second]);
  assert.equal(s.calls.length, 1);
});

test('the read-aloud button plays in the conversation language with the matching voice', () => {
  const s = setup({ conversationId: 7 });
  s.controller.play(5, 'Hello?');
  assert.deepEqual(s.log[0], ['play', 5, 'Hello?', 'EN', 'en_b']);
});

test('a voice answer is added to the field for review and focuses it', () => {
  const s = setup({ conversationId: 7 });
  s.controller.transcribed('spoken');
  assert.deepEqual(s.log, [['setAnswer', 'spoken'], ['focusAnswer']]);
});

test('the recording guard of the page is a real guard', () => {
  const g = createRecordingGuard();
  assert.equal(typeof g.onChange, 'function');
});
