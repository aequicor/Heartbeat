// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BridgeError, ErrorCode, createIds, isObject, toWireError } from '../src/shared/protocol.js';

test('ids carry the sender prefix and grow', () => {
  const ids = createIds('e');
  assert.equal(ids.next(), 'e:1');
  assert.equal(ids.next(), 'e:2');
});

test('only bridge errors reach the app with their message', () => {
  assert.deepEqual(toWireError(new BridgeError(ErrorCode.FORBIDDEN, 'Not allowed')), {
    code: 'forbidden',
    message: 'Not allowed',
  });
  assert.deepEqual(toWireError(new Error('https://private.example/path')), {
    code: 'internal',
    message: 'Extension failed',
  });
});

test('objects are told apart from arrays and null', () => {
  assert.equal(isObject({}), true);
  assert.equal(isObject([]), false);
  assert.equal(isObject(null), false);
  assert.equal(isObject('x'), false);
});
