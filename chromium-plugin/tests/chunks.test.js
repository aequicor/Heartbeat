// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ChunkAssembler } from '../src/shared/chunks.js';

/**
 * Splits a frame the way the app does (`FrameChunks.kt`): raw UTF-8 slices, base64 each.
 * @param {string} id
 * @param {object} frame
 * @param {number} slice
 */
function chunk(id, frame, slice) {
  const bytes = Buffer.from(JSON.stringify(frame), 'utf8');
  const count = Math.max(1, Math.ceil(bytes.length / slice));
  return Array.from({ length: count }, (_, seq) => ({
    kind: 'chunk',
    of: id,
    seq,
    count,
    data: bytes.subarray(seq * slice, (seq + 1) * slice).toString('base64'),
  }));
}

test('chunks reassemble a frame with multi-byte characters split between slices', () => {
  const frame = { kind: 'evt', event: 'chat.changed', params: { text: 'Привет 🙂'.repeat(50) } };
  const assembler = new ChunkAssembler();
  const parts = chunk('c:1', frame, 37).reverse();

  const results = parts.map((part) => assembler.accept(part));

  assert.deepEqual(results.filter(Boolean), [frame]);
  assert.equal(assembler.pending, 0);
});

test('inconsistent chunks drop their set', () => {
  const assembler = new ChunkAssembler();
  assembler.accept({ of: 'a', seq: 0, count: 2, data: 'YQ==' });

  assert.throws(() => assembler.accept({ of: 'a', seq: 0, count: 2, data: 'YQ==' }));
  assert.equal(assembler.pending, 0);
  assert.throws(() => assembler.accept({ of: 'b', seq: 2, count: 2, data: 'YQ==' }));
  assert.throws(() => assembler.accept({ of: 'c', seq: 0, count: 0, data: '' }));
});

test('incomplete sets expire', () => {
  let now = 0;
  const assembler = new ChunkAssembler({ now: () => now, ttlMs: 1_000 });
  assembler.accept({ of: 'a', seq: 0, count: 2, data: 'YQ==' });

  now = 1_001;
  assembler.expire();

  assert.equal(assembler.pending, 0);
});

test('assembled size is capped', () => {
  const assembler = new ChunkAssembler({ maxBytes: 2 });
  const parts = chunk('c:2', { a: 'bbbb' }, 1);

  assert.throws(() => parts.forEach((part) => assembler.accept(part)), /too large/);
});
