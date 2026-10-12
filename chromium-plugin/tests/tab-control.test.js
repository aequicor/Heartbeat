// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createTabControl } from '../src/background/tab-control.js';
import { isControllableUrl } from '../src/background/url-policy.js';
import { BridgeError } from '../src/shared/protocol.js';
import { FakeEvent, FakeTimers } from './fakes/chrome.js';

function fakeBrowser() {
  /** @type {any[]} */
  const commands = [];
  /** @type {Map<number, any>} */
  const tabs = new Map([[1, { id: 1, url: 'https://user.example/', title: 'Mail', status: 'complete' }]]);
  let nextTab = 10;
  let nextContext = 100;
  const chrome = {
    debugger: {
      onEvent: new FakeEvent(),
      onDetach: new FakeEvent(),
      attached: new Set(),
      attach: async (/** @type {any} */ target) => chrome.debugger.attached.add(target.tabId),
      detach: async (/** @type {any} */ target) => chrome.debugger.attached.delete(target.tabId),
      sendCommand: async (/** @type {any} */ target, /** @type {string} */ method, /** @type {any} */ params) => {
        commands.push([target.tabId, method, params]);
        if (method === 'Page.getFrameTree') return { frameTree: { frame: { id: `frame-${target.tabId}` } } };
        if (method === 'Page.createIsolatedWorld') return { executionContextId: nextContext++ };
        if (method === 'Page.navigate') {
          const tab = tabs.get(target.tabId);
          tab.url = params.url;
          queueMicrotask(() => chrome.tabs.onUpdated.emit(target.tabId, { status: 'complete' }, tab));
          return { frameId: `frame-${target.tabId}` };
        }
        return { ok: method };
      },
    },
    tabs: {
      onRemoved: new FakeEvent(),
      onUpdated: new FakeEvent(),
      get: async (/** @type {number} */ id) => {
        if (!tabs.has(id)) throw new Error('No tab');
        return tabs.get(id);
      },
      query: async () => [tabs.get(1)],
      create: async () => {
        const tab = { id: nextTab++, url: 'about:blank', title: '', status: 'complete' };
        tabs.set(tab.id, tab);
        return tab;
      },
      update: async (/** @type {number} */ id, /** @type {any} */ change) => commands.push([id, 'update', change]),
      remove: async (/** @type {number} */ id) => tabs.delete(id),
      group: async () => 7,
    },
    tabGroups: { update: async (/** @type {number} */ id, /** @type {any} */ change) => commands.push([id, 'group', change]) },
  };
  return { chrome, commands, tabs };
}

function setup(isAllowed = true) {
  const browser = fakeBrowser();
  const timers = new FakeTimers();
  /** @type {[string, any][]} */
  const events = [];
  const control = createTabControl({
    chrome: browser.chrome,
    isAllowed: () => isAllowed,
    notify: (event, params) => events.push([event, params]),
    timers,
  });
  return { ...browser, timers, events, control };
}

test('only web pages outside the stores can be controlled', () => {
  assert.equal(isControllableUrl('https://example.com/'), true);
  assert.equal(isControllableUrl('http://localhost:3000/'), true);
  for (const url of ['chrome://settings', 'chrome-extension://x/', 'file:///etc/hosts', 'about:blank',
    'https://chromewebstore.google.com/detail/x', 'https://chrome.google.com/webstore/x', '', 'not a url']) {
    assert.equal(isControllableUrl(url), false, url);
  }
});

test('an agent tab opens blank in the Heartbeat group under the debugger and the navigation guard', async () => {
  const { control, chrome, commands, events } = setup();

  const opened = await control.handle('tabs.open', {});
  const navigated = await control.handle('tabs.navigate', { tabId: opened.tab.id, url: 'https://example.com/' });

  assert.deepEqual(opened, { tab: { id: '10', url: '', title: '' } });
  assert.equal(navigated.tab.url, 'https://example.com/');
  assert.equal(chrome.debugger.attached.has(10), true);
  assert.deepEqual(commands.find((it) => it[1] === 'group'), [7, 'group', { title: 'Heartbeat', color: 'pink' }]);
  assert.deepEqual(commands.find((it) => it[1] === 'Fetch.enable')?.[2], {
    patterns: [{ resourceType: 'Document', requestStage: 'Request' }],
  });
  assert.deepEqual(events.find((it) => it[0] === 'tabs.summary'), ['tabs.summary', { controlled: 1 }]);
});

