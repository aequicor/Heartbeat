// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakePort, FakeTimers, flush } from './fakes/chrome.js';
import { fakeDocument } from './fakes/dom.js';
import { fakeLocks } from './fakes/locks.js';

test('new-chat selector matches submitted project after navigation and same-size project changes', async () => {
  const document = fakeDocument();
  const port = new FakePort('heartbeat-panel');
  const timers = new FakeTimers();
  const globals = /** @type {any} */ (globalThis);
  const oldTimeout = globalThis.setTimeout;
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  globals.document = document;
  globals.chrome = {
    i18n: { getMessage: (/** @type {string} */ key) => key, getUILanguage: () => 'en' },
    runtime: { connect: () => port },
    windows: { getCurrent: async () => ({ id: 1 }) },
    storage: { session: { get: async () => ({}), onChanged: { addListener: () => {} } } },
  };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: fakeLocks() } });
  globalThis.setTimeout = timers.setTimeout;
  try {
    await import('../src/sidepanel/sidepanel.js');
    await flush();
    const projects = [{ id: 'p', name: 'Project P' }];
    const snapshot = (/** @type {{id: string, name: string}[]} */ next) => port.deliver({
      type: 'event', event: 'chats.changed', params: { projects: next, chats: [], permissions: [] },
    });
    port.deliver({ type: 'status', status: 'ready', features: { chat: true } });
    snapshot(projects);
    const byId = document.getElementById;
    const project = byId('project');
    byId('new-chat').fire('click');
    project.value = 'p';
    project.fire('change');
    byId('back').fire('click');
    byId('new-chat').fire('click');
    assert.equal(project.value, '', 'new composer must visually reset to no project');
    byId('prompt').value = 'Hello';
    byId('send').fire('click');
    assert.equal(port.last().params.projectId, null);
    port.deliver({ type: 'reply', id: port.last().id, result: { chatId: 'new' } });
    await flush();
    byId('new-chat').fire('click');
    project.value = 'p';
    project.fire('change');
    snapshot([{ id: 'p', name: 'Renamed' }]);
    assert.equal(project.options[1].textContent, 'Renamed');
    assert.equal(project.value, 'p');
    snapshot([{ id: 'q', name: 'Replacement' }]);
    assert.equal(project.options[1].value, 'q');
    assert.equal(project.value, '', 'removed selection resets state and UI together');
    project.value = 'q';
    project.fire('change');
    byId('prompt').value = 'Next';
    byId('send').fire('click');
    assert.equal(port.last().params.projectId, 'q');
    port.deliver({ type: 'reply', id: port.last().id, result: { chatId: 'next' } });
    await flush();
  } finally {
    globalThis.setTimeout = oldTimeout;
    if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator);
    else delete globals.navigator;
    delete globals.chrome;
    delete globals.document;
  }
});
