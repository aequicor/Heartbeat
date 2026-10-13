// @ts-check
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createNativeLink } from '../src/background/native-link.js';
import { BridgeError } from '../src/shared/protocol.js';
import { FakeTimers, fakeChrome, flush } from './fakes/chrome.js';

/** Shared with the app's `ProtocolFixturesTest`. @param {string} name */
function fixture(name) {
  return JSON.parse(readFileSync(new URL(`./fixtures/protocol/${name}.json`, import.meta.url), 'utf8'));
}

function setup(overrides = {}) {
  const fake = fakeChrome();
  const timers = new FakeTimers();
  /** @type {string[]} */
  const statuses = [];
  /** @type {[string, any][]} */
  const events = [];
  const link = createNativeLink({
    chrome: fake.chrome,
    extensionVersion: '0.1.0',
    browser: { brand: 'Google Chrome', version: '140' },
    capabilities: () => ['chat'],
    onStatus: (status) => statuses.push(status),
    onEvent: (event, params) => events.push([event, params]),
    timers,
    now: timers.now,
    ...overrides,
  });
  return { fake, timers, statuses, events, link };
}

/** Brings the link to `ready` through the host's `connected` frame and a successful hello. */
async function ready(context) {
  context.link.connect();
  const port = context.fake.ports.at(-1);
  port.deliver({ kind: 'host', state: 'connected' });
  const hello = port.last();
  port.deliver({ ...fixture('hello-result'), id: hello.id });
  await flush();
  return port;
}

test('hello is sent once the host reports Heartbeat and carries version, browser and capabilities', async () => {
  const context = setup();
  const port = await ready(context);

  assert.deepEqual(port.posted[0], fixture('hello-request'));
  assert.deepEqual(context.statuses, ['connecting', 'handshaking', 'ready']);
  assert.deepEqual(context.link.hello?.features, { chat: true, agentControl: false });
});

test('requests resolve with results and reject with bridge errors', async () => {
  const context = setup();
  const port = await ready(context);

  const ok = context.link.request('chats.watch');
  port.deliver({ kind: 'res', id: port.last().id, result: { watch: 'w1' } });
  assert.deepEqual(await ok, { watch: 'w1' });

  const refused = context.link.request('chat.send', { text: 'x' });
  port.deliver({ kind: 'res', id: port.last().id, error: { code: 'busy', message: 'Chat is running' } });
  await assert.rejects(refused, (error) => error instanceof BridgeError && error.code === 'busy');
});

test('requests time out and fail when Heartbeat goes away', async () => {
  const context = setup();
  const port = await ready(context);

  const slow = context.link.request('chats.watch', {}, 1_000);
  context.timers.advance(1_001);
  await assert.rejects(slow, (error) => error instanceof BridgeError && error.code === 'timeout');

  const abandoned = context.link.request('chats.watch');
  port.deliver({ kind: 'host', state: 'waiting' });
  await assert.rejects(abandoned, (error) => error instanceof BridgeError && error.code === 'unavailable');
  assert.equal(context.link.status, 'waiting_for_app');
  await assert.rejects(context.link.request('chats.watch'), (error) => error.code === 'unavailable');
});

test('an unregistered host is reported and retried with backoff', () => {
  const context = setup();
  context.link.connect();

  context.fake.disconnect('Specified native messaging host not found.');

  assert.equal(context.link.status, 'host_missing');
  assert.equal(context.fake.ports.length, 1);
  context.timers.advance(1_000);
  assert.equal(context.fake.ports.length, 2);
  context.fake.disconnect('Specified native messaging host not found.');
  context.timers.advance(1_999);
  assert.equal(context.fake.ports.length, 2);
  context.timers.advance(1);
  assert.equal(context.fake.ports.length, 3);
});

test('a forbidden host and an incompatible app are told apart', async () => {
  const forbidden = setup();
  forbidden.link.connect();
  forbidden.fake.disconnect('Access to the specified native messaging host is forbidden.');
  assert.equal(forbidden.link.status, 'forbidden');

  const old = setup();
  old.link.connect();
  const port = old.fake.ports[0];
  port.deliver({ kind: 'host', state: 'connected' });
  port.deliver({ kind: 'res', id: port.last().id, error: { code: 'unsupported_protocol', message: 'Supported: 2' } });
  await flush();
  assert.equal(old.link.status, 'incompatible');
});