test('nothing is controlled without consent and other tabs are refused', async () => {
  const off = setup(false);
  await assert.rejects(off.control.handle('tabs.open', {}), (error) => error instanceof BridgeError && error.code === 'forbidden');

  const { control } = setup();
  await assert.rejects(control.handle('cdp.send', { tabId: '1', method: 'Page.getFrameTree' }), (error) => error.code === 'no_such_tab');
  await assert.rejects(control.handle('tabs.navigate', { tabId: '1', url: 'https://x/' }), (error) => error.code === 'no_such_tab');
});

test('DevTools calls are limited to the driver methods and its own isolated worlds', async () => {
  const { control } = setup();
  await control.handle('tabs.control', { tabId: '1' });

  const world = await control.handle('cdp.send', {
    tabId: '1',
    method: 'Page.createIsolatedWorld',
    params: { frameId: 'frame-1', worldName: 'heartbeat' },
  });
  const evaluated = await control.handle('cdp.send', {
    tabId: '1',
    method: 'Runtime.evaluate',
    params: { contextId: world.result.executionContextId, expression: '1' },
  });

  assert.deepEqual(evaluated, { result: { ok: 'Runtime.evaluate' } });
  await assert.rejects(
    control.handle('cdp.send', { tabId: '1', method: 'Runtime.evaluate', params: { expression: 'document.cookie' } }),
    (error) => error.code === 'forbidden',
  );
  await assert.rejects(
    control.handle('cdp.send', { tabId: '1', method: 'Network.getCookies', params: {} }),
    (error) => error.code === 'forbidden',
  );
});

test('guarded loads wait for the app and fail closed', async () => {
  const { control, chrome, commands, events, timers } = setup();
  await control.handle('tabs.control', { tabId: '1' });

  chrome.debugger.onEvent.emit({ tabId: 1 }, 'Fetch.requestPaused', { requestId: 'r1', frameId: 'frame-1', request: { url: 'https://ok.example/' } });
  chrome.debugger.onEvent.emit({ tabId: 1 }, 'Fetch.requestPaused', { requestId: 'r2', frameId: 'child', request: { url: 'https://slow.example/' } });
  chrome.debugger.onEvent.emit({ tabId: 1 }, 'Fetch.requestPaused', { requestId: 'r3', frameId: 'frame-1', request: { url: 'chrome://settings' } });
  await control.handle('guard.resolve', { tabId: '1', requestId: 'r1', allow: true });
  timers.advance(10_000);
  await Promise.resolve();

  assert.deepEqual(events.filter((it) => it[0] === 'guard.paused').map((it) => [it[1].requestId, it[1].mainFrame]), [
    ['r1', true],
    ['r2', false],
  ]);
  assert.deepEqual(commands.filter((it) => it[1].startsWith('Fetch.') && it[1] !== 'Fetch.enable').map((it) => [it[1], it[2].requestId]), [
    ['Fetch.failRequest', 'r3'],
    ['Fetch.continueRequest', 'r1'],
    ['Fetch.failRequest', 'r2'],
  ]);
  await assert.rejects(control.handle('guard.resolve', { tabId: '1', requestId: 'r2', allow: true }), (error) => error.code === 'stale_request');
});

test('cancelling the debugger infobar revokes and closing the tab removes it', async () => {
  const { control, chrome, events } = setup();
  await control.handle('tabs.control', { tabId: '1' });
  const opened = await control.handle('tabs.open', {});

  chrome.debugger.onDetach.emit({ tabId: 1 }, 'canceled_by_user');
  chrome.tabs.onRemoved.emit(Number(opened.tab.id));

  assert.deepEqual(events.filter((it) => it[0] === 'tab.revoked' || it[0] === 'tab.removed'), [
    ['tab.revoked', { tabId: '1' }],
    ['tab.removed', { tabId: '10' }],
  ]);
  assert.equal(control.size, 0);
});

test('revoking all detaches every tab and tells the app', async () => {
  const { control, chrome, events } = setup();
  await control.handle('tabs.control', { tabId: '1' });
  await control.handle('tabs.open', {});

  const result = await control.handle('tabs.revokeAll', {});

  assert.deepEqual(result, { released: 2 });
  assert.equal(chrome.debugger.attached.size, 0);
  assert.deepEqual(events.at(-1), ['tabs.summary', { controlled: 0 }]);
});
