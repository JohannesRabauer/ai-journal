import assert from 'node:assert/strict';
import { test } from 'node:test';
import { IllegalTransitionError, RecorderStateMachine, State } from '../../../main/frontend/recorder/state-machine.js';

test('happy path: idle -> recording -> paused -> recording -> stopping -> uploading -> done', () => {
  const changes = [];
  const sm = new RecorderStateMachine((c) => changes.push(c.to));
  for (const event of ['request', 'granted', 'pause', 'resume', 'stop', 'stopped', 'uploaded']) {
    sm.send(event);
  }
  assert.equal(sm.state, State.DONE);
  assert.deepEqual(changes, [
    'requesting-permission', 'recording', 'paused', 'recording', 'stopping', 'uploading', 'done',
  ]);
  sm.send('reset');
  assert.equal(sm.state, State.IDLE);
});

test('illegal transitions throw and leave the state untouched', () => {
  const sm = new RecorderStateMachine();
  assert.throws(() => sm.send('stop'), IllegalTransitionError);
  assert.throws(() => sm.send('pause'), IllegalTransitionError);
  assert.equal(sm.state, State.IDLE);
  sm.send('request');
  sm.send('granted');
  assert.throws(() => sm.send('resume'), IllegalTransitionError);
  assert.throws(() => sm.send('request'), IllegalTransitionError);
  assert.equal(sm.state, State.RECORDING);
});

test('error is reachable from every active state and can be retried or reset', () => {
  for (const path of [['request'], ['request', 'granted'], ['request', 'granted', 'pause'],
    ['request', 'granted', 'stop'], ['request', 'granted', 'stop', 'stopped']]) {
    const sm = new RecorderStateMachine();
    path.forEach((e) => sm.send(e));
    sm.send('fail');
    assert.equal(sm.state, State.ERROR);
  }
  const sm = new RecorderStateMachine();
  ['request', 'granted', 'stop', 'stopped', 'fail', 'retry'].forEach((e) => sm.send(e));
  assert.equal(sm.state, State.UPLOADING);
  sm.send('fail');
  sm.send('reset');
  assert.equal(sm.state, State.IDLE);
});

test('recovered recordings can be finished straight from idle', () => {
  const sm = new RecorderStateMachine();
  sm.send('finish');
  assert.equal(sm.state, State.UPLOADING);
  assert.equal(sm.can('uploaded'), true);
  assert.equal(sm.can('pause'), false);
});

test('done cannot restart without reset', () => {
  const sm = new RecorderStateMachine();
  ['finish', 'uploaded'].forEach((e) => sm.send(e));
  assert.throws(() => sm.send('request'), IllegalTransitionError);
});
