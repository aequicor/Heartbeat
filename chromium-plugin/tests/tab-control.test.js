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
      query: async (query) => query.active ? [tabs.get(1)] : [...tabs.values()],
      create: async () => {
        const tab = { id: nextTab++, url: 'about:blank', title: '', status: 'complete' };
        tabs.set(tab.id, tab);
        return tab;
      },
      update: async (/** @type {number} */ id, /** @type {any} */ change) => commands.push([id, 'update', change]),
      remove: async (/** @type {number} */ id) => tabs.delete(id),
      group: async ({ tabIds }) => {
        for (const id of tabIds) tabs.get(id).groupId = 7;
        return 7;
      },
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
  return { ...browser, timers, events, control, setAllowed: (value) => { isAllowed = value; } };
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
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });

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
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });

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
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
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
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
  await control.handle('tabs.open', {});

  const result = await control.handle('tabs.revokeAll', {});

  assert.deepEqual(result, { released: 2 });
  assert.equal(chrome.debugger.attached.size, 0);
  assert.deepEqual(events.at(-1), ['tabs.summary', { controlled: 0 }]);
});


function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve: () => resolve() };
}

test('revoke and disconnect invalidate an attachment that is still pending', async () => {
  for (const revoke of ['revokeAll', 'releaseAll']) {
    const { control, chrome, setAllowed } = setup();
    const entered = deferred();
    const pending = deferred();
    chrome.debugger.attach = async ({ tabId }) => {
      entered.resolve();
      await pending.promise;
      chrome.debugger.attached.add(tabId);
    };
    const acquired = control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
    await entered.promise;
    if (revoke === 'revokeAll') setAllowed(false);
    await control[revoke]();
    pending.resolve();
    await assert.rejects(acquired, (error) => error.code === 'forbidden');
    assert.equal(control.size, 0);
    assert.equal(chrome.debugger.attached.size, 0);
  }
});

test('cancelling debugger while initialization waits never restores control', async () => {
  const { control, chrome } = setup();
  const entered = deferred();
  const pending = deferred();
  const send = chrome.debugger.sendCommand;
  chrome.debugger.sendCommand = async (...args) => {
    if (args[1] === 'Page.getFrameTree') { entered.resolve(); await pending.promise; }
    return send(...args);
  };
  const acquired = control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
  await entered.promise;
  chrome.debugger.onDetach.emit({ tabId: 1 }, 'canceled_by_user');
  pending.resolve();
  await assert.rejects(acquired, (error) => error.code === 'forbidden');
  assert.equal(control.size, 0);
  assert.equal(chrome.debugger.attached.size, 0);
});

test('failed creation removes the newly opened tab for grouping and attach failures', async () => {
  for (const stage of ['group', 'attach']) {
    const { control, chrome, tabs } = setup();
    const fail = async () => { throw new Error('Cannot acquire'); };
    if (stage === 'group') chrome.tabs.group = fail;
    else chrome.debugger.attach = fail;
    await assert.rejects(control.handle('tabs.open', {}));
    assert.deepEqual([...tabs.keys()], [1]);
    assert.equal(control.size, 0);
    assert.equal(chrome.debugger.attached.size, 0);
  }
});

test('new tab returned after revocation is removed before it can attach', async () => {
  const { control, chrome, tabs } = setup();
  const entered = deferred();
  const pending = deferred();
  const create = chrome.tabs.create;
  chrome.tabs.create = async () => { entered.resolve(); await pending.promise; return create(); };
  const acquired = control.handle('tabs.open', {});
  await entered.promise;
  await control.revokeAll();
  pending.resolve();
  await assert.rejects(acquired, (error) => error.code === 'forbidden');
  assert.deepEqual([...tabs.keys()], [1]);
  assert.equal(control.size, 0);
});

