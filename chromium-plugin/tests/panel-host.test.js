// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pendingAttachmentKey } from '../src/background/context-menu.js';
import { createPanelHost } from '../src/sidepanel/panel-host.js';
import { FakeEvent, FakePort, flush } from './fakes/chrome.js';
import { fakeLocks } from './fakes/locks.js';

/** @param {{ tabs?: any[], session?: Record<string, unknown> }} [options] */
function setup({ tabs = [], session = {} } = {}) {
  const port = new FakePort('');
  const onChanged = new FakeEvent();
  const chrome = {
    runtime: {
      connect: (/** @type {{ name: string }} */ info) => {
        port.name = info.name;
        return port;
      },
    },
    windows: { getCurrent: async () => ({ id: 7 }) },
    tabs: { query: async () => tabs },
    storage: {
      session: {
        get: async (/** @type {string} */ key) => ({ [key]: session[key] }),
        remove: async (/** @type {string} */ key) => {
          delete session[key];
        },
        onChanged,
      },
    },
  };
  return { port, onChanged, host: createPanelHost(chrome, fakeLocks()) };
}

test('messages before listen are delivered first and in order, later ones live', () => {
  const { port, host } = setup();
  /** @type {string[]} */
  const received = [];
  port.deliver({ type: 'status', status: 'connecting' });
  port.deliver({ type: 'agent', isAllowed: false, controlled: 0 });

  host.listen((json) => received.push(json));
  port.deliver({ type: 'status', status: 'ready' });

  assert.equal(port.name, 'heartbeat-panel');
  assert.deepEqual(received.map((json) => JSON.parse(json).status ?? JSON.parse(json).type), [
    'connecting',
    'agent',
    'ready',
  ]);
});

test('posted JSON reaches the service worker as an object', () => {
  const { port, host } = setup();

  host.post(JSON.stringify({ type: 'unwatch', key: 'chat' }));

  assert.deepEqual(port.last(), { type: 'unwatch', key: 'chat' });
});

test('a pending selection of this window is taken once', async () => {
  const page = { kind: 'page', url: 'https://example.com/', title: 'Example', text: '', selection: 'Привет' };
  const { host } = setup({ session: { [pendingAttachmentKey(7)]: page } });

  const first = await host.takeSelection();
  const second = await host.takeSelection();

  assert.deepEqual(JSON.parse(String(first)), page);
  assert.equal(second, null);
});

test('a tab without an id cannot be attached', async () => {
  const { host } = setup({ tabs: [{ url: 'https://example.com/' }] });

  assert.equal(await host.capturePage(), null);
});

test('only selections sent to this window wake the panel', async () => {
  const { onChanged, host } = setup();
  let calls = 0;
  host.onSelection(() => calls++);

  onChanged.emit({ [pendingAttachmentKey(8)]: {} });
  onChanged.emit({ [pendingAttachmentKey(7)]: {} });
  await flush();

  assert.equal(calls, 1);
});
