// @ts-check
import { BridgeError, ErrorCode, toWireError } from '../shared/protocol.js';

/** Port name side panels connect with. */
export const PANEL_PORT = 'heartbeat-panel';

/** App methods a panel may call directly; watches go through `watch` / `unwatch` messages. */
const PANEL_METHODS = new Set(['chat.send', 'chat.stop', 'permission.respond']);

/** Methods that open a watch and the events that carry its updates. */
const WATCH_METHODS = new Set(['chats.watch', 'chat.watch']);

/** Bounds retained events while watch replies are in flight; overflow fails the watch instead of losing history. */
const MAX_EARLY_EVENTS = 64;
const MAX_EARLY_CHARS = 64 * 1024 * 1024;

/**
 * @typedef {{ method: string, params: Record<string, any>, appWatch: string | null, generation: number, opening: boolean }} Watch
 * @typedef {{ port: any, watches: Map<string, Watch> }} Panel
 * @typedef {{ status: string, hello: Record<string, any> | null,
 *   request: (method: string, params?: Record<string, any>) => Promise<any> }} Link
 * @typedef {{ state: () => { isAllowed: boolean, controlled: number },
 *   setAllowed: (isAllowed: boolean) => unknown, revokeAll: () => unknown }} Agent
 */

/** @type {Agent} */
const NO_AGENT = { state: () => ({ isAllowed: false, controlled: 0 }), setAllowed: () => {}, revokeAll: () => {} };

/**
 * Connects side panels to the app. Each panel holds named watches (`chats`, `chat`); the hub opens them in the
 * app, routes their events to the owning panel and opens them again after every reconnect, so a panel only ever
 * sees a fresh `reset`. Watches of a closed panel are cancelled in the app.
 * The hub also carries the agent-control consent and the number of controlled tabs to every panel.
 * @param {{ link: Link, extensionOrigin: string, agent?: Agent }} options
 */
