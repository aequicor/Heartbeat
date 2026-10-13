// @ts-check
import { BridgeError, ErrorCode } from '../shared/protocol.js';
import { isControllableUrl } from './url-policy.js';

/** DevTools protocol version of `chrome.debugger`. */
const PROTOCOL = '1.3';

/** DevTools methods the app's automation driver uses; everything else is refused. */
const ALLOWED_METHODS = new Set([
  'Page.getFrameTree',
  'Page.createIsolatedWorld',
  'Page.captureScreenshot',
  'Runtime.evaluate',
  'Input.dispatchMouseEvent',
  'Input.dispatchKeyEvent',
  'Input.insertText',
]);

/** A guarded document load fails when the app does not answer in time. */
const GUARD_TIMEOUT_MS = 10_000;
const NAVIGATION_TIMEOUT_MS = 30_000;
const GROUP_TITLE = 'Heartbeat';

/**
 * @typedef {{ id: number, mainFrame: string | null, contexts: Set<number>, guards: Map<string, any> }} Controlled
 * @typedef {{ chrome: any, notify: (event: string, params: Record<string, any>) => void,
 *   isAllowed: () => boolean, timers?: { setTimeout: typeof setTimeout, clearTimeout: typeof clearTimeout } }} Options
 */

/**
 * Drives tabs for Heartbeat agents. The debugger is attached only to tabs opened for the agent (kept in the
 * «Heartbeat» group of the current window) or handed over by the user, and only while the user allows agent
 * control. Every document load in a controlled tab waits for the app's navigation guard and fails if it does not
 * answer. Script evaluation is limited to isolated worlds this controller created, so the agent never runs code in
 * the page's own world. The infobar Chrome shows for the debugger is the visible signal of control; cancelling it
 * revokes the tab.
 * @param {Options} options
 */
