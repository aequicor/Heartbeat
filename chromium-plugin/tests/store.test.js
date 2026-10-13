// @ts-check
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { canSend, composerView, initialState, reduce, selectedPermissions, sidebarSections } from '../src/sidepanel/store.js';

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

const withModels = reduce(ready, {
  type: 'chats',
  projects: [{ id: 'p1', name: 'Heartbeat' }],
  chats: [
    { ...listed.chats[0] },
    { ...listed.chats[1], running: false, modelId: 'gpt', settings: { modelId: 'gpt', effort: 'low', approval: 'ask' } },
  ],
  permissions: [],
  composer: {
    models: [
      { id: 'gpt', name: 'GPT', shortName: 'GPT', efforts: ['low', 'high'], newChatEffort: 'high', trust: true, projects: true, connection: 'codex/a' },
      { id: 'codex', name: 'Codex', shortName: 'Codex', projects: true, connection: 'codex/a' },
      { id: 'local', name: 'Local', shortName: 'Local', projects: false },
      { id: 'other', name: 'Other', shortName: 'Other', projects: true, connection: 'pi/b' },
    ],
    newChat: { modelId: 'gpt', approval: 'ask' },
  },
});

test('the list is arranged like the studio sidebar: pinned, expanded projects, then recent', () => {
  const state = reduce(ready, {
    type: 'chats',
    projects: [{ id: 'p1', name: 'Heartbeat' }, { id: 'p2', name: 'Docs' }],
    chats: [
      { id: 'pin', title: 'Pinned', projectId: 'p1', updatedAt: 9, pinned: true },
      { id: 'old', title: 'Old', projectId: null, updatedAt: 1 },
      { id: 'new', title: 'New', projectId: null, updatedAt: 5 },
      { id: 'p1a', title: 'Login', projectId: 'p1', updatedAt: 4 },
      { id: 'p2a', title: 'Guide', projectId: 'p2', updatedAt: 3 },
      { id: 'gone', title: 'Archived login', projectId: null, updatedAt: 8, archived: true },
    ].map((chat) => ({ running: false, pinned: false, unread: false, continuable: true, ...chat })),
    permissions: [],
  });
  const shape = (/** @type {import('../src/sidepanel/store.js').PanelState} */ it) => sidebarSections(it).map((section) => [
    section.key,
    section.collapsed,
    (section.projects ?? []).map((group) => [group.project.id, group.collapsed, group.rows.map((chat) => chat.id)]),
    section.rows.map((chat) => chat.id),
  ]);
  const sidebar = (/** @type {any} */ from, /** @type {Record<string, any>} */ change) =>
    reduce(from, { type: 'sidebar', ...change });

  assert.deepEqual(shape(state), [
    ['pinned', false, [], ['pin']],
    ['projects', false, [['p1', false, ['p1a']], ['p2', false, ['p2a']]], []],
    ['recent', false, [], ['new', 'old']],
  ]);
  const collapsedProject = sidebar(state, { change: 'toggle', key: 'project:p2' });
  assert.deepEqual(shape(collapsedProject)[2], ['recent', false, [], ['new', 'p2a', 'old']]);
  const collapsedProjects = sidebar(state, { change: 'toggle', key: 'projects' });
  assert.deepEqual(shape(collapsedProjects).slice(1), [
    ['projects', true, [['p1', false, ['p1a']], ['p2', false, ['p2a']]], []],
    ['recent', false, [], ['new', 'p1a', 'p2a', 'old']],
  ]);
  const archive = sidebar(state, { change: 'mode', mode: 'archive' });
  assert.deepEqual(shape(archive), [['archive', false, [], ['gone']]]);
  const searching = sidebar(sidebar(state, { change: 'search', isSearching: true }), { change: 'query', query: 'LOGIN' });
  assert.deepEqual(shape(searching), [['results', false, [], ['p1a']]]);
  assert.equal(sidebar(searching, { change: 'search', isSearching: false }).sidebar.query, '');
});

test('a row menu and a title editor exclude each other', () => {
  const menu = reduce(listed, { type: 'sidebar', change: 'menu', chatId: 'a' });
  const renaming = reduce(menu, { type: 'sidebar', change: 'rename', chatId: 'a' });

  assert.equal(menu.sidebar.menu, 'a');
  assert.equal(renaming.sidebar.menu, null);
  assert.equal(renaming.sidebar.renaming, 'a');
  assert.equal(reduce(renaming, { type: 'sidebar', change: 'mode', mode: 'archive' }).sidebar.renaming, null);
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
  const archived = reduce(listed, { type: 'chats', projects: [], chats: [{ ...listed.chats[0], archived: true }], permissions: [] });
  assert.equal(canSend(reduce(archived, { type: 'open', chatId: 'a' }), 'Hi'), false);
  assert.equal(canSend(reduce(withModels, { type: 'compose', projectId: 'p1' }), 'Hi'), true);
  assert.equal(canSend(reduce(withModels, { type: 'compose' }), '  '), false);
  assert.equal(canSend(reduce(listed, { type: 'compose' }), 'Hi'), false, 'a new chat needs a model');
  const noChats = reduce(withModels, { type: 'status', status: 'ready', features: { chat: false } });
  assert.equal(canSend(reduce(noChats, { type: 'compose' }), 'Hi'), false);
});

test('the composer follows the studio: new chats on the panel\'s or the start page\'s model, chats on their own', () => {
  const newChat = reduce(withModels, { type: 'compose', projectId: null });
  const inProject = reduce(withModels, { type: 'compose', projectId: 'p1' });
  const chosen = reduce(inProject, { type: 'draft', modelId: 'local', approval: 'auto_edits' });
  const chat = reduce(withModels, { type: 'open', chatId: 'b' });

  assert.equal(composerView(newChat).model?.id, 'gpt');
  assert.equal(composerView(newChat).effort, 'high');
  assert.deepEqual(composerView(inProject).models.map((it) => it.id), ['gpt', 'codex', 'other']);
  assert.equal(composerView(chosen).model, null, 'a model without projects cannot start a project chat');
  assert.equal(composerView(chosen).approval, 'auto_edits');
  assert.deepEqual(composerView(chat).models.map((it) => it.id), ['gpt', 'codex']);
  assert.equal(composerView(chat).effort, 'low');
  assert.equal(composerView(chat).isLocked, false);
  assert.equal(composerView(reduce(withModels, { type: 'open', chatId: 'a' })).isLocked, true, 'a chat without a model');
});

test('composer menus toggle and close when the chat changes', () => {
  const open = reduce(withModels, { type: 'composerMenu', menu: 'model' });

  assert.equal(open.composerMenu, 'model');
  assert.equal(reduce(open, { type: 'composerMenu', menu: 'model' }).composerMenu, null);
  assert.equal(reduce(open, { type: 'open', chatId: 'a' }).composerMenu, null);
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


test('existing detached chats only offer models of their connection, while new chats remain unrestricted', () => {
  const opened = reduce(withModels, { type: 'open', chatId: 'b' });
  const detached = { ...opened, chats: opened.chats.map((chat) => ({ ...chat, projectId: null })) };
  assert.deepEqual(composerView(detached).models.map((model) => model.id), ['gpt', 'codex']);

  const fixed = {
    ...detached,
    composer: { ...detached.composer, models: detached.composer.models.map((model) => ({ ...model, connection: null })) },
  };
  assert.deepEqual(composerView(fixed).models.map((model) => model.id), ['gpt'], 'no in-place switching capability');
  assert.deepEqual(composerView(reduce(detached, { type: 'compose' })).models.map((model) => model.id),
    ['gpt', 'codex', 'local', 'other']);
});