test('app calls are answered and local failures keep their messages private', async () => {
  const context = setup({
    onRequest: async (/** @type {string} */ method) => {
      if (method === 'tabs.active') return { tabId: 7 };
      if (method === 'cdp.send') throw new BridgeError('forbidden', 'Not allowed');
      throw new Error('file:///private/path');
    },
  });
  const port = await ready(context);

  port.deliver({ kind: 'req', id: 'a:1', method: 'tabs.active', params: {} });
  port.deliver({ kind: 'req', id: 'a:2', method: 'cdp.send', params: {} });
  port.deliver({ kind: 'req', id: 'a:3', method: 'other', params: {} });
  await flush();

  const answers = port.posted.filter((message) => message.kind === 'res');
  assert.deepEqual(answers, [
    { kind: 'res', id: 'a:1', result: { tabId: 7 } },
    { kind: 'res', id: 'a:2', error: { code: 'forbidden', message: 'Not allowed' } },
    { kind: 'res', id: 'a:3', error: { code: 'internal', message: 'Extension failed' } },
  ]);
});

test('chunked events are reassembled and feature updates refresh the hello result', async () => {
  const context = setup();
  const port = await ready(context);
  const event = { kind: 'evt', event: 'chat.changed', params: { text: 'Ж'.repeat(10) } };
  const bytes = Buffer.from(JSON.stringify(event), 'utf8');

  port.deliver({ kind: 'chunk', of: 'c:1', seq: 1, count: 2, data: bytes.subarray(5).toString('base64') });
  port.deliver({ kind: 'chunk', of: 'c:1', seq: 0, count: 2, data: bytes.subarray(0, 5).toString('base64') });
  port.deliver({ kind: 'evt', event: 'app.features', params: { chat: false, agentControl: false } });

  assert.deepEqual(context.events[0], ['chat.changed', { text: 'Ж'.repeat(10) }]);
  assert.deepEqual(context.link.hello?.features, { chat: false, agentControl: false });
});

test('a reconnect after the port closes performs a fresh handshake', async () => {
  const context = setup();
  await ready(context);

  context.fake.disconnect();
  assert.equal(context.link.status, 'disconnected');
  context.timers.advance(1_000);
  const port = context.fake.ports.at(-1);
  port.deliver({ kind: 'host', state: 'connected' });

  assert.equal(port.last().method, 'hello');
  assert.equal(context.link.status, 'handshaking');
});


test('app socket reconnect drops old asynchronous replies on the retained native port', async () => {
  let finish;
  const context = setup({ onRequest: () => new Promise((resolve) => { finish = resolve; }) });
  const port = await ready(context);
  port.deliver({ kind: 'req', id: 'a:1', method: 'tabs.open' });
  await flush();
  port.deliver({ kind: 'host', state: 'waiting' });
  await ready(context);
  finish({ tab: { id: 'old' } });
  await flush();
  assert.equal(port.posted.filter((message) => message.kind === 'res').length, 0);
});

test('completed old handshake cannot restore ready after the app disconnects', async () => {
  const context = setup();
  context.link.connect();
  const port = context.fake.ports[0];
  port.deliver({ kind: 'host', state: 'connected' });
  port.deliver({ ...fixture('hello-result'), id: port.last().id });
  port.deliver({ kind: 'host', state: 'waiting' });
  await flush();
  assert.equal(context.link.status, 'waiting_for_app');
  assert.equal(context.link.hello, null);
});

test('app reconnect clears partial chunks before the new app reuses chunk ids', async () => {
  const context = setup();
  const port = await ready(context);
  const event = { kind: 'evt', event: 'chat.changed', params: { text: 'fresh' } };
  const bytes = Buffer.from(JSON.stringify(event));
  const first = { kind: 'chunk', of: 'c:1', seq: 0, count: 2, data: bytes.subarray(0, 5).toString('base64') };
  port.deliver(first);
  port.deliver({ kind: 'host', state: 'waiting' });
  await ready(context);
  port.deliver(first);
  port.deliver({ ...first, seq: 1, data: bytes.subarray(5).toString('base64') });
  assert.deepEqual(context.events, [['chat.changed', { text: 'fresh' }]]);
});
