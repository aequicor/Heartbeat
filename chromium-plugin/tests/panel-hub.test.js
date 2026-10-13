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
  assert.deepEqual(port.posted[1], { type: 'agent', isAllowed: false, controlled: 0 });
  assert.deepEqual(port.posted.slice(2), [
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

test('consent and revocation from a panel reach the agent and every panel hears changes', () => {
  /** @type {any[]} */
  const calls = [];
  let state = { isAllowed: false, controlled: 0 };
  const hub = createPanelHub({
    link: fakeLink(),
    extensionOrigin: ORIGIN,
    agent: {
      state: () => state,
      setAllowed: (isAllowed) => calls.push(['consent', isAllowed]),
      revokeAll: () => calls.push(['revoke']),
    },
  });
  const port = panelPort();
  hub.attach(port);

  port.deliver({ type: 'consent', isAllowed: true });
  port.deliver({ type: 'revokeTabs' });
  state = { isAllowed: true, controlled: 2 };
  hub.onAgentChanged();

  assert.deepEqual(calls, [['consent', true], ['revoke']]);
  assert.deepEqual(port.last(), { type: 'agent', isAllowed: true, controlled: 2 });
});

function delayedHub() {
  const link = fakeLink();
  link.request = (method, params = {}) => new Promise((resolve, reject) => link.calls.push({ method, params, resolve, reject }));
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort();
  hub.attach(port);
  return { hub, link, port };
}

test('early reset and deltas are delivered in order only to their watch owner', async () => {
  const { hub, link, port } = delayedHub();
  const other = panelPort();
  hub.attach(other);
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'a' } });
  other.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'b' } });
  hub.onEvent('chat.changed', { watch: 'w:1', chatId: 'a', reset: true, upserts: [{ id: 'history' }] });
  hub.onEvent('chat.changed', { watch: 'w:1', chatId: 'a', reset: false, upserts: [{ id: 'latest' }] });
  hub.onEvent('chat.changed', { watch: 'w:2', chatId: 'b', reset: true, upserts: [] });
  link.calls[1].resolve({ watch: 'w:2' });
  link.calls[0].resolve({ watch: 'w:1' });
  await flush();
  assert.deepEqual(port.posted.filter((it) => it.type === 'event').map((it) => it.params.upserts[0].id), ['history', 'latest']);
  assert.deepEqual(other.posted.filter((it) => it.type === 'event').map((it) => it.params.chatId), ['b']);
});

test('replacing pending watches suppresses stale errors and cancels stale successes', async () => {
  const { hub, link, port } = delayedHub();
  const open = (/** @type {string} */ chatId) => port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId } });
  open('a');
  hub.onEvent('chat.changed', { watch: 'w:1', reset: true });
  open('b');
  open('c');
  link.calls[0].resolve({ watch: 'w:1' });
  link.calls[1].reject(new BridgeError('unavailable', 'Old request'));
  link.calls[2].resolve({ watch: 'w:3' });
  await flush();
  assert.equal(port.posted.some((it) => ['event', 'watchFailed'].includes(it.type)), false);
  assert.deepEqual(link.calls.at(-1)?.params, { watch: 'w:1' });
});

test('reconnect discards early events and does not cancel reused ids in the new connection', async () => {
  const { hub, link, port } = delayedHub();
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'a' } });
  hub.onEvent('chat.changed', { watch: 'w:1', reset: true, upserts: ['old'] });
  hub.onLinkStatus('disconnected', null);
  hub.onLinkStatus('ready', { features: { chat: true } });
  hub.onLinkStatus('ready', { features: { chat: true } });
  assert.equal(link.calls.length, 2, 'features/status refresh must not duplicate pending watches');
  hub.onEvent('chat.changed', { watch: 'w:1', reset: true, upserts: ['new'] });
  link.calls[0].resolve({ watch: 'w:1' });
  link.calls[1].resolve({ watch: 'w:1' });
  await flush();
  assert.equal(link.calls.some((it) => it.method === 'watch.cancel'), false);
  assert.deepEqual(port.posted.filter((it) => it.type === 'event').map((it) => it.params.upserts), [['new']]);
});

