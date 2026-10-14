// @ts-check
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mock, test } from 'node:test';
import { createDiagramOpener, DiagramUnavailableError, receiveDiagram, validateDiagram } from '../src/sidepanel/diagram-transfer.js';
import { showDiagram } from '../src/sidepanel/diagram-viewer.js';
import { createPanelHost } from '../src/sidepanel/panel-host.js';
import { FakeEvent, FakePort, FakeTimers, flush } from './fakes/chrome.js';
import { fakeLocks } from './fakes/locks.js';

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7WQAAAAASUVORK5CYII=';
const DIAGRAM = { png: PNG, background: '#fFfFfF', title: '<svg onload=alert(1)>' };
const ORIGIN = 'chrome-extension://test';

/**
 * A runtime Port pair; Chrome delivers messages after listener registration and disconnects only the peer.
 * @param {string} name @param {Record<string, any>} sender
 */
function portPair(name, sender) {
  const panel = { name, sender, onMessage: new FakeEvent(), onDisconnect: new FakeEvent(),
    disconnected: false, postMessage: (/** @type {any} */ message) => send(viewer, message), disconnect: () => close(panel, viewer) };
  const viewer = { name, onMessage: new FakeEvent(), onDisconnect: new FakeEvent(),
    disconnected: false, postMessage: (/** @type {any} */ message) => send(panel, message), disconnect: () => close(viewer, panel) };
  /** @param {any} port @param {any} message */
  function send(port, message) {
    if (panel.disconnected || viewer.disconnected) throw new Error('Disconnected port');
    const copy = structuredClone(message);
    queueMicrotask(() => { if (!port.disconnected) port.onMessage.emit(copy); });
  }
  /** @param {any} source @param {any} target */
  function close(source, target) {
    if (source.disconnected) return;
    source.disconnected = true;
    target.disconnected = true;
    queueMicrotask(() => target.onDisconnect.emit());
  }
  return { panel, viewer };
}

function setup() {
  /** @type {any[]} */
  const tabs = [];
  /** @type {number[]} */
  const removed = [];
  /** @type {ReturnType<typeof portPair>[]} */
  const ports = [];
  const timers = new FakeTimers();
  const lifecycle = new EventTarget();
  const onConnect = new FakeEvent();
  const chrome = {
    runtime: {
      id: 'test', onConnect,
      connect: () => new FakePort('heartbeat-panel'),
      getURL: (/** @type {string} */ path) => `${ORIGIN}/${path}`,
    },
    windows: { getCurrent: async () => ({ id: 42 }) },
    tabs: { create: async (/** @type {any} */ tab) => {
      const created = { id: tabs.length + 1, ...tab };
      tabs.push(created);
      return created;
    },
    get: async (/** @type {number} */ id) => {
      const tab = tabs.find((entry) => entry.id === id);
      if (!tab) throw new Error('Tab not found');
      return { ...tab };
    },
    remove: async (/** @type {number} */ id) => { removed.push(id); } },
    i18n: { getUILanguage: () => 'ru', getMessage: (/** @type {string} */ key) => key },
  };
  const open = createDiagramOpener(chrome, { timers, lifecycle });
  /** @param {any} [tab] @param {Record<string, any>} [sender] */
  function viewerChrome(tab = tabs.at(-1), sender = {}) {
    return { ...chrome, runtime: { ...chrome.runtime, connect: (/** @type {{ name: string }} */ { name }) => {
      const pair = portPair(name, { id: 'test', url: tab.url, origin: ORIGIN, frameId: 0, tab: { id: tab.id }, ...sender });
      ports.push(pair);
      queueMicrotask(() => onConnect.emit(pair.panel));
      return pair.viewer;
    } } };
  }
  return { chrome, tabs, removed, ports, timers, lifecycle, open, viewerChrome };
}

