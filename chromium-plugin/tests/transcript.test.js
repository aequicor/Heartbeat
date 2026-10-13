// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyTranscript, isStreaming } from '../src/sidepanel/transcript.js';

const prompt = { id: 'm1', type: 'prompt', createdAt: 1, text: 'Hi' };
const reply = { id: 'm2', type: 'reply', createdAt: 2, parts: [], streaming: true };

test('a reset replaces the transcript', () => {
  assert.deepEqual(applyTranscript([reply], { reset: true, upserts: [prompt] }), [prompt]);
});

test('updates stay in place, new entries are appended and removed ones dropped', () => {
  const done = { ...reply, streaming: false };
  const notice = { id: 'm3', type: 'notice', createdAt: 3, notice: 'stopped' };

  const next = applyTranscript([prompt, reply], { reset: false, upserts: [done, notice], removed: ['m1'] });

  assert.deepEqual(next, [done, notice]);
  assert.equal(isStreaming(next), false);
  assert.equal(isStreaming([prompt, reply]), true);
});
