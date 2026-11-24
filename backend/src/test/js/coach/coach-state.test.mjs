import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAnswerDraft } from '../../../main/resources/META-INF/resources/js/coach/answer-draft.js';
import {
  actionsFor, currentQuestion, errorFor, newQuestions, speechLanguage, voiceFor,
} from '../../../main/resources/META-INF/resources/js/coach/coach-state.js';

const msg = (id, role, kind) => ({ id, role, kind, content: `text ${id}` });
const conversation = (status, ...messages) => ({ id: 1, status, language: 'DE', messages });

// ---- actions (port of CoachPresenterTest)

test('active offers answer, pause and end', () => {
  const a = actionsFor('ACTIVE');
  assert.deepEqual([a.answer, a.pause, a.end, a.resume, a.startNew], [true, true, true, false, false]);
});

test('paused offers resume and end but no answer', () => {
  const a = actionsFor('PAUSED');
  assert.deepEqual([a.resume, a.end, a.answer, a.pause, a.startNew], [true, true, false, false, false]);
});

test('ended is read-only with start new', () => {
  const a = actionsFor('ENDED');
  assert.equal(a.startNew, true);
  assert.equal(a.answer || a.pause || a.resume || a.end, false);
});

// ---- errors

test('invalid llm output and provider errors are calm and retryable', () => {
  const invalid = errorFor({ code: 'LLM_OUTPUT_INVALID', status: 502 });
  assert.equal(invalid.messageKey, 'coach.error.invalid');
  assert.equal(invalid.retryable, true);
  for (const code of ['PROVIDER_UNAVAILABLE', 'PROVIDER_TIMEOUT', 'PROVIDER_ERROR']) {
    const info = errorFor({ code, status: 502 });
    assert.equal(info.messageKey, 'coach.error.provider');
    assert.equal(info.retryable, true);
  }
});

test('conflict means reload, not retry', () => {
  const info = errorFor({ code: 'CONVERSATION_ENDED', status: 409 });
  assert.deepEqual(info, { messageKey: 'coach.error.state', retryable: false, reload: true });
});

test('not implemented, not found and unreachable', () => {
  assert.equal(errorFor({ code: 'HTTP_501', status: 501 }).messageKey, 'coach.error.unavailable');
  assert.equal(errorFor({ code: 'NOT_FOUND', status: 404 }).messageKey, 'coach.error.notFound');
  const other = errorFor({ code: 'INTERNAL', status: 500 });
  assert.equal(other.messageKey, 'coach.error.unreachable');
  assert.equal(other.retryable, true);
  assert.equal(errorFor({ code: 'NETWORK', status: 0 }).messageKey, 'coach.error.unreachable');
  assert.equal(errorFor(new Error('connection refused')).messageKey, 'coach.error.unreachable');
});

test('error messages never contain backend text', () => {
  const info = errorFor({ code: 'LLM_OUTPUT_INVALID', status: 502, message: 'my secret diary text' });
  assert.ok(!JSON.stringify(info).includes('secret'));
});

// ---- conversation helpers

test('current question is the trailing coach question of an active conversation', () => {
  assert.ok(currentQuestion(conversation('ACTIVE', msg(1, 'COACH', 'OPENING_QUESTION'))));
  assert.equal(currentQuestion(conversation('ACTIVE', msg(1, 'COACH', 'OPENING_QUESTION'), msg(2, 'USER', 'USER_ANSWER'))), null);
  assert.equal(currentQuestion(conversation('ACTIVE', msg(3, 'COACH', 'EXPLANATION'))), null);
  assert.equal(currentQuestion(conversation('PAUSED', msg(1, 'COACH', 'FOLLOW_UP_QUESTION'))), null);
  assert.equal(currentQuestion(conversation('ACTIVE')), null);
});

test('only new coach questions are spoken', () => {
  const c = conversation('ACTIVE', msg(1, 'COACH', 'OPENING_QUESTION'), msg(2, 'USER', 'USER_ANSWER'),
    msg(3, 'COACH', 'EXPLANATION'), msg(4, 'COACH', 'FOLLOW_UP_QUESTION'));
  assert.deepEqual(newQuestions(new Set(), c).map((m) => m.id), [1, 4]);
  assert.deepEqual(newQuestions(new Set([1, 2, 3]), c).map((m) => m.id), [4]);
  assert.deepEqual(newQuestions(new Set([1, 4]), c), []);
});

test('speech language and voice selection', () => {
  assert.equal(speechLanguage('DE', 'en'), 'DE');
  assert.equal(speechLanguage('auto', 'de-DE'), 'DE');
  assert.equal(speechLanguage(null, 'fr'), 'EN');
  assert.equal(voiceFor('DE', 'de_a', 'en_b'), 'de_a');
  assert.equal(voiceFor('EN', 'de_a', ' '), null);
});

// ---- answer draft (port of CoachPresenterTest)

test('typed answer is not voice', () => {
  const d = createAnswerDraft();
  d.edited('Ich war müde.');
  assert.deepEqual(d.request(), { text: 'Ich war müde.', voice: false });
});

test('transcribed answer is voice even after editing', () => {
  const d = createAnswerDraft();
  assert.equal(d.transcribed('  ich war müde '), 'ich war müde');
  d.edited('Ich war sehr müde.');
  assert.deepEqual(d.request(), { text: 'Ich war sehr müde.', voice: true });
});

test('a transcription is added to existing text', () => {
  const d = createAnswerDraft();
  d.edited('Erstens das.');
  assert.equal(d.transcribed('Zweitens das.'), 'Erstens das. Zweitens das.');
  assert.equal(d.voice(), true);
});

test('emptying the field resets the voice flag', () => {
  const d = createAnswerDraft();
  d.transcribed('hallo');
  d.edited('');
  d.edited('neu getippt');
  assert.equal(d.request().voice, false);
});

test('a blank transcript changes nothing', () => {
  const d = createAnswerDraft();
  assert.equal(d.transcribed('   '), '');
  assert.equal(d.voice(), false);
  assert.equal(d.request(), null);
});

test('a failed send keeps text and voice flag, a successful send clears them', () => {
  const d = createAnswerDraft();
  d.transcribed('das ist wichtig');
  assert.ok(d.request());
  // a failed send does nothing to the draft
  assert.equal(d.text(), 'das ist wichtig');
  assert.equal(d.voice(), true);
  d.sent();
  assert.equal(d.text(), '');
  assert.equal(d.voice(), false);
  assert.equal(d.request(), null);
});

test('a blank answer is never sent', () => {
  const d = createAnswerDraft();
  d.edited('   \n ');
  assert.equal(d.request(), null);
});
