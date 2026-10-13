// @ts-check
import { ChunkAssembler } from '../shared/chunks.js';
import {
  BridgeError,
  CALL_TIMEOUT_MS,
  ErrorCode,
  HOST_NAME,
  PROTOCOL_VERSION,
  createIds,
  isObject,
  toWireError,
} from '../shared/protocol.js';
import { backoffDelay } from './backoff.js';

/**
 * Connection state shown to the user.
 * - `connecting`: the port is being opened;
 * - `host_missing`: the host is not registered (Heartbeat settings → «Браузер Chrome»);
 * - `forbidden`: the registered host does not allow this extension id;
 * - `waiting_for_app`: the host runs, Heartbeat is not running or the bridge is off;
 * - `handshaking`: `hello` is in flight;
 * - `ready`: requests can be sent;
 * - `incompatible`: Heartbeat and the extension speak different protocol versions;
 * - `disconnected`: the port closed for another reason; a reconnect is scheduled.
 * @typedef {'connecting'|'host_missing'|'forbidden'|'waiting_for_app'|'handshaking'|'ready'|'incompatible'|'disconnected'} LinkStatus
 */

/**
 * @typedef {object} LinkOptions
 * @property {any} chrome chrome.* API (a fake in tests).
 * @property {string} extensionVersion
 * @property {{ brand: string, version: string }} browser
 * @property {() => string[]} capabilities Current capabilities, sent in `hello`.
 * @property {(status: LinkStatus, hello: Record<string, any> | null) => void} [onStatus]
 * @property {(event: string, params: Record<string, any>) => void} [onEvent] Events from the app.
 * @property {(method: string, params: Record<string, any>) => Promise<unknown> | unknown} [onRequest] Calls from the app.
 * @property {{ setTimeout: typeof setTimeout, clearTimeout: typeof clearTimeout }} [timers]
 * @property {() => number} [now]
 */

/**
 * The extension end of the bridge: one native-messaging port to the Heartbeat host.
 * The host reports whether Heartbeat is running; once it is, the link performs `hello` and becomes `ready`.
 * Pending requests fail as `unavailable` whenever the app goes away. A closed port is reopened with backoff.
 * @param {LinkOptions} options
 */