export function createPanelHub({ link, extensionOrigin, agent = NO_AGENT }) {
  /** @type {Set<Panel>} */
  const panels = new Set();
  /** @type {Map<string, { panel: Panel, key: string }>} */
  const byAppWatch = new Map();
  let connection = earlyEvents();

  /** Each connection owns its pending replies and early events; old replies cannot affect a new connection. */
  function earlyEvents() {
    return { pending: 0, chars: 0, overflow: false,
      events: /** @type {{ event: string, params: Record<string, any> }[]} */ ([]) };
  }


  /**
   * Accepts a panel port opened by this extension's side panel page.
   * @param {any} port
   */
  function attach(port) {
    const sender = port.sender ?? {};
    if (port.name !== PANEL_PORT || !String(sender.url ?? '').startsWith(`${extensionOrigin}src/sidepanel/`)) {
      port.disconnect();
      return;
    }
    /** @type {Panel} */
    const panel = { port, watches: new Map() };
    panels.add(panel);
    port.onMessage.addListener((/** @type {any} */ message) => receive(panel, message));
    port.onDisconnect.addListener(() => detach(panel));
    post(panel, { type: 'status', status: link.status, features: link.hello?.features ?? null });
    post(panel, { type: 'agent', ...agent.state() });
  }

  /**
   * @param {Panel} panel
   * @param {any} message
   */
  function receive(panel, message) {
    if (!message || typeof message !== 'object') return;
    switch (message.type) {
      case 'watch':
        watch(panel, String(message.key), String(message.method), message.params ?? {});
        break;
      case 'unwatch':
        unwatch(panel, String(message.key));
        break;
      case 'call':
        call(panel, message.id, String(message.method), message.params ?? {});
        break;
      case 'consent':
        agent.setAllowed(message.isAllowed === true);
        break;
      case 'revokeTabs':
        agent.revokeAll();
        break;
      default:
        break;
    }
  }

  /**
   * @param {Panel} panel
   * @param {string} key
   * @param {string} method
   * @param {Record<string, any>} params
   */
  function watch(panel, key, method, params) {
    if (!WATCH_METHODS.has(method)) return;
    unwatch(panel, key);
    const entry = { method, params, appWatch: null, generation: 0, opening: false };
    panel.watches.set(key, entry);
    if (link.status === 'ready') open(panel, key, entry);
  }

  /**
   * @param {Panel} panel
   * @param {string} key
   * @param {Watch} entry
   */
  function open(panel, key, entry) {
    if (entry.opening) return;
    entry.opening = true;
    const generation = ++entry.generation;
    const epoch = connection;
    epoch.pending++;
    const isCurrent = () => connection === epoch && panel.watches.get(key) === entry &&
      entry.generation === generation && panels.has(panel);
    link.request(entry.method, entry.params).then(
      (result) => {
        const appWatch = String(result?.watch ?? '');
        if (!isCurrent()) {
          // A watch id from an old connection may identify a new watch after app restart.
          if (connection === epoch) cancelInApp(appWatch);
          return;
        }
        if (epoch.overflow) {
          cancelInApp(appWatch);
          post(panel, { type: 'watchFailed', key, error: { code: ErrorCode.BUSY, message: 'Watch buffer full' } });
          return;
        }
        entry.appWatch = appWatch;
        byAppWatch.set(appWatch, { panel, key });
        for (const message of epoch.events) {
          if (message.params.watch === appWatch) post(panel, { type: 'event', key, ...message });
        }
      },
      (error) => {
        if (isCurrent()) post(panel, { type: 'watchFailed', key, error: toWireError(error) });
      },
    ).finally(() => {
      if (isCurrent()) entry.opening = false;
      if (--epoch.pending === 0) {
        epoch.events = [];
        epoch.chars = 0;
        epoch.overflow = false;
      }
    });
  }

  /**
   * @param {Panel} panel
   * @param {string} key
   */
  function unwatch(panel, key) {
    const entry = panel.watches.get(key);
    if (!entry) return;
    panel.watches.delete(key);
    entry.generation++;
    if (entry.appWatch) {
      byAppWatch.delete(entry.appWatch);
      cancelInApp(entry.appWatch);
    }
  }

  /** @param {string} appWatch */
  function cancelInApp(appWatch) {
    if (!appWatch || link.status !== 'ready') return;
    link.request('watch.cancel', { watch: appWatch }).catch(() => {});
  }

  /**
   * @param {Panel} panel
   * @param {unknown} id
   * @param {string} method
   * @param {Record<string, any>} params
   */
  function call(panel, id, method, params) {
    const result = PANEL_METHODS.has(method)
      ? link.request(method, params)
      : Promise.reject(new BridgeError(ErrorCode.UNKNOWN_METHOD, 'Unknown method'));
    result.then(
      (value) => post(panel, { type: 'reply', id, result: value }),
      (error) => post(panel, { type: 'reply', id, error: toWireError(error) }),
    );
  }

  /** @param {Panel} panel */
  function detach(panel) {
    panels.delete(panel);
    for (const key of [...panel.watches.keys()]) unwatch(panel, key);
  }

  /**
   * @param {Panel} panel
   * @param {Record<string, any>} message
   */
  function post(panel, message) {
    try {
      panel.port.postMessage(message);
    } catch (error) {
      detach(panel);
    }
  }

  return {
    attach,

    /**
     * Relays link status; app watches die with the connection and are opened again once it is ready.
     * @param {string} status
     * @param {Record<string, any> | null} hello
     */
    onLinkStatus(status, hello) {
      if (status !== 'ready') {
        byAppWatch.clear();
        connection.events = [];
        connection.chars = 0;
        connection = earlyEvents();
        for (const panel of panels) for (const entry of panel.watches.values()) {
          entry.appWatch = null;
          entry.opening = false;
          entry.generation++;
        }
      }
      for (const panel of [...panels]) {
        post(panel, { type: 'status', status, features: hello?.features ?? null });
        if (status !== 'ready') continue;
        for (const [key, entry] of panel.watches) if (!entry.appWatch) open(panel, key, entry);
      }
    },

    /**
     * Routes a watch event of the app to its panel; other events are ignored here.
     * @param {string} event
     * @param {Record<string, any>} params
     */
    onEvent(event, params) {
      const target = byAppWatch.get(String(params.watch ?? ''));
      if (target) {
        post(target.panel, { type: 'event', key: target.key, event, params });
      } else if (connection.pending && !connection.overflow && ['chats.changed', 'chat.changed'].includes(event)) {
        const chars = JSON.stringify(params).length;
        if (connection.events.length >= MAX_EARLY_EVENTS || connection.chars + chars > MAX_EARLY_CHARS) {
          connection.overflow = true;
          connection.events = [];
        } else {
          connection.events.push({ event, params });
          connection.chars += chars;
        }
      }
    },

    /** Tells every panel the current consent and number of controlled tabs. */
    onAgentChanged() {
      const state = agent.state();
      for (const panel of [...panels]) post(panel, { type: 'agent', ...state });
    },

    /** Number of open panels, for diagnostics and tests. */
    get size() {
      return panels.size;
    },
  };
}