test('handover rechecks approved URL and rolls back only newly acquired debugger', async () => {
  const { control, chrome, tabs } = setup();
  const attach = chrome.debugger.attach;
  chrome.debugger.attach = async (target) => {
    await attach(target);
    tabs.get(1).url = 'https://other.example/';
  };
  await assert.rejects(
    control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' }),
    (error) => error.code === 'forbidden',
  );
  assert.equal(chrome.debugger.attached.size, 0);
  assert.equal(control.size, 0);
  chrome.debugger.attach = attach;
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://other.example/' });
  await assert.rejects(control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://old.example/' }));
  assert.equal(control.size, 1);
  assert.equal(chrome.debugger.attached.size, 1);
});

test('concurrent handovers share finished acquisition without double attaching', async () => {
  const { control, chrome } = setup();
  let attachments = 0;
  const attach = chrome.debugger.attach;
  chrome.debugger.attach = async (target) => { attachments++; await attach(target); };
  await Promise.all([
    control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' }),
    control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' }),
  ]);
  assert.equal(attachments, 1);
  assert.equal(control.size, 1);
});

test('navigation failure and release dispose their completion listeners and timers', async () => {
  const { control, chrome, timers } = setup();
  await control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
  const listeners = chrome.tabs.onUpdated.listeners.length;
  const send = chrome.debugger.sendCommand;
  chrome.debugger.sendCommand = async (...args) => args[1] === 'Page.navigate' ? { errorText: 'blocked' } : send(...args);
  await assert.rejects(control.handle('tabs.navigate', { tabId: '1', url: 'https://example.com/' }));
  assert.equal(chrome.tabs.onUpdated.listeners.length, listeners);
  assert.equal(timers.tasks.size, 0);
  const entered = deferred();
  chrome.debugger.sendCommand = async (...args) => {
    if (args[1] === 'Page.navigate') { entered.resolve(); return {}; }
    return send(...args);
  };
  const navigation = control.handle('tabs.navigate', { tabId: '1', url: 'https://example.com/' });
  await entered.promise;
  await control.revokeAll();
  await assert.rejects(navigation, (error) => error.code === 'no_such_tab');
  assert.equal(chrome.tabs.onUpdated.listeners.length, listeners);
  assert.equal(timers.tasks.size, 0);
});


test('late URL mismatch of an already held tab does not detach its earlier claim', async () => {
  const { control, chrome, tabs } = setup();
  const params = { tabId: '1', expectedUrl: 'https://user.example/' };
  await control.handle('tabs.control', params);
  const get = chrome.tabs.get;
  let reads = 0;
  chrome.tabs.get = async (id) => {
    if (++reads === 2) tabs.get(id).url = 'https://changed.example/';
    return get(id);
  };
  await assert.rejects(control.handle('tabs.control', params), (error) => error.code === 'forbidden');
  assert.equal(control.size, 1);
  assert.equal(chrome.debugger.attached.has(1), true);
});

test('closing a tab during initialization rejects acquisition', async () => {
  const { control, chrome } = setup();
  const entered = deferred();
  const pending = deferred();
  const send = chrome.debugger.sendCommand;
  chrome.debugger.sendCommand = async (...args) => {
    if (args[1] === 'Fetch.enable') { entered.resolve(); await pending.promise; }
    return send(...args);
  };
  const acquisition = control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' });
  await entered.promise;
  chrome.tabs.onRemoved.emit(1);
  pending.resolve();
  await assert.rejects(acquisition, (error) => error.code === 'forbidden');
  assert.equal(control.size, 0);
  assert.equal(chrome.debugger.attached.size, 0);
});


test('handover refuses pending navigation even while the committed URL still matches approval', async () => {
  for (const stage of ['before', 'after']) {
    const { control, chrome, tabs } = setup();
    if (stage === 'before') tabs.get(1).pendingUrl = 'https://changed.example/';
    else {
      const attach = chrome.debugger.attach;
      chrome.debugger.attach = async (target) => {
        await attach(target);
        tabs.get(1).pendingUrl = 'https://changed.example/';
      };
    }
    await assert.rejects(
      control.handle('tabs.control', { tabId: '1', expectedUrl: 'https://user.example/' }),
      (error) => error.code === 'forbidden',
    );
    assert.equal(control.size, 0);
    assert.equal(chrome.debugger.attached.size, 0);
  }
});


test('agent tabs remain discoverable by their owner after the app reconnects', async () => {
  const { control, chrome, tabs } = setup();
  await control.handle('tabs.open', { owner: 'chat-a' });
  await control.handle('tabs.navigate', { tabId: '10', url: 'https://example.com/' });
  await control.releaseAll();
  assert.equal(chrome.debugger.attached.size, 0);
  assert.deepEqual((await control.handle('tabs.owned', { owner: 'chat-a' })).tabs.map(t => t.id), ['10']);
  assert.deepEqual(await control.handle('tabs.owned', { owner: 'chat-b' }), { tabs: [] });
  await control.handle('tabs.recover', { owner: 'chat-a', tabId: '10', expectedUrl: 'https://example.com/' });
  assert.equal(chrome.debugger.attached.has(10), true);
  await control.handle('tabs.release', { tabId: '10' });
  assert.deepEqual(await control.handle('tabs.owned', { owner: 'chat-a' }), { tabs: [] });
  assert.equal(tabs.has(10), true);
});

test('moving an agent tab out of its group or revoking control removes recovery ownership', async () => {
  const { control, chrome, tabs } = setup();
  await control.handle('tabs.open', { owner: 'chat-a' });
  tabs.get(10).groupId = 8;
  assert.deepEqual(await control.handle('tabs.owned', { owner: 'chat-a' }), { tabs: [] });
  await control.handle('tabs.open', { owner: 'chat-a' });
  chrome.debugger.onDetach.emit({ tabId: 11 }, 'canceled_by_user');
  assert.deepEqual(await control.handle('tabs.owned', { owner: 'chat-a' }), { tabs: [] });
  await control.handle('tabs.open', { owner: 'chat-a' });
  await control.releaseAll();
  await control.revokeAll();
  assert.deepEqual(await control.handle('tabs.owned', { owner: 'chat-a' }), { tabs: [] });
});


test('recovery rechecks ownership after listing and cannot reclaim a released tab', async () => {
  const { control } = setup();
  await control.handle('tabs.open', { owner: 'chat-a' });
  await control.releaseAll();
  await assert.rejects(control.handle('tabs.recover', {
    owner: 'chat-b', tabId: '10', expectedUrl: 'about:blank',
  }));
  await control.handle('tabs.recover', { owner: 'chat-a', tabId: '10', expectedUrl: 'about:blank' });
  await control.handle('tabs.release', { tabId: '10' });
  await assert.rejects(control.handle('tabs.recover', {
    owner: 'chat-a', tabId: '10', expectedUrl: 'about:blank',
  }));
});


test('the active page can be read without handing over or retaining input control', async () => {
  const { control, chrome, commands } = setup();
  const original = chrome.debugger.sendCommand;
  chrome.debugger.sendCommand = async (target, method, params) => {
    if (method === 'Runtime.evaluate') return { result: { value: {
      url: 'https://user.example/', title: 'Mail', text: 'Current page', selection: '',
    } } };
    return original(target, method, params);
  };
  const page = await control.handle('tabs.readActive', {});
  assert.equal(page.text, 'Current page');
  assert.equal(page.readOnly, true);
  assert.equal(chrome.debugger.attached.size, 0);
  assert.equal(control.size, 0);
  assert.equal(commands.some(it => it[1].startsWith('Input.')), false);
  assert.equal(commands.some(it => it[1] === 'Fetch.enable'), false);
  await assert.rejects(control.handle('cdp.send', { tabId: '1', method: 'Input.insertText', params: { text: 'x' } }));
});

test('a failed active-page read releases temporary control and never returns a different page', async () => {
  const { control, chrome, tabs } = setup();
  const original = chrome.debugger.sendCommand;
  chrome.debugger.sendCommand = async (target, method, params) => {
    if (method === 'Runtime.evaluate') {
      tabs.get(1).url = 'https://changed.example/';
      return { result: { value: { url: 'https://user.example/', text: 'Stale' } } };
    }
    return original(target, method, params);
  };
  await assert.rejects(control.handle('tabs.readActive', {}));
  assert.equal(chrome.debugger.attached.size, 0);
});


test('concurrent group creation records each tabs actual group rather than the last global group', async () => {
  const { control, chrome, tabs } = setup();
  let releaseFirst;
  const first = new Promise(resolve => { releaseFirst = resolve; });
  chrome.tabs.group = async ({ tabIds }) => {
    const id = tabIds[0];
    tabs.get(id).groupId = id;
    return id;
  };
  chrome.tabGroups.update = async (id) => {
    if (id === 10) await first;
    else releaseFirst();
  };
  await Promise.all([
    control.handle('tabs.open', { owner: 'a' }),
    control.handle('tabs.open', { owner: 'b' }),
  ]);
  assert.deepEqual((await control.handle('tabs.owned', { owner: 'a' })).tabs.map(t => t.id), ['10']);
  assert.deepEqual((await control.handle('tabs.owned', { owner: 'b' })).tabs.map(t => t.id), ['11']);
});
