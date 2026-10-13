// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakePort, FakeTimers, flush } from './fakes/chrome.js';
import { fakeDocument } from './fakes/dom.js';
import { fakeLocks } from './fakes/locks.js';

test('session action menus survive the opening click, switch rows and dismiss outside or with Escape', async () => {
  const document = fakeDocument();
  const port = new FakePort('heartbeat-panel');
  const globals = /** @type {any} */ (globalThis);
  const oldTimeout = globalThis.setTimeout;
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  globals.document = document;
  globals.chrome = {
    i18n: { getMessage: (/** @type {string} */ key) => key },
    runtime: { connect: () => port },
    windows: { getCurrent: async () => ({ id: 1 }) },
    storage: { session: { get: async () => ({}), onChanged: { addListener: () => {} } } },
  };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: fakeLocks() } });
  globalThis.setTimeout = new FakeTimers().setTimeout;
  try {
    await import('../src/sidepanel/sidepanel.js');
    await flush();
    port.deliver({
      type: 'event', event: 'chats.changed',
      params: {
        projects: [], permissions: [],
        chats: ['a', 'b'].map((id) => ({ id, title: id, updatedAt: 1, running: false, continuable: true })),
      },
    });
    const content = document.getElementById('content');
    const row = (/** @type {string} */ id) => content.querySelectorAll('div').find((it) => it.attrs['data-chat'] === id);
    const more = (/** @type {string} */ id) => row(id).querySelectorAll('button').find((it) => it.title === 'sessionActions');
    const menu = () => content.querySelectorAll('div').find((it) => it.attrs.role === 'menu');
    const click = (/** @type {any} */ node) => node.fire('click', { bubbles: true });

    const original = more('a');
    click(original.querySelector('path'));
    assert.notEqual(more('a'), original, 'render replaces the original click target tree');
    assert.equal(menu()?.parentElement.attrs['data-chat'], 'a', 'document bubbling must keep the new menu open');
    click(more('a'));
    assert.equal(menu(), undefined, 'clicking the same action again closes its menu');
    click(more('a'));
    click(more('b'));
    assert.equal(menu()?.parentElement.attrs['data-chat'], 'b', 'another action switches the open menu');
    click(document.getElementById('title'));
    assert.equal(menu(), undefined, 'clicking outside dismisses the menu');
    click(more('a'));
    more('a').fire('keydown', { key: 'Escape', bubbles: true });
    assert.equal(menu(), undefined, 'Escape dismisses the menu');
  } finally {
    globalThis.setTimeout = oldTimeout;
    if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator);
    else delete globals.navigator;
    delete globals.chrome;
    delete globals.document;
  }
});