export function createNativeLink(options) {
  const { chrome, extensionVersion, browser, capabilities } = options;
  const onStatus = options.onStatus ?? (() => {});
  const onEvent = options.onEvent ?? (() => {});
  const onRequest = options.onRequest ?? (() => {
    throw new BridgeError(ErrorCode.UNKNOWN_METHOD, 'Unknown method');
  });
  const timers = options.timers ?? globalThis;
  const ids = createIds('e');
  let assembler = new ChunkAssembler({ now: options.now });
  let generation = 0;
  /** @type {Map<string, { resolve: (value: any) => void, reject: (error: Error) => void, timer: any }>} */
  const pending = new Map();
  /** @type {any} */
  let port = null;
  /** @type {LinkStatus} */
  let status = 'disconnected';
  /** @type {Record<string, any> | null} */
  let hello = null;
  let attempt = 0;
  /** @type {any} */
  let reconnectTimer = null;

  /**
   * @param {LinkStatus} next
   */
  function setStatus(next) {
    if (status === next && next !== 'ready') return;
    status = next;
    onStatus(status, hello);
  }

  function connect() {
    if (port) return;
    clearReconnect();
    setStatus('connecting');
    try {
      port = chrome.runtime.connectNative(HOST_NAME);
    } catch (error) {
      port = null;
      setStatus('host_missing');
      scheduleReconnect();
      return;
    }
    const opened = port;
    opened.onMessage.addListener((/** @type {unknown} */ message) => {
      if (port === opened) receive(message);
    });
    opened.onDisconnect.addListener(() => {
      if (port === opened) closed(chrome.runtime.lastError?.message ?? '');
    });
  }

  /**
   * @param {string} reason
   */
  function closed(reason) {
    port = null;
    invalidateApp();
    setStatus(classify(reason));
    scheduleReconnect();
  }

  function scheduleReconnect() {
    clearReconnect();
    reconnectTimer = timers.setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, backoffDelay(attempt++));
  }

  function clearReconnect() {
    if (reconnectTimer !== null) timers.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  /**
   * @param {unknown} message
   */
  function receive(message) {
    if (!isObject(message)) return;
    switch (message.kind) {
      case 'host':
        if (message.state === 'connected') handshake();
        if (message.state === 'waiting') appGone();
        break;
      case 'chunk':
        receiveChunk(message);
        break;
      case 'res':
        settle(message);
        break;
      case 'evt':
        if (message.event === 'app.features' && hello && isObject(message.params)) {
          hello = { ...hello, features: message.params };
          onStatus(status, hello);
        }
        onEvent(String(message.event), isObject(message.params) ? message.params : {});
        break;
      case 'req':
        answer(message);
        break;
      default:
        break;
    }
  }

  /**
   * @param {Record<string, any>} chunk
   */
  function receiveChunk(chunk) {
    try {
      const frame = assembler.accept(chunk);
      if (frame) receive(frame);
    } catch (error) {
      console.warn('Heartbeat: dropped a broken chunk set', error);
    }
  }

  /** Ends one app socket lifetime even when the native host port stays open. */
  function invalidateApp() {
    generation++;
    hello = null;
    assembler = new ChunkAssembler({ now: options.now });
    failPending(ErrorCode.UNAVAILABLE, 'Heartbeat is not available');
  }

  function appGone() {
    invalidateApp();
    setStatus('waiting_for_app');
  }

  function handshake() {
    invalidateApp();
    const current = generation;
    setStatus('handshaking');
    send('hello', {
      protocol: [PROTOCOL_VERSION],
      extension: { version: extensionVersion },
      browser,
      capabilities: capabilities(),
    }).then(
      (result) => {
        if (generation !== current) return;
        hello = isObject(result) ? result : {};
        attempt = 0;
        setStatus('ready');
      },
      (error) => {
        if (generation !== current) return;
        if (error instanceof BridgeError && error.code === ErrorCode.UNSUPPORTED_PROTOCOL) {
          setStatus('incompatible');
        } else if (status === 'handshaking') {
          setStatus('waiting_for_app');
        }
      },
    );
  }

  /**
   * @param {string} method
   * @param {Record<string, any>} params
   * @param {number} [timeoutMs]
   * @returns {Promise<any>}
   */
  function send(method, params, timeoutMs = CALL_TIMEOUT_MS) {
    const opened = port;
    if (!opened) return Promise.reject(new BridgeError(ErrorCode.UNAVAILABLE, 'Heartbeat is not available'));
    const id = ids.next();
    return new Promise((resolve, reject) => {
      const timer = timers.setTimeout(() => {
        pending.delete(id);
        reject(new BridgeError(ErrorCode.TIMEOUT, 'Heartbeat did not answer'));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
      try {
        opened.postMessage({ kind: 'req', id, method, params });
      } catch (error) {
        timers.clearTimeout(timer);
        pending.delete(id);
        reject(new BridgeError(ErrorCode.UNAVAILABLE, 'Heartbeat is not available'));
      }
    });
  }

  /**
   * @param {Record<string, any>} response
   */
  function settle(response) {
    const entry = pending.get(response.id);
    if (!entry) return;
    pending.delete(response.id);
    timers.clearTimeout(entry.timer);
    if (isObject(response.error)) {
      entry.reject(new BridgeError(String(response.error.code), String(response.error.message ?? '')));
    } else {
      entry.resolve(response.result ?? {});
    }
  }

  /**
   * @param {string} code
   * @param {string} message
   */
  function failPending(code, message) {
    for (const [id, entry] of pending) {
      timers.clearTimeout(entry.timer);
      entry.reject(new BridgeError(code, message));
      pending.delete(id);
    }
  }

  /**
   * @param {Record<string, any>} request
   */
  function answer(request) {
    const opened = port;
    const current = generation;
    Promise.resolve()
      .then(() => {
        if (generation !== current || port !== opened) return;
        return onRequest(String(request.method), isObject(request.params) ? request.params : {});
      })
      .then(
        (result) => ({ kind: 'res', id: request.id, result: result ?? {} }),
        (error) => ({ kind: 'res', id: request.id, error: toWireError(error) }),
      )
      .then((response) => {
        if (generation === current && port === opened && opened) opened.postMessage(response);
      });
  }

  return {
    connect,
    /** @returns {LinkStatus} */
    get status() {
      return status;
    },
    /** Result of the last successful `hello`, or `null`. */
    get hello() {
      return hello;
    },
    /**
     * Calls the app; rejects with {@link BridgeError} unless the link is ready.
     * @param {string} method
     * @param {Record<string, any>} [params]
     * @param {number} [timeoutMs]
     */
    request(method, params = {}, timeoutMs) {
      if (status !== 'ready') {
        return Promise.reject(new BridgeError(ErrorCode.UNAVAILABLE, 'Heartbeat is not available'));
      }
      return send(method, params, timeoutMs);
    },
    /**
     * Sends an event to the app when ready; silently skipped otherwise.
     * @param {string} event
     * @param {Record<string, any>} [params]
     */
    notify(event, params = {}) {
      if (status === 'ready' && port) port.postMessage({ kind: 'evt', event, params });
    },
  };
}

/**
 * Maps Chrome's disconnect reason to a status.
 * @param {string} reason
 * @returns {LinkStatus}
 */
export function classify(reason) {
  if (/not found/i.test(reason)) return 'host_missing';
  if (/forbidden/i.test(reason)) return 'forbidden';
  return 'disconnected';
}
