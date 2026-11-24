import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  errorInfo as weeklyError, errorAttribute, pageHref, removeLabel, renumber,
} from '../../main/resources/META-INF/resources/js/weekly-logic.js';
import {
  durationSeconds, attemptRequest, clock, fill, errorInfo as gymError, hasFeedback, MAX_DURATION_SECONDS,
} from '../../main/resources/META-INF/resources/js/gym-logic.js';

// ---- week
test('week errors are calm codes like WeeklyPresenter.errorFor', () => {
  assert.deepEqual(weeklyError({ code: 'LLM_OUTPUT_INVALID', status: 502 }), { code: 'invalid', retryable: true, reload: false });
  assert.equal(weeklyError({ code: 'PROVIDER_UNAVAILABLE', status: 503 }).code, 'provider');
  assert.equal(weeklyError({ code: 'NO_ENTRIES_IN_WEEK', status: 409 }).retryable, false);
  assert.equal(weeklyError({ code: 'INVALID_REPORT', status: 400 }).code, 'invalidEdit');
  assert.equal(weeklyError({ code: 'INVALID_WEEK', status: 400 }).code, 'badWeek');
  assert.deepEqual(weeklyError({ code: 'NOT_FOUND', status: 404 }), { code: 'notFound', retryable: false, reload: true });
  assert.equal(weeklyError({ code: 'X', status: 501 }).code, 'unavailable');
  assert.deepEqual(weeklyError({ code: 'NETWORK', status: 0 }), { code: 'unreachable', retryable: true, reload: false });
  assert.equal(weeklyError(null).code, 'unreachable');
});

test('error codes select lower case data attributes', () => {
  assert.equal(errorAttribute('noEntries'), 'data-err-noentries');
  assert.equal(errorAttribute('invalidEdit'), 'data-err-invalidedit');
});

test('page addresses keep the week and add message codes', () => {
  assert.equal(pageHref('/weekly?week=2026-W12', { msg: 'created' }), '/weekly?week=2026-W12&msg=created');
  assert.equal(pageHref('/weekly', { error: 'notFound' }), '/weekly?error=notFound');
  assert.equal(pageHref('/weekly?week=2026-W12'), '/weekly?week=2026-W12');
});

test('remove labels are shortened and cloned ids renumbered', () => {
  assert.equal(removeLabel('Remove {0}', '  Walks  '), 'Remove Walks');
  assert.equal(removeLabel('Remove {0}', 'x'.repeat(45)), `Remove ${'x'.repeat(40)}…`);
  assert.equal(removeLabel('Remove {0}', undefined), 'Remove ');
  assert.equal(renumber('weekly-thread-3', 1001), 'weekly-thread-1001');
});

// ---- gym
test('practice time is never negative and capped', () => {
  assert.equal(durationSeconds(1000, 61_000), 60);
  assert.equal(durationSeconds(5000, 1000), 0);
  assert.equal(durationSeconds(0, 10 * 60 * 60 * 1000), MAX_DURATION_SECONDS);
  assert.equal(durationSeconds(undefined, 5), 0);
});

test('only a written answer can be saved', () => {
  assert.equal(attemptRequest('   ', 10), null);
  assert.equal(attemptRequest(null, 10), null);
  assert.deepEqual(attemptRequest('  my thoughts  ', 90), { answer: 'my thoughts', durationSeconds: 90 });
  assert.equal(attemptRequest('x', -4).durationSeconds, 0);
});

test('timer clock and filled texts', () => {
  assert.equal(clock(0), '0:00');
  assert.equal(clock(65), '1:05');
  assert.equal(clock(-3), '0:00');
  assert.equal(fill('About {0} minutes', 7), 'About 7 minutes');
});

test('gym errors map to calm codes', () => {
  assert.deepEqual(gymError({ code: 'LLM_OUTPUT_INVALID', status: 502 }), { code: 'aiInvalid', retryable: true });
  assert.equal(gymError({ code: 'PROVIDER_ERROR', status: 503 }).code, 'aiUnavailable');
  assert.equal(gymError({ code: 'ANSWER_EMPTY', status: 400 }).retryable, false);
  assert.equal(gymError({ code: 'ANSWER_TOO_LONG', status: 400 }).code, 'answerTooLong');
  assert.equal(gymError({ code: 'NOT_FOUND', status: 404 }).code, 'notFound');
  assert.equal(gymError({ code: 'X', status: 501 }).code, 'unavailable');
  assert.deepEqual(gymError({ code: 'NETWORK', status: 0 }), { code: 'unreachable', retryable: true });
});

test('feedback is shown only when there are questions', () => {
  assert.equal(hasFeedback({ feedbackQuestions: ['a?'] }), true);
  assert.equal(hasFeedback({ feedbackQuestions: [] }), false);
  assert.equal(hasFeedback({}), false);
  assert.equal(hasFeedback(null), false);
});