export function createTabControl({ chrome, notify, isAllowed, timers = globalThis }) {
  /** @type {Map<number, Controlled>} */
  const controlled = new Map();
  /** @type {number | null} */
  let group = null;
  let generation = 0;
  /** @type {Map<number, { generation: number, cancelled: boolean }>} */
  const acquiring = new Map();
  /** @type {Map<number, Promise<unknown>>} */
  const claims = new Map();
  /** @type {Map<number, Set<() => void>>} */
  const navigationWaiters = new Map();

  chrome.debugger.onEvent.addListener((/** @type {any} */ source, /** @type {string} */ method, /** @type {any} */ params) => {
    const tab = controlled.get(source.tabId);
    if (tab && method === 'Fetch.requestPaused') paused(tab, params);
  });
  chrome.debugger.onDetach.addListener((/** @type {any} */ source, /** @type {string} */ reason) => {
    invalidateTab(source.tabId);
    if (!controlled.has(source.tabId)) return;
    forget(source.tabId);
    notify(reason === 'target_closed' ? 'tab.removed' : 'tab.revoked', { tabId: String(source.tabId) });
    summarize();
  });
  chrome.tabs.onRemoved.addListener((/** @type {number} */ tabId) => {
    invalidateTab(tabId);
    if (!controlled.has(tabId)) return;
    forget(tabId);
    notify('tab.removed', { tabId: String(tabId) });
    summarize();
  });
  chrome.tabs.onUpdated.addListener((/** @type {number} */ tabId, /** @type {any} */ change, /** @type {any} */ tab) => {
    if (!controlled.has(tabId) || !(change.url || change.title || change.status)) return;
    notify('tab.changed', { tabId: String(tabId), url: tab.url ?? '', title: tab.title ?? '', loading: tab.status === 'loading' });
  });

  /**
   * @param {Controlled} tab
   * @param {any} params
   */
  function paused(tab, params) {
    const requestId = String(params.requestId);
    const url = String(params.request?.url ?? '');
    if (!isControllableUrl(url)) {
      fail(tab, requestId);
      return;
    }
    const timer = timers.setTimeout(() => fail(tab, requestId), GUARD_TIMEOUT_MS);
    tab.guards.set(requestId, timer);
    notify('guard.paused', { tabId: String(tab.id), requestId, url, mainFrame: params.frameId === tab.mainFrame });
  }

  /**
   * @param {Controlled} tab
   * @param {string} requestId
   */
  function fail(tab, requestId) {
    timers.clearTimeout(tab.guards.get(requestId));
    tab.guards.delete(requestId);
    send(tab.id, 'Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }).catch(() => {});
  }

  /**
   * @param {number} tabId
   * @param {string} method
   * @param {Record<string, any>} [params]
   * @returns {Promise<any>}
   */
  function send(tabId, method, params = {}) {
    return chrome.debugger.sendCommand({ tabId }, method, params);
  }

  /** @param {number} expected */
  function checkGeneration(expected) {
    if (expected !== generation || !isAllowed()) {
      throw new BridgeError(ErrorCode.FORBIDDEN, 'Tab acquisition was revoked');
    }
  }

  /** Serialize acquisition and its rollback so a failed claimant cannot detach another one.
   * @param {number} tabId @param {() => Promise<any>} action */
  async function claim(tabId, action) {
    const previous = claims.get(tabId) ?? Promise.resolve();
    const current = previous.catch(() => {}).then(action);
    claims.set(tabId, current);
    try {
      return await current;
    } finally {
      if (claims.get(tabId) === current) claims.delete(tabId);
    }
  }

  /** @param {number} tabId @param {number} expected */
  async function attach(tabId, expected) {
    checkGeneration(expected);
    if (controlled.has(tabId)) return;
    const attempt = { generation: expected, cancelled: false };
    acquiring.set(tabId, attempt);
    /** @type {Controlled} */
    const tab = { id: tabId, mainFrame: null, contexts: new Set(), guards: new Map() };
    let attached = false;
    function check() {
      checkGeneration(attempt.generation);
      if (attempt.cancelled) throw new BridgeError(ErrorCode.FORBIDDEN, 'Tab acquisition was revoked');
    }
    try {
      await chrome.debugger.attach({ tabId }, PROTOCOL);
      attached = true;
      check();
      controlled.set(tabId, tab);
      const tree = await send(tabId, 'Page.getFrameTree');
      check();
      tab.mainFrame = tree?.frameTree?.frame?.id ?? null;
      await send(tabId, 'Fetch.enable', { patterns: [{ resourceType: 'Document', requestStage: 'Request' }] });
      check();
      summarize();
    } catch (error) {
      if (controlled.get(tabId) === tab) forget(tabId);
      if (attached) await chrome.debugger.detach({ tabId }).catch(() => {});
      summarize();
      throw error;
    } finally {
      if (acquiring.get(tabId) === attempt) acquiring.delete(tabId);
    }
  }

  /** Invalidates pending work even when attachment has not returned yet. @param {number} tabId */
  function invalidateTab(tabId) {
    const attempt = acquiring.get(tabId);
    if (attempt) attempt.cancelled = true;
    for (const cancel of navigationWaiters.get(tabId) ?? []) cancel();
  }

  /** @param {number} tabId */
  async function detach(tabId) {
    invalidateTab(tabId);
    if (!controlled.has(tabId)) return;
    forget(tabId);
    await chrome.debugger.detach({ tabId }).catch(() => {});
  }

  /** @param {number} tabId */
  function forget(tabId) {
    const tab = controlled.get(tabId);
    if (!tab) return;
    for (const timer of tab.guards.values()) timers.clearTimeout(timer);
    controlled.delete(tabId);
  }

  function summarize() {
    notify('tabs.summary', { controlled: controlled.size });
  }

  /** @param {number} tabId @param {string} [expectedUrl] */
  async function describe(tabId, expectedUrl) {
    const tab = await chrome.tabs.get(tabId);
    if (expectedUrl !== undefined) requireApprovedPage(tab, expectedUrl);
    return { id: String(tab.id), url: tab.url ?? '', title: tab.title ?? '' };
  }

  /**
   * Waits until the tab finished loading after a navigation started.
   * @param {number} tabId
   */
  function loaded(tabId) {
    /** @type {(completed: boolean) => void} */
    let finish;
    const promise = new Promise((resolve) => { finish = resolve; });
    let settled = false;
    const waiters = navigationWaiters.get(tabId) ?? new Set();
    navigationWaiters.set(tabId, waiters);
    const cancel = () => done(false);
    waiters.add(cancel);
    const timer = timers.setTimeout(() => done(true), NAVIGATION_TIMEOUT_MS);
    /** @param {number} id @param {any} change */
    function listener(id, change) {
      if (id === tabId && change.status === 'complete') done(true);
    }
    /** @param {boolean} completed */
    function done(completed) {
      if (settled) return;
      settled = true;
      timers.clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(listener);
      waiters.delete(cancel);
      if (waiters.size === 0) navigationWaiters.delete(tabId);
      finish(completed);
    }
    chrome.tabs.onUpdated.addListener(listener);
    return { promise, cancel };
  }

  /** @param {number} tabId @param {string} url */
  async function navigate(tabId, url) {
    requireUrl(url);
    const expected = generation;
    const done = loaded(tabId);
    try {
      const result = await send(tabId, 'Page.navigate', { url });
      if (result?.errorText) throw new BridgeError(ErrorCode.FORBIDDEN, 'Navigation was blocked');
      if (!(await done.promise)) throw new BridgeError(ErrorCode.NO_SUCH_TAB, 'Tab was released');
      checkGeneration(expected);
      return await describe(tabId);
    } finally {
      done.cancel();
    }
  }

  /** @param {number} tabId */
  async function joinGroup(tabId) {
    try {
      group = await chrome.tabs.group({ tabIds: [tabId], ...(group === null ? {} : { groupId: group }) });
      await chrome.tabGroups.update(group, { title: GROUP_TITLE, color: 'pink' });
    } catch (error) {
      // The group was closed by the user meanwhile: start a new one.
      group = await chrome.tabs.group({ tabIds: [tabId] });
      await chrome.tabGroups.update(group, { title: GROUP_TITLE, color: 'pink' });
    }
  }

  /** @param {Record<string, any>} params */
  function tabIdOf(params) {
    const tabId = Number(params.tabId);
    if (!Number.isInteger(tabId) || !controlled.has(tabId)) throw new BridgeError(ErrorCode.NO_SUCH_TAB, 'Tab is not controlled');
    return tabId;
  }

  /**
   * Answers one call of the app.
   * @param {string} method
   * @param {Record<string, any>} params
   */
  async function handle(method, params) {
    if (!isAllowed()) throw new BridgeError(ErrorCode.FORBIDDEN, 'Agent control is off in this browser');
    const expected = generation;
    switch (method) {
      case 'tabs.active': {
        const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
        return { tab: tab && isControllableUrl(tab.url) ? { id: String(tab.id), url: tab.url, title: tab.title ?? '' } : null };
      }
      case 'tabs.open': {
        // Blank first: the app learns the tab before its first page load passes the guard.
        const created = await chrome.tabs.create({ url: 'about:blank', active: false });
        return claim(created.id, async () => {
          try {
            checkGeneration(expected);
            await joinGroup(created.id);
            checkGeneration(expected);
            await attach(created.id, expected);
            checkGeneration(expected);
            return { tab: { id: String(created.id), url: '', title: '' } };
          } catch (error) {
            await detach(created.id);
            await chrome.tabs.remove(created.id).catch(() => {});
            summarize();
            throw error;
          }
        });
      }
      case 'tabs.control': {
        const tabId = Number(params.tabId);
        return claim(tabId, async () => {
          checkGeneration(expected);
          const tab = await chrome.tabs.get(tabId);
          requireApprovedPage(tab, params.expectedUrl);
          const alreadyControlled = controlled.has(tabId);
          try {
            await attach(tabId, expected);
            const result = await describe(tabId, params.expectedUrl);
            checkGeneration(expected);
            if (!controlled.has(tabId) || result.url !== params.expectedUrl) {
              throw new BridgeError(ErrorCode.FORBIDDEN, 'The approved page changed');
            }
            return { tab: result };
          } catch (error) {
            if (!alreadyControlled) await detach(tabId);
            summarize();
            throw error;
          }
        });
      }
      case 'tabs.navigate':
        return { tab: await navigate(tabIdOf(params), String(params.url)) };
      case 'tabs.activate':
        await chrome.tabs.update(tabIdOf(params), { active: true });
        return {};
      case 'tabs.release':
        await detach(tabIdOf(params));
        summarize();
        return {};
      case 'tabs.close': {
        const tabId = tabIdOf(params);
        await detach(tabId);
        await chrome.tabs.remove(tabId);
        summarize();
        return {};
      }
      case 'tabs.revokeAll':
        return { released: await revokeAll() };
      case 'cdp.send':
        return { result: await devTools(tabIdOf(params), String(params.method), params.params ?? {}) };
      case 'guard.resolve': {
        const tab = /** @type {Controlled} */ (controlled.get(tabIdOf(params)));
        const requestId = String(params.requestId);
        if (!tab.guards.has(requestId)) throw new BridgeError(ErrorCode.STALE_REQUEST, 'The load is no longer waiting');
        if (params.allow !== true) {
          fail(tab, requestId);
        } else {
          timers.clearTimeout(tab.guards.get(requestId));
          tab.guards.delete(requestId);
          await send(tab.id, 'Fetch.continueRequest', { requestId });
        }
        return {};
      }
      default:
        throw new BridgeError(ErrorCode.UNKNOWN_METHOD, 'Unknown method');
    }
  }

  /**
   * @param {number} tabId
   * @param {string} method
   * @param {Record<string, any>} params
   */
  async function devTools(tabId, method, params) {
    if (!ALLOWED_METHODS.has(method)) throw new BridgeError(ErrorCode.FORBIDDEN, 'Method is not allowed');
    const tab = /** @type {Controlled} */ (controlled.get(tabId));
    if (method === 'Runtime.evaluate' && !tab.contexts.has(Number(params.contextId))) {
      throw new BridgeError(ErrorCode.FORBIDDEN, 'Evaluation outside the agent world is not allowed');
    }
    const result = await send(tabId, method, params);
    if (method === 'Page.createIsolatedWorld' && Number.isInteger(result?.executionContextId)) {
      tab.contexts.add(result.executionContextId);
    }
    return result ?? {};
  }

  /** Releases every controlled tab, telling the app each one was revoked. */
  async function revokeAll() {
    generation++;
    const tabs = [...controlled.keys()];
    for (const tabId of tabs) {
      await detach(tabId);
      notify('tab.revoked', { tabId: String(tabId) });
    }
    if (tabs.length) summarize();
    return tabs.length;
  }

  return {
    handle,
    revokeAll,

    /** Lets every tab go without telling the app, which is gone anyway. */
    async releaseAll() {
      generation++;
      for (const tabId of [...controlled.keys()]) await detach(tabId);
    },

    /** Number of controlled tabs. */
    get size() {
      return controlled.size;
    },
  };
}

/** @param {unknown} url */
function requireUrl(url) {
  if (typeof url !== 'string' || !isControllableUrl(url)) {
    throw new BridgeError(ErrorCode.FORBIDDEN, 'Only web pages can be opened');
  }
}


/** A navigation issued before debugger attachment is not covered by its new Fetch guard.
 * @param {any} tab @param {unknown} expectedUrl */
function requireApprovedPage(tab, expectedUrl) {
  if (!isControllableUrl(tab.url) || tab.url !== expectedUrl || tab.pendingUrl) {
    throw new BridgeError(ErrorCode.FORBIDDEN, 'The approved page changed');
  }
}
