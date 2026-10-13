// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createPermissionRenderer } from '../src/sidepanel/views/permission-card.js';
import { createTranscriptRenderer } from '../src/sidepanel/views/chat-view.js';
import { initialState } from '../src/sidepanel/store.js';
import { fakeDocument } from './fakes/dom.js';

const t = (/** @type {string} */ key) => key;

test('permission fields retain draft, checked options and focus across fresh list snapshots', () => {
  const document = fakeDocument();
  const container = document.createElement('div');
  const answers = /** @type {any[]} */ ([]);
  const render = createPermissionRenderer(document, container, t, (...args) => answers.push(args));
  const text = { chatId: 'a', requestId: 'r1', title: 'Question', options: [{ id: 'ok', title: 'OK' }], input: { kind: 'text' } };
  const choice = { ...text, requestId: 'r2', input: { kind: 'multi', choices: [{ id: 'x', title: 'X' }, { id: 'y', title: 'Y' }] } };
  render([text, choice]);
  const input = container.querySelector('input');
  input.value = 'Answer draft';
  input.focus();
  const boxes = container.querySelectorAll('input');
  boxes[1].checked = true;
  render(structuredClone([text, choice]));
  assert.equal(container.querySelector('input'), input);
  assert.equal(document.activeElement, input);
  assert.equal(input.value, 'Answer draft');
  assert.equal(container.querySelectorAll('input')[1].checked, true);
  container.querySelectorAll('button')[0].fire('click');
  container.querySelectorAll('button')[1].fire('click');
  assert.deepEqual(answers.map((it) => it[2]), [{ text: 'Answer draft' }, { selected: ['x'] }]);
  render([choice]);
  assert.equal(container.contains(input), false);
  render([text, choice]);
  assert.equal(container.querySelector('input').value, '', 'a new request must not reuse a removed draft');
});

test('streaming preserves open details and summary focus while updating output and truncation notices', () => {
  const document = fakeDocument();
  const container = document.createElement('div');
  const render = createTranscriptRenderer(document, container, t);
  const reply = { id: 'r', type: 'reply', createdAt: 0, streaming: true, parts: [
    { type: 'reasoning', text: 'Thinking' },
    { type: 'tool', id: 'tool1', title: 'Build', status: 'running', output: 'First' },
  ] };
  render({ ...initialState, selected: 'a', transcript: [reply] });
  const details = container.querySelectorAll('details');
  details.forEach((/** @type {any} */ it) => { it.open = true; });
  const summary = details[1].querySelector('summary');
  summary.focus();
  const next = structuredClone(reply);
  next.parts[0].text = 'Thinking more';
  next.parts[1].output = 'Last';
  render({ ...initialState, selected: 'a', transcript: [{ ...next, truncated: true }] });
  assert.deepEqual(container.querySelectorAll('details'), details);
  assert.equal(details.every((/** @type {any} */ it) => it.open), true);
  assert.equal(document.activeElement, summary);
  assert.equal(details[1].querySelector('pre').textContent, 'Last');
  assert.equal(container.textContent.includes('truncated'), true);
  render({ ...initialState, selected: 'b', transcript: [reply] });
  assert.equal(container.querySelector('details').open, false, 'a different chat must not inherit disclosure state');
});

