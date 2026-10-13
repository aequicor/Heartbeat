// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakePort, FakeTimers, flush } from './fakes/chrome.js';
import { fakeDocument } from './fakes/dom.js';
import { fakeLocks } from './fakes/locks.js';

test('the new chat\'s project pill matches the submitted project across navigation and project changes', async () => {
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
    const composer = {
      models: [{ id: 'm', name: 'Model', shortName: 'M', projects: true }],
      newChat: { modelId: 'm', approval: 'ask' },
    };
    const snapshot = (/** @type {{id: string, name: string}[]} */ next) => port.deliver({
      type: 'event', event: 'chats.changed', params: { projects: next, chats: [], permissions: [], composer },
    });
    port.deliver({ type: 'status', status: 'ready', features: { chat: true } });
    snapshot([{ id: 'p', name: 'Project P' }]);
    const byId = document.getElementById;
    const context = byId('composer-context');
    const pill = () => context.querySelector('button');
    const choose = (/** @type {string} */ label) => {
      pill().fire('click');
      context.querySelectorAll('button').find((it) => it.className.startsWith('menu-item') && it.textContent === label)
        .fire('click');
    };
    const enter = () => byId('prompt').fire('keydown', { key: 'Enter', preventDefault: () => {} });

    byId('new-chat').fire('click');
    choose('Project P');
    assert.equal(pill().textContent, 'Project P');
    byId('back').fire('click');
    byId('new-chat').fire('click');
    assert.equal(pill().textContent, 'withoutProject', 'a new composer starts without a project');
    byId('prompt').value = 'Hello';
    enter();
    assert.equal(port.last().params.projectId, null);
    assert.deepEqual(port.last().params.settings, { modelId: 'm', approval: 'ask' });
    port.deliver({ type: 'reply', id: port.last().id, result: { chatId: 'new' } });
    await flush();
    byId('new-chat').fire('click');
    choose('Project P');
    snapshot([{ id: 'p', name: 'Renamed' }]);
    assert.equal(pill().textContent, 'Renamed');
    snapshot([{ id: 'q', name: 'Replacement' }]);
    assert.equal(pill().textContent, 'withoutProject', 'a removed project resets the choice');
    choose('Replacement');
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
