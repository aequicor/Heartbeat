// @ts-check
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { canSend, groupedChats, initialState, reduce, selectedPermissions } from '../src/sidepanel/store.js';

const ready = reduce(initialState, { type: 'status', status: 'ready', features: { chat: true } });
const listed = reduce(ready, {
  type: 'chats',
  projects: [{ id: 'p1', name: 'Heartbeat' }],
  chats: [
    { id: 'a', title: 'Release notes', projectId: null, updatedAt: 2, running: false, pinned: false, unread: false, continuable: true },
    { id: 'b', title: 'Fix login', projectId: 'p1', updatedAt: 1, running: true, pinned: false, unread: false, continuable: true },
  ],
  permissions: [{ chatId: 'b', requestId: 'r1', title: 'Run?', options: [] }],
});

test('chats are grouped by project and filtered by title', () => {
  assert.deepEqual(groupedChats(listed).map((group) => [group.project?.name ?? null, group.chats.map((it) => it.id)]), [
    [null, ['a']],
    ['Heartbeat', ['b']],
  ]);
  assert.deepEqual(groupedChats(listed, 'LOGIN').map((group) => group.chats.map((it) => it.id)), [['b']]);
});

test('transcript changes apply only to the open chat', () => {
  const opened = reduce(listed, { type: 'open', chatId: 'a' });
  const change = { reset: true, upserts: [{ id: 'm1', type: 'prompt', createdAt: 1 }], olderOmitted: 3 };

  const applied = reduce(opened, { type: 'transcript', chatId: 'a', change });
  const ignored = reduce(opened, { type: 'transcript', chatId: 'b', change });

  assert.equal(applied.transcript.length, 1);
  assert.equal(applied.olderOmitted, 3);
  assert.equal(ignored, opened);
});

test('sending is possible only into an idle, continuable chat or a new one', () => {
  assert.equal(canSend(reduce(listed, { type: 'open', chatId: 'a' }), 'Hi'), true);
  assert.equal(canSend(reduce(listed, { type: 'open', chatId: 'b' }), 'Hi'), false);
  assert.equal(canSend(reduce(listed, { type: 'compose', projectId: 'p1' }), 'Hi'), true);
  assert.equal(canSend(reduce(listed, { type: 'compose' }), '  '), false);
  const noChats = reduce(listed, { type: 'status', status: 'ready', features: { chat: false } });
  assert.equal(canSend(reduce(noChats, { type: 'compose' }), 'Hi'), false);
});

test('a sent prompt opens its chat and clears attachments', () => {
  const composing = reduce(reduce(listed, { type: 'compose' }), {
    type: 'attach',
    attachment: { kind: 'page', url: 'https://a', title: 'A', text: 'x' },
  });

  const sent = reduce(reduce(composing, { type: 'sending' }), { type: 'sent', chatId: 'c9' });

  assert.equal(sent.selected, 'c9');
  assert.equal(sent.isComposingNew, false);
  assert.deepEqual(sent.attachments, []);
  assert.deepEqual(selectedPermissions(reduce(listed, { type: 'open', chatId: 'b' })).map((it) => it.requestId), ['r1']);
});

test('the app\'s chat events reduce into the panel state', () => {
  /** @param {string} name */
  const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/protocol/${name}.json`, import.meta.url), 'utf8'));
  const list = fixture('chats-changed').params;
  const change = fixture('chat-changed').params;

  const listedFromApp = reduce(ready, { type: 'chats', ...list });
  const opened = reduce(listedFromApp, { type: 'open', chatId: 'c1' });
  const withEntry = reduce(opened, { type: 'transcript', chatId: change.chatId, change });

  assert.equal(listedFromApp.chats[0].running, true);
  assert.equal(selectedPermissions(opened)[0].input?.kind, 'text');
  assert.equal(withEntry.transcript[0].parts[1].truncated, true);
  assert.equal(withEntry.olderOmitted, 12);
});
