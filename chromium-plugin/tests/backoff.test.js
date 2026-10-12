// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { backoffDelay } from '../src/background/backoff.js';
import { browserInfo } from '../src/background/browser-info.js';

test('reconnect delay doubles up to a minute', () => {
  assert.deepEqual([0, 1, 2, 5, 6, 30].map(backoffDelay), [1_000, 2_000, 4_000, 32_000, 60_000, 60_000]);
  assert.equal(backoffDelay(-3), 1_000);
});

test('browser brand prefers the vendor over Chromium and placeholder brands', () => {
  const edge = {
    userAgentData: {
      brands: [
        { brand: 'Not)A;Brand', version: '99' },
        { brand: 'Chromium', version: '140' },
        { brand: 'Microsoft Edge', version: '140' },
      ],
    },
  };
  assert.deepEqual(browserInfo(edge), { brand: 'Microsoft Edge', version: '140' });
  assert.deepEqual(browserInfo({ userAgentData: { brands: [{ brand: 'Chromium', version: '139' }] } }), {
    brand: 'Chromium',
    version: '139',
  });
  assert.deepEqual(browserInfo(undefined), { brand: 'Chromium', version: '' });
});