test('early buffer overflow cancels the watch instead of showing incomplete history', async () => {
  const { hub, link, port } = delayedHub();
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'a' } });
  for (let index = 0; index < 65; index++) hub.onEvent('chat.changed', { watch: 'w:1', reset: index === 0 });
  link.calls[0].resolve({ watch: 'w:1' });
  await flush();
  assert.equal(port.posted.some((it) => it.type === 'event'), false);
  assert.equal(port.posted.at(-1).type, 'watchFailed');
  assert.equal(link.calls.at(-1)?.method, 'watch.cancel');
});

test('a valid large reset is buffered and closing a pending panel releases its watch', async () => {
  const { hub, link, port } = delayedHub();
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'a' } });
  hub.onEvent('chat.changed', { watch: 'w:1', reset: true, text: 'a'.repeat(7 * 1024 * 1024) });
  link.calls[0].resolve({ watch: 'w:1' });
  await flush();
  assert.equal(port.posted.at(-1).type, 'event');
  port.deliver({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId: 'b' } });
  port.onDisconnect.emit();
  link.calls.find((it) => it.method === 'chat.watch' && it.params.chatId === 'b')?.resolve({ watch: 'w:2' });
  await flush();
  assert.deepEqual(link.calls.at(-1)?.params, { watch: 'w:2' });
});

test('the studio view is watched, its early frames kept and its intents relayed', async () => {
  const link = fakeLink();
  const hub = createPanelHub({ link, extensionOrigin: ORIGIN });
  const port = panelPort(`${ORIGIN}src/sidepanel/panel.html`);
  hub.attach(port);

  port.deliver({ type: 'watch', key: 'studio', method: 'studio.watch', params: { view: 'v1', schema: 'abc' } });
  hub.onEvent('studio.changed', { watch: 'w:1', view: 'v1', frame: { '@type': 'snapshot' } });
  await flush();
  port.deliver({ type: 'call', id: 7, method: 'studio.intent', params: { view: 'v1', intents: [] } });
  await flush();
  link.calls[link.calls.length - 1].resolve({ ack: 0, refused: [] });
  await flush();

  assert.deepEqual(link.calls[0].params, { view: 'v1', schema: 'abc' });
  assert.deepEqual(port.posted.slice(2), [
    {
      type: 'event',
      key: 'studio',
      event: 'studio.changed',
      params: { watch: 'w:1', view: 'v1', frame: { '@type': 'snapshot' } },
    },
    { type: 'reply', id: 7, result: { ack: 0, refused: [] } },
  ]);
});


test('a multipart studio snapshot overflowing a delayed watch is refused and a new watch can recover', async () => {
  const { hub, link, port } = delayedHub();
  const open = () => port.deliver({ type: 'watch', key: 'studio', method: 'studio.watch', params: { view: 'v1' } });
  const part = (watch, index) => hub.onEvent('studio.changed', {
    watch, view: 'v1', frame: { transfer: 'snapshot', index, count: 72, totalBytes: 18 * 1024 * 1024, data: 'YQ==' },
  });
  open();
  for (let index = 0; index < 72; index++) part('w:1', index);
  link.calls[0].resolve({ watch: 'w:1' });
  await flush();
  assert.equal(port.posted.some((it) => it.type === 'event'), false);
  assert.equal(port.posted.at(-1).error.code, 'busy');
  assert.deepEqual(link.calls.at(-1).params, { watch: 'w:1' });

  open();
  link.calls.at(-1).resolve({ watch: 'w:2' });
  await flush();
  for (let index = 0; index < 72; index++) part('w:2', index);
  assert.deepEqual(port.posted.filter((it) => it.type === 'event').map((it) => it.params.frame.index),
    Array.from({ length: 72 }, (_, index) => index));
});
