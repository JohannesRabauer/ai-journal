import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ReviewSession, PHASE, GRADES, gradeForDigit, formatInterval, attemptText, editProblem, errorInfo, extractionTarget,
  MAX_PROMPT, MAX_ANSWER,
} from '../../main/resources/META-INF/resources/js/memory-logic.js';

const card = (id) => ({ id, type: 'FACT', typeLabel: 'Fact', prompt: `Q${id}` });
const reveal = (id) => ({ itemId: id, answer: `A${id}`, sourceQuote: 'q', sourceType: 'ENTRY', sourceId: 3, attemptText: null });

test('grade buttons map to SM-2 qualities and the keys 1 to 4', () => {
  assert.deepEqual(GRADES.map((g) => [g.key, g.quality, g.digit]), [['forgot', 1, '1'], ['hard', 3, '2'], ['good', 4, '3'], ['easy', 5, '4']]);
  assert.equal(gradeForDigit('3').key, 'good');
  assert.equal(gradeForDigit('5'), null);
  assert.equal(gradeForDigit('a'), null);
});

test('the next-interval hint says days, singular for one', () => {
  assert.equal(formatInterval(1, 'in 1 day', 'in {0} days'), 'in 1 day');
  assert.equal(formatInterval(6, 'in 1 day', 'in {0} days'), 'in 6 days');
  assert.equal(formatInterval(30, 'in 1 Tag', 'in {0} Tagen'), 'in 30 Tagen');
});

test('a session shows the prompt first, then the answer after an attempt, then moves on', () => {
  const s = new ReviewSession([card(1), card(2)]);
  assert.equal(s.total, 2);
  assert.equal(s.remaining(), 2);
  assert.equal(s.position(), 1);
  assert.equal(s.phase(), PHASE.PROMPT);
  assert.equal(s.current().id, 1);

  s.revealed(reveal(2)); // not the current card: ignored
  assert.equal(s.phase(), PHASE.PROMPT);
  s.revealed(reveal(1));
  assert.equal(s.phase(), PHASE.REVEALED);
  assert.equal(s.reveal.answer, 'A1');

  s.graded();
  assert.equal(s.phase(), PHASE.PROMPT);
  assert.equal(s.position(), 2);
  assert.equal(s.reveal, null);
  s.graded();
  assert.equal(s.phase(), PHASE.DONE);
  assert.equal(s.remaining(), 0);
  assert.equal(s.current(), null);
  s.graded(); // nothing left: harmless
  assert.equal(s.phase(), PHASE.DONE);
});

test('an empty session is done at once', () => {
  assert.equal(new ReviewSession([]).phase(), PHASE.DONE);
  assert.equal(new ReviewSession(null).phase(), PHASE.DONE);
});

test('deleting a card of the session drops it and its reveal', () => {
  const s = new ReviewSession([card(1), card(2), card(3)]);
  s.revealed(reveal(1));
  s.removeItem(1);
  assert.equal(s.phase(), PHASE.PROMPT);
  assert.equal(s.current().id, 2);
  assert.equal(s.total, 2);
  s.removeItem(3);
  assert.equal(s.remaining(), 1);
  s.removeItem(99);
  assert.equal(s.remaining(), 1);
});

test('a blank attempt means "answered in my head"', () => {
  assert.equal(attemptText('  '), null);
  assert.equal(attemptText(null), null);
  assert.equal(attemptText('  hello '), 'hello');
});

test('edited suggestions are checked before sending', () => {
  assert.equal(editProblem('', 'a'), 'promptEmpty');
  assert.equal(editProblem('q', ' '), 'answerEmpty');
  assert.equal(editProblem('x'.repeat(MAX_PROMPT + 1), 'a'), 'promptTooLong');
  assert.equal(editProblem('q', 'x'.repeat(MAX_ANSWER + 1)), 'answerTooLong');
  assert.equal(editProblem('q', 'a'), null);
  assert.equal(editProblem('x'.repeat(MAX_PROMPT), 'x'.repeat(MAX_ANSWER)), null);
});

test('errors map to calm codes and say whether to retry or reload the lists', () => {
  assert.deepEqual(errorInfo({ code: 'LLM_OUTPUT_INVALID', status: 502 }), { code: 'invalid', retryable: true, refresh: false });
  assert.equal(errorInfo({ code: 'PROVIDER_ERROR', status: 503 }).code, 'provider');
  assert.equal(errorInfo({ code: 'ENTRY_NOT_FINISHED', status: 409 }).code, 'notFinished');
  assert.equal(errorInfo({ code: 'CONVERSATION_EMPTY', status: 409 }).code, 'empty');
  assert.deepEqual(errorInfo({ code: 'CANDIDATE_DECIDED', status: 409 }), { code: 'state', retryable: false, refresh: true });
  assert.deepEqual(errorInfo({ code: 'NOT_FOUND', status: 404 }), { code: 'notFound', retryable: false, refresh: true });
  assert.equal(errorInfo({ code: 'INVALID_REQUEST', status: 400 }).code, 'badInput');
  assert.equal(errorInfo({ code: 'NOT_IMPLEMENTED', status: 501 }).code, 'unavailable');
  assert.deepEqual(errorInfo({ code: 'NETWORK', status: 0 }), { code: 'unreachable', retryable: true, refresh: false });
  assert.equal(errorInfo(undefined).code, 'unreachable');
});

test('the extraction target comes from source and id of the address', () => {
  assert.deepEqual(extractionTarget('entry', '12'), { sourceType: 'ENTRY', sourceId: 12 });
  assert.deepEqual(extractionTarget('Conversation', ' 7 '), { sourceType: 'CONVERSATION', sourceId: 7 });
  assert.equal(extractionTarget('', '12'), null);
  assert.equal(extractionTarget('entry', ''), null);
  assert.equal(extractionTarget('entry', '0'), null);
  assert.equal(extractionTarget('entry', '-3'), null);
  assert.equal(extractionTarget('journal', '3'), null);
});