test('session rows show the studio status, actions and menu, and edit titles inline', async () => {
  const { renderChatList } = await import('../src/sidepanel/views/chat-list.js');
  const { reduce } = await import('../src/sidepanel/store.js');
  const document = fakeDocument();
  const calls = /** @type {any[][]} */ ([]);
  const chat = { running: false, pinned: false, unread: false, continuable: true, projectId: null };
  let state = reduce(initialState, {
    type: 'chats',
    projects: [{ id: 'p1', name: 'Heartbeat' }],
    chats: [
      { ...chat, id: 'wait', title: 'Waiting', updatedAt: 4, running: true },
      { ...chat, id: 'run', title: 'Running', updatedAt: 3, running: true },
      { ...chat, id: 'new', title: 'Unread', updatedAt: 2, unread: true },
      { ...chat, id: 'pin', title: 'Pinned', updatedAt: 1, pinned: true },
    ],
    permissions: [{ chatId: 'wait', requestId: 'r', title: 'Run?', options: [] }],
  });
  /** @type {import('../src/sidepanel/views/chat-list.js').ListContext} */
  const context = {
    t,
    onOpen: (id) => calls.push(['open', id]),
    onNewChat: (id) => calls.push(['new', id]),
    onToggle: (key) => calls.push(['toggle', key]),
    onMenu: (id) => calls.push(['menu', id]),
    onRename: (id) => calls.push(['rename', id]),
    onEdit: (...args) => calls.push(['edit', ...args]),
  };
  const rows = (/** @type {any} */ list) => list.querySelectorAll('div').filter((it) => it.attrs['data-chat']);
  const slot = (/** @type {any} */ row) => row.querySelectorAll('span').find((it) => it.className.startsWith('status'));

  const list = renderChatList(document, state, context);
  const byId = Object.fromEntries(rows(list).map((row) => [row.attrs['data-chat'], row]));

  assert.equal(slot(byId.wait).title, 'awaitingPermission');
  assert.equal(slot(byId.run).className, 'status sticky');
  assert.equal(slot(byId.new).children[0].className, 'dot');
  assert.equal(slot(byId.pin).children[0].tagName, 'svg');
  list.querySelectorAll('button').find((it) => it.title === 'newSessionIn')?.fire('click');
  byId.new.querySelectorAll('button').find((it) => it.title === 'archive')?.fire('click');
  assert.deepEqual(calls, [['new', 'p1'], ['edit', 'new', 'archive', true]]);

  state = reduce(state, { type: 'sidebar', change: 'menu', chatId: 'new' });
  const menu = rows(renderChatList(document, state, context)).find((row) => row.attrs['data-chat'] === 'new')
    .querySelectorAll('button').filter((it) => it.className === 'menu-item').map((it) => it.textContent);
  assert.deepEqual(menu, ['rename', 'pin', 'markRead', 'archive']);

  state = reduce(state, { type: 'sidebar', change: 'rename', chatId: 'new' });
  const field = renderChatList(document, state, context).querySelector('input');
  field.value = '  Renamed  ';
  calls.length = 0;
  field.fire('keydown', { key: 'Enter', preventDefault: () => {} });
  field.fire('blur');
  assert.deepEqual(calls, [['rename', null], ['edit', 'new', 'rename', 'Renamed']]);
});

test('composer pills offer what the model supports and wait for pending changes', async () => {
  const { renderComposerLead, renderComposerTrail } = await import('../src/sidepanel/views/composer.js');
  const document = fakeDocument();
  const picked = /** @type {any[][]} */ ([]);
  const context = {
    t,
    canAttach: true,
    onMenu: (/** @type {string} */ menu) => picked.push(['menu', menu]),
    onProject: () => {},
    onTemplate: (/** @type {string} */ text) => picked.push(['template', text]),
    onApproval: (/** @type {string} */ approval) => picked.push(['approval', approval]),
    onEffort: (/** @type {string | null} */ effort) => picked.push(['effort', effort]),
    onModel: (/** @type {string} */ id) => picked.push(['model', id]),
    onAttachPage: () => {},
  };
  const gpt = { id: 'gpt', name: 'GPT', shortName: 'GPT', efforts: ['low', 'high'], trust: true };
  const local = { id: 'local', name: 'Local', shortName: 'Local' };
  const view = { isNew: false, model: gpt, models: [gpt, local], effort: 'low', approval: 'ask', isLocked: false };
  const labels = (/** @type {any[]} */ nodes) => nodes.flatMap((it) => it.querySelectorAll('button'))
    .filter((it) => it.className.startsWith('menu-item')).map((it) => it.textContent);

  const closed = renderComposerTrail(document, { ...initialState, composerMenu: null }, view, context);
  assert.equal(closed.length, 2, 'effort and model pills');
  assert.equal(closed[0].querySelector('button').textContent, 'low');
  assert.deepEqual(labels(renderComposerTrail(document, { ...initialState, composerMenu: 'effort' }, view, context)),
    ['effortAuto', 'low', 'high']);
  assert.deepEqual(labels(renderComposerLead(document, { ...initialState, composerMenu: 'add' }, view, context)),
    ['templatePlan', 'templateTests', 'templateReview', 'approvalAsk', 'approvalEdits', 'approvalAuto']);
  const plain = { ...view, model: local, effort: null };
  assert.equal(renderComposerTrail(document, initialState, plain, context).length, 1, 'no effort levels, no pill');
  assert.deepEqual(labels(renderComposerLead(document, { ...initialState, composerMenu: 'add' }, plain, context)),
    ['templatePlan', 'templateTests', 'templateReview']);
  const locked = renderComposerTrail(document, { ...initialState, composerMenu: 'model' }, { ...view, isLocked: true }, context);
  assert.equal(locked.at(-1).querySelector('button').disabled, true);
  assert.deepEqual(labels(locked), [], 'a locked picker does not open');
});
