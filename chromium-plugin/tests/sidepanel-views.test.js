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