/** Minimal DOM: content is only assigned as text, images decode explicitly, and events are driven by tests. */
function viewer() {
  /** @type {Record<string, any>} */
  const elements = {};
  for (const id of ['diagram', 'viewport', 'status', 'controls', 'title', 'scale', 'zoom-in', 'zoom-out', 'actual', 'fit']) {
    const events = new Map();
    elements[id] = {
      style: {}, hidden: ['viewport', 'controls'].includes(id), textContent: '',
      naturalWidth: 1600, naturalHeight: 1200, clientWidth: 800, clientHeight: 800,
      decode: async () => {},
      /** @param {string} name @param {string} value */
      setAttribute(name, value) { this[name] = value; },
      /** @param {string} name */
      removeAttribute(name) { delete this[name]; },
      /** @param {string} name @param {Function} callback */
      addEventListener(name, callback) { events.set(name, callback); },
      /** @param {string} name */
      emit(name) { events.get(name)?.(); },
    };
  }
  const events = new Map();
  const document = /** @type {any} */ ({
    documentElement: {}, title: '', getElementById: (/** @type {string} */ id) => elements[id],
  });
  const window = /** @type {any} */ ({
    location: { hash: '' },
    addEventListener: (/** @type {string} */ name, /** @type {Function} */ callback) => events.set(name, callback),
  });
  /** @type {Blob[]} */
  const blobs = [];
  /** @type {string[]} */
  const revoked = [];
  const urls = {
    createObjectURL: (/** @type {Blob | MediaSource} */ blob) => { blobs.push(/** @type {Blob} */ (blob)); return 'blob:test'; },
    revokeObjectURL: (/** @type {string} */ url) => { revoked.push(url); },
  };
  return { elements, document, window, blobs, revoked, urls, emit: (/** @type {string} */ name) => events.get(name)?.() };
}

/** @param {{ url: string }} tab */
const tokenOf = (tab) => new URL(tab.url).hash.slice(1);

/**
 * Starts both ends; browser navigation itself stays under test control.
 * @param {ReturnType<typeof setup>} state @param {ReturnType<typeof viewer>} ui
 */
async function start(state, ui) {
  const opening = state.open(42, JSON.stringify(DIAGRAM));
  await flush();
  ui.window.location.hash = '#' + tokenOf(state.tabs.at(-1));
  const shown = showDiagram(state.viewerChrome(), ui.document, ui.window, ui.urls, state.timers);
  return { opening, shown };
}

