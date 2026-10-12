// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PANEL_PORT, createPanelHub } from '../src/background/panel-hub.js';
import { BridgeError } from '../src/shared/protocol.js';
import { FakePort, flush } from './fakes/chrome.js';

const ORIGIN = 'chrome-extension://dngnkjkjppehmiaebojfebjcfkcpdjki/';

function fakeLink() {
  /** @type {{ method: string, params: any, resolve: Function, reject: Function }[]} */
  const calls = [];
  let watches = 0;
  return {
    calls,
    status: 'ready',
    hello: { features: { chat: true } },
    /** @param {string} method @param {any} params */
    request(method, params = {}) {
      return new Promise((resolve, reject) => {
        calls.push({ method, params, resolve, reject });
        if (method.endsWith('.watch')) resolve({ watch: `w:${++watches}` });
        if (method === 'watch.cancel') resolve({});
      });
    },
  };
}

function panelPort(url = `${ORIGIN}src/sidepanel/sidepanel.html`) {
  const port = new FakePort(PANEL_PORT);
  /** @type {any} */ (port).sender = { url };
  return port;
}

test('a panel receives the link status and its watch events', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();

  hub.attach(port);
  port.deliver({ type: 'watch', key: 'chats', method: 'chats.watch', params: {} });
  await flush();
  hub.onEvent('chats.changed', { watch: 'w:1', chats: [] });
  hub.onEvent('chats.changed', { watch: 'w:9', chats: [] });

  assert.deepEqual(port.posted[0], { type: 'status', status: 'ready', features: { chat: true } });
  assert.deepEqual(port.posted.slice(1), [
    { type: 'event', key: 'chats', event: 'chats.changed', params: { watch: 'w:1', chats: [] } },
  ]);
});

test('ports from other pages are refused', () => {
  const hub = createPanelHub({ link: fakeLink(), extensionOrigin: ORIGIN });
  const port = panelPort('https://example.com/');

  hub.attach(port);

  assert.equal(port.isDisconnected, true);
  assert.equal(hub.size, 0);
});

test('switching the chat watch cancels the previous one in the app', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();
  hub.attach(port);

  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'a' } });
  await flush();
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'b' } });
  await flush();
  hub.onEvent('chat.changed', { watch: 'w:1' });

  assert.deepEqual(link.calls.map((it) => [it.method, it.params]), [
    ['chat.watch', { chatId: 'a' }],
    ['watch.cancel', { watch: 'w:1' }],
    ['chat.watch', { chatId: 'b' }],
  ]);
  assert.equal(port.posted.filter((it) => it.type === 'event').length, 0);
});

test('watches are opened again after the app comes back', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();
  hub.attach(port);
  port.deliver({ type: 'watch', key: 'chats', method: 'chats.watch', params: {} });
  await flush();

  link.status = 'waiting_for_app';
  hub.onLinkStatus('waiting_for_app', null);
  link.status = 'ready';
  hub.onLinkStatus('ready', { features: { chat: true } });
  await flush();
  hub.onEvent('chats.changed', { watch: 'w:2' });

  assert.equal(link.calls.filter((it) => it.method === 'chats.watch').length, 2);
  assert.deepEqual(port.posted.at(-1), { type: 'event', key: 'chats', event: 'chats.changed', params: { watch: 'w:2' } });
});

test('closing a panel cancels its watches', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();
  hub.attach(port);
  port.deliver({ type: 'watch', key: 'chats', method: 'chats.watch', params: {} });
  await flush();

  port.onDisconnect.emit();

  assert.equal(hub.size, 0);
  assert.deepEqual(link.calls.at(-1)?.params, { watch: 'w:1' });
});

test('panel calls are limited to chat methods and answered by id', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();
  hub.attach(port);

  port.deliver({ type: 'call', id: 1, method: 'chat.send', params: { text: 'Hi' } });
  port.deliver({ type: 'call', id: 2, method: 'cdp.send', params: {} });
  port.deliver({ type: 'call', id: 3, method: 'chat.stop', params: { chatId: 'a' } });
  link.calls[0].resolve({ chatId: 'c1' });
  link.calls[1].reject(new BridgeError('busy', 'Running'));
  await flush();

  const replies = port.posted.filter((it) => it.type === 'reply');
  assert.deepEqual(replies, [
    { type: 'reply', id: 2, error: { code: 'unknown_method', message: 'Unknown method' } },
    { type: 'reply', id: 1, result: { chatId: 'c1' } },
    { type: 'reply', id: 3, error: { code: 'busy', message: 'Running' } },
  ]);
});