test('panel opens an active viewer in its window and resolves only after the decode acknowledgement', async () => {
  const state = setup();
  const host = createPanelHost(state.chrome, fakeLocks());
  let opened = false;
  const opening = host.openDiagram(JSON.stringify(DIAGRAM)).then(() => { opened = true; });
  await flush();
  assert.equal(state.tabs[0].active, true);
  assert.equal(state.tabs[0].windowId, 42);
  assert.match(state.tabs[0].url, /^chrome-extension:\/\/test\/src\/sidepanel\/diagram.html#[a-f0-9-]+$/);
  let decoded = () => {};
  const decoding = new Promise((resolve) => { decoded = () => resolve(undefined); });
  const showing = receiveDiagram(state.viewerChrome(), tokenOf(state.tabs[0]), async (diagram) => {
    assert.deepEqual(diagram, DIAGRAM);
    await decoding;
  }, { timers: state.timers });
  await flush();
  assert.equal(opened, false);
  decoded();
  await Promise.all([opening, showing]);
  assert.equal(opened, true);
  assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
  assert.equal(state.ports[0].panel.disconnected, true);
  assert.deepEqual(state.removed, []);
});

test('full renderer budget of 10 MiB reaches the viewer without storage and one extra byte is refused', async () => {
  const state = setup();
  const bytes = Buffer.alloc(10 * 1024 * 1024);
  Buffer.from(PNG, 'base64').copy(bytes);
  const diagram = { ...DIAGRAM, png: bytes.toString('base64') };
  const opening = state.open(42, JSON.stringify(diagram));
  await flush();
  const showing = receiveDiagram(state.viewerChrome(), tokenOf(state.tabs[0]), async (value) => {
    assert.equal(value.png.length, diagram.png.length);
  }, { timers: state.timers });
  await Promise.all([opening, showing]);
  const oversized = { ...DIAGRAM, png: Buffer.concat([bytes, Buffer.from([0])]).toString('base64') };
  await assert.rejects(state.open(42, JSON.stringify(oversized)), /Oversized/);
  assert.equal(state.tabs.length, 1);
  assert.equal(state.timers.tasks.size, 0);
});

test('pending opens are bounded and a timeout frees capacity and listeners', async () => {
  const state = setup();
  const one = state.open(42, JSON.stringify(DIAGRAM));
  const two = state.open(42, JSON.stringify(DIAGRAM));
  const failures = Promise.all([assert.rejects(one, /timed out/), assert.rejects(two, /timed out/)]);
  await assert.rejects(state.open(42, JSON.stringify(DIAGRAM)), /Too many diagrams/);
  await flush();
  state.timers.advance(15_000);
  await failures;
  assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
  assert.equal(state.timers.tasks.size, 0);
  assert.deepEqual(state.removed, [1, 2]);
  const retry = state.open(42, JSON.stringify(DIAGRAM));
  const failedRetry = assert.rejects(retry, /panel closed/);
  state.lifecycle.dispatchEvent(new Event('pagehide'));
  await failedRetry;
});

test('sender identity, origin, page path, frame and created tab are verified before sending any data', async () => {
  const state = setup();
  const opening = state.open(42, JSON.stringify(DIAGRAM));
  await flush();
  const tab = state.tabs[0];
  for (const sender of [
    { id: 'other' }, { url: 'https://evil.example/' }, { origin: 'https://evil.example' },
    { url: tab.url.replace('diagram.html', 'panel.html') }, { frameId: 1 }, { tab: { id: tab.id + 1 } },
  ]) {
    let received = false;
    const port = state.viewerChrome(tab, sender).runtime.connect({ name: 'heartbeat-diagram:' + tokenOf(tab) });
    port.onMessage.addListener(() => { received = true; });
    await flush();
    assert.equal(received, false);
    assert.equal(port.disconnected, true);
  }
  // MessageSender.url need not expose a fragment, and its origin field is optional in the Chrome API.
  const realPage = state.viewerChrome(tab, { url: tab.url.split('#')[0], origin: undefined });
  await Promise.all([opening, receiveDiagram(realPage, tokenOf(tab), async () => {}, { timers: state.timers })]);
});

test('a viewer connecting before tabs.create resolves is delivered only after its tab identity is known', async () => {
  const state = setup();
  /** @type {(value: any) => void} */
  let created = () => {};
  state.chrome.tabs.create = (tab) => {
    state.tabs.push({ ...tab, id: 7 });
    return new Promise((resolve) => { created = resolve; });
  };
  const opening = state.open(42, JSON.stringify(DIAGRAM));
  await flush();
  let received = false;
  const showing = receiveDiagram(state.viewerChrome(), tokenOf(state.tabs[0]), async () => { received = true; },
    { timers: state.timers });
  await flush();
  assert.equal(received, false);
  created({ id: 7 });
  await Promise.all([opening, showing]);
  assert.equal(received, true);
});

test('tab failures and panel teardown reject the open and release all pending resources', async () => {
  const state = setup();
  state.chrome.tabs.create = async () => { throw new Error('Tab rejected'); };
  await assert.rejects(state.open(42, JSON.stringify(DIAGRAM)), /Tab rejected/);
  assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
  assert.equal(state.timers.tasks.size, 0);

  const next = setup();
  const opening = next.open(42, JSON.stringify(DIAGRAM));
  const rejected = assert.rejects(opening, /panel closed/);
  await flush();
  next.lifecycle.dispatchEvent(new Event('pagehide'));
  await rejected;
  assert.equal(next.chrome.runtime.onConnect.listeners.length, 0);
  assert.equal(next.timers.tasks.size, 0);
  assert.deepEqual(next.removed, [1]);
});

test('a tab returned after transfer timeout is closed without touching other tabs', async () => {
  const state = setup();
  /** @type {(value: any) => void} */
  let created = () => {};
  state.chrome.tabs.create = (tab) => {
    state.tabs.push({ ...tab, id: 81 });
    return new Promise((resolve) => { created = resolve; });
  };
  const opening = state.open(42, JSON.stringify(DIAGRAM));
  const rejected = assert.rejects(opening, /timed out/);
  await flush();
  state.timers.advance(15_000);
  await rejected;
  assert.deepEqual(state.removed, []);
  created({ id: 81 });
  await flush();
  assert.deepEqual(state.removed, [81]);
});

test('rollback failure is logged without replacing the original transfer error', async () => {
  const state = setup();
  const removeError = new Error('Cannot remove tab');
  state.chrome.tabs.remove = async () => { throw removeError; };
  const warning = mock.method(console, 'warn', () => {});
  try {
    const opening = state.open(42, JSON.stringify(DIAGRAM));
    const rejected = assert.rejects(opening, /timed out/);
    await flush();
    state.timers.advance(15_000);
    await rejected;
    assert.equal(warning.mock.callCount(), 1);
    assert.equal(warning.mock.calls[0].arguments[1], removeError);
  } finally {
    warning.mock.restore();
  }
});

test('navigation during pending decode preserves the tab for both committed and pending destinations', async () => {
  for (const navigation of [{ url: 'https://example.com/' }, { pendingUrl: 'https://example.com/' }]) {
    const state = setup();
    const opening = state.open(42, JSON.stringify(DIAGRAM));
    const rejected = assert.rejects(opening, DiagramUnavailableError);
    await flush();
    let decoded = () => {};
    const decoding = new Promise((resolve) => { decoded = () => resolve(undefined); });
    const receiving = receiveDiagram(state.viewerChrome(), tokenOf(state.tabs[0]), async () => decoding,
      { timers: state.timers });
    const failedReceive = assert.rejects(receiving, DiagramUnavailableError);
    await flush();
    Object.assign(state.tabs[0], navigation);
    state.ports[0].viewer.disconnect();
    await flush();
    state.timers.advance(15_000);
    await Promise.all([rejected, failedReceive]);
    decoded();
    assert.deepEqual(state.removed, []);
    assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
  }
});

test('late creation rollback preserves a tab whose navigation changed before the create reply', async () => {
  const state = setup();
  /** @type {(value: any) => void} */
  let created = () => {};
  state.chrome.tabs.create = (tab) => {
    state.tabs.push({ ...tab, id: 81, pendingUrl: 'https://example.com/' });
    return new Promise((resolve) => { created = resolve; });
  };
  const opening = state.open(42, JSON.stringify(DIAGRAM));
  const rejected = assert.rejects(opening, /timed out/);
  await flush();
  state.timers.advance(15_000);
  await rejected;
  created({ id: 81 });
  await flush();
  assert.deepEqual(state.removed, []);
});

test('invalid tokens and unavailable transfers fail with a bounded lifetime', async () => {
  const state = setup();
  await assert.rejects(receiveDiagram(state.chrome, '../other', async () => {}, { timers: state.timers }), DiagramUnavailableError);
  const tab = { id: 10, url: `${ORIGIN}/src/sidepanel/diagram.html#aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` };
  const receiving = receiveDiagram(state.viewerChrome(tab), tokenOf(tab), async () => {}, { timers: state.timers });
  const failed = assert.rejects(receiving, DiagramUnavailableError);
  await flush();
  state.timers.advance(15_000);
  await failed;
  assert.equal(state.ports[0].viewer.disconnected, true);
  assert.equal(state.timers.tasks.size, 0);
});

test('invalid raster, background, dimensions and oversized payloads never open a tab', async () => {
  const state = setup();
  const hugeDimensions = Buffer.from(PNG, 'base64');
  hugeDimensions.writeUInt32BE(4097, 16);
  for (const value of [
    null, { ...DIAGRAM, png: 'data:image/png;base64,' + PNG }, { ...DIAGRAM, png: 'A'.repeat(44) },
    { ...DIAGRAM, background: 'url(https://example.com)' }, { ...DIAGRAM, background: '#ffffffff' },
    { ...DIAGRAM, png: hugeDimensions.toString('base64') }, { ...DIAGRAM, title: 'x'.repeat(257) },
    { ...DIAGRAM, png: 'A'.repeat(15 * 1024 * 1024) },
  ]) await assert.rejects(state.open(42, JSON.stringify(value)));
  assert.equal(state.tabs.length, 0);
  assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
  assert.deepEqual(validateDiagram({ png: PNG, background: '#ffffff' }), { png: PNG, background: '#ffffff', title: '' });
});

test('viewer displays a local PNG, fits and zooms, survives panel closure and releases its URL on close', async () => {
  const state = setup();
  const ui = viewer();
  const { opening, shown } = await start(state, ui);
  await Promise.all([opening, shown]);
  state.lifecycle.dispatchEvent(new Event('pagehide'));
  assert.deepEqual(state.removed, []);
  assert.equal(ui.document.documentElement.lang, 'ru');
  assert.equal(ui.elements.title.textContent, DIAGRAM.title);
  assert.equal(ui.elements.diagram.src, 'blob:test');
  assert.equal(ui.elements.viewport.style.backgroundColor, DIAGRAM.background);
  assert.equal(ui.elements.diagram.style.width, '800px');
  assert.equal(ui.elements.scale.textContent, '50%');
  assert.equal(ui.elements.status.hidden, true);
  assert.equal(ui.elements.controls.hidden, false);
  assert.equal(ui.blobs[0].type, 'image/png');
  assert.deepEqual(Buffer.from(await ui.blobs[0].arrayBuffer()), Buffer.from(PNG, 'base64'));
  ui.elements.actual.emit('click');
  assert.equal(ui.elements.scale.textContent, '100%');
  ui.elements['zoom-in'].emit('click');
  assert.equal(ui.elements.scale.textContent, '125%');
  ui.elements['zoom-out'].emit('click');
  assert.equal(ui.elements.scale.textContent, '100%');
  ui.elements.fit.emit('click');
  ui.elements.viewport.clientWidth = 400;
  ui.emit('resize');
  assert.equal(ui.elements.scale.textContent, '25%');
  ui.emit('pagehide');
  assert.deepEqual(ui.revoked, ['blob:test']);
  assert.equal(ui.elements.diagram.src, undefined);
});

test('decoding failure rejects panel open for fallback and releases viewer data', async () => {
  const warning = mock.method(console, 'warn', () => {});
  try {
    const state = setup();
    const ui = viewer();
    ui.elements.diagram.decode = async () => { throw new Error('Broken PNG'); };
    const { opening, shown } = await start(state, ui);
    await Promise.all([assert.rejects(opening, DiagramUnavailableError), shown]);
    assert.equal(ui.elements.status.textContent, 'diagramFailed');
    assert.equal(ui.elements.controls.hidden, true);
    assert.deepEqual(ui.revoked, ['blob:test']);
    assert.equal(state.chrome.runtime.onConnect.listeners.length, 0);
    assert.equal(state.timers.tasks.size, 0);
    assert.deepEqual(state.removed, [1]);
  } finally {
    warning.mock.restore();
  }
});

test('closing a viewer during decode rejects the transfer and cannot revive the disposed image', async () => {
  const state = setup();
  const ui = viewer();
  ui.elements.diagram.decode = async () => { ui.emit('pagehide'); };
  const { opening, shown } = await start(state, ui);
  await Promise.all([assert.rejects(opening, DiagramUnavailableError), shown]);
  assert.deepEqual(ui.revoked, ['blob:test']);
  assert.equal(ui.elements.controls.hidden, true);
  assert.equal(ui.elements.viewport.hidden, true);
  assert.equal(state.timers.tasks.size, 0);
  assert.deepEqual(state.removed, [1]);
});

test('viewer is bundled with a network-free CSP and matching locales', () => {
  const html = readFileSync(new URL('../src/sidepanel/diagram.html', import.meta.url), 'utf8');
  assert.ok(html.includes("default-src 'none'"));
  assert.ok(html.includes('img-src blob:'));
  assert.ok(html.includes("connect-src 'none'"));
  assert.ok(html.includes('src="diagram-boot.js"'));
  for (const locale of ['ru', 'en']) {
    const messages = JSON.parse(readFileSync(new URL(`../_locales/${locale}/messages.json`, import.meta.url), 'utf8'));
    for (const key of ['diagramTitle', 'diagramLoading', 'diagramZoomIn', 'diagramZoomOut', 'diagramActualSize',
      'diagramFit', 'diagramUnavailable', 'diagramFailed']) assert.ok(messages[key].message, `${locale}: ${key}`);
  }
});
