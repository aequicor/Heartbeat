// @ts-check

const PREFIX = 'heartbeat-diagram:';
const MAX_PNG_BYTES = 10 * 1024 * 1024;
const MAX_BASE64_LENGTH = Math.ceil(MAX_PNG_BYTES / 3) * 4;
const MAX_PENDING_COUNT = 2;
const TRANSFER_TIMEOUT_MS = 15_000;
const TOKEN = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

/** @typedef {{ png: string, background: string, title: string }} Diagram */
/** @typedef {Pick<typeof globalThis, 'setTimeout' | 'clearTimeout'>} Timers */

/** The one-time handoff is gone (including after a reload) or its short lifetime expired. */
export class DiagramUnavailableError extends Error {}

/**
 * Accepts only bounded raster data. The PNG header limits allocation before the browser decodes the image;
 * neither PlantUML source nor markup is interpreted here. The image decoder checks the remaining PNG data.
 * @param {unknown} value
 * @returns {Diagram}
 */
export function validateDiagram(value) {
  if (!value || typeof value !== 'object') throw new Error('Invalid diagram');
  const { png, background, title } = /** @type {Record<string, unknown>} */ (value);
  if (typeof png !== 'string' || png.length < 44 || png.length > MAX_BASE64_LENGTH ||
      png.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(png)) {
    throw new Error('Invalid or oversized PNG');
  }
  const size = png.length / 4 * 3 - (png.endsWith('==') ? 2 : png.endsWith('=') ? 1 : 0);
  if (size > MAX_PNG_BYTES) throw new Error('Oversized PNG');
  const header = Uint8Array.from(atob(png.slice(0, 44)), (character) => character.charCodeAt(0));
  const signature = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82];
  if (!signature.every((byte, index) => header[index] === byte)) throw new Error('Invalid PNG header');
  const view = new DataView(header.buffer);
  const width = view.getUint32(16);
  const height = view.getUint32(20);
  if (!width || !height || width > 4096 || height > 4096 || width * height > 16 * 1024 * 1024) {
    throw new Error('Oversized PNG dimensions');
  }
  if (typeof background !== 'string' || !/^#[0-9a-f]{6}$/i.test(background)) {
    throw new Error('Invalid diagram background');
  }
  if (title !== undefined && (typeof title !== 'string' || title.length > 256)) {
    throw new Error('Invalid diagram title');
  }
  return { png, background, title: typeof title === 'string' ? title : '' };
}

/**
 * Opens a PNG over a one-time runtime Port, without storage, a server, or image data in the URL. Up to two
 * pending transfers belong to the panel; listeners and data are released on decode acknowledgement, error,
 * timeout or panel teardown. Failed openings close only their own created tab, including late creation replies.
 * The receiver owns its PNG afterwards. Chrome messaging's 64 MiB limit permits
 * the renderer's full 10 MiB PNG budget even after base64 encoding. Resolves only after the viewer decodes it.
 * @param {any} chrome
 * @param {{ timers?: Timers, lifecycle?: Pick<EventTarget, 'addEventListener' | 'removeEventListener'> }} [options]
 * @returns {(windowId: number, json: string) => Promise<void>}
 */
export function createDiagramOpener(chrome, { timers = globalThis, lifecycle = globalThis } = {}) {
  let pending = 0;
  return async (windowId, json) => {
    if (pending >= MAX_PENDING_COUNT) throw new Error('Too many diagrams waiting to open');
    if (typeof json !== 'string' || json.length > MAX_BASE64_LENGTH + 2048) throw new Error('Oversized diagram');
    /** @type {Diagram | null} */
    let diagram = validateDiagram(JSON.parse(json));
    json = '';
    pending++;
    const token = crypto.randomUUID();
    const viewerPage = chrome.runtime.getURL('src/sidepanel/diagram.html');
    const origin = chrome.runtime.getURL('').replace(/\/$/, '');
    const url = `${viewerPage}#${token}`;
    return new Promise((resolve, reject) => {
      let finished = false;
      let failed = false;
      /** @type {number | undefined} */
      let tabId;
      /** @type {any} */
      let receiver = null;
      const timer = timers.setTimeout(() => finish(new Error('Diagram transfer timed out')), TRANSFER_TIMEOUT_MS);
      /** @param {number} id Roll back our viewer only while the user has not navigated its tab elsewhere. */
      async function removeCreatedTab(id) {
        try {
          const tab = await chrome.tabs.get(id);
          if (tab.url !== url || (tab.pendingUrl && tab.pendingUrl !== url)) return;
          await chrome.tabs.remove(id);
        } catch (error) {
          console.warn('Heartbeat: could not close an unsuccessful diagram tab', error);
        }
      }
      /** @param {unknown} [error] */
      function finish(error) {
        if (finished) return;
        finished = true;
        failed = Boolean(error);
        pending--;
        diagram = null;
        timers.clearTimeout(timer);
        chrome.runtime.onConnect.removeListener(connect);
        lifecycle.removeEventListener?.('pagehide', close);
        if (receiver) {
          receiver.onMessage.removeListener(message);
          receiver.onDisconnect.removeListener(disconnect);
          receiver.disconnect();
          receiver = null;
        }
        if (error && tabId !== undefined) void removeCreatedTab(tabId).then(() => reject(error));
        else if (error) reject(error);
        else resolve();
      }
      const close = () => finish(new DiagramUnavailableError('The panel closed during transfer'));
      const disconnect = () => {
        const reason = chrome.runtime.lastError?.message;
        finish(new DiagramUnavailableError(reason || 'The viewer closed during transfer'));
      };
      /** @param {any} value */
      const message = (value) => {
        if (value?.type === 'ready') finish();
        else finish(new Error('The viewer could not decode the diagram'));
      };
      const deliver = () => {
        if (!receiver || tabId === undefined || finished) return;
        if (receiver.sender.tab.id !== tabId) {
          receiver.disconnect();
          receiver = null;
          return;
        }
        receiver.onMessage.addListener(message);
        receiver.onDisconnect.addListener(disconnect);
        try {
          receiver.postMessage({ type: 'diagram', diagram });
          diagram = null;
        } catch (error) {
          finish(error);
        }
      };
      /** @param {any} port */
      function connect(port) {
        if (port.name !== PREFIX + token) return;
        const sender = port.sender;
        // Chrome does not promise a fragment in MessageSender.url. The port name authenticates the token;
        // the exact bundled page and returned tab id identify its only permitted recipient.
        const senderPage = typeof sender?.url === 'string' ? sender.url.split('#')[0] : '';
        if (finished || receiver || sender?.id !== chrome.runtime.id || senderPage !== viewerPage ||
            (sender.origin !== undefined && sender.origin !== origin) || sender.frameId !== 0 ||
            typeof sender.tab?.id !== 'number') {
          port.disconnect();
          return;
        }
        receiver = port;
        deliver();
      }
      chrome.runtime.onConnect.addListener(connect);
      lifecycle.addEventListener?.('pagehide', close, { once: true });
      Promise.resolve().then(() => finished ? null : chrome.tabs.create({ url, active: true, windowId }))
        .then((/** @type {{ id?: number } | null} */ tab) => {
          if (finished) {
            if (failed && tab?.id !== undefined) void removeCreatedTab(tab.id);
            return;
          }
          if (tab?.id === undefined) { finish(new Error('The diagram tab is unavailable')); return; }
          tabId = tab.id;
          deliver();
        }, finish);
    });
  };
}

/**
 * Receives and displays a diagram before acknowledging it. The Port name contains only this random transfer token,
 * and is released after one diagram. An aborted/failed decode rejects the panel's open request for fallback.
 * Reloads intentionally require opening the diagram from the chat again.
 * @param {any} chrome
 * @param {string} token
 * @param {(diagram: Diagram) => Promise<void>} display
 * @param {{ signal?: AbortSignal, timers?: Timers }} [options]
 * @returns {Promise<void>}
 */
export async function receiveDiagram(chrome, token, display, { signal, timers = globalThis } = {}) {
  if (!TOKEN.test(token)) throw new DiagramUnavailableError('Invalid diagram token');
  if (signal?.aborted) throw new DiagramUnavailableError('The viewer closed during transfer');
  const port = chrome.runtime.connect({ name: PREFIX + token });
  return new Promise((resolve, reject) => {
    let finished = false;
    let received = false;
    const timer = timers.setTimeout(() => finish(new DiagramUnavailableError('Diagram transfer timed out')),
      TRANSFER_TIMEOUT_MS);
    /** @param {unknown} [error] */
    function finish(error) {
      if (finished) return;
      finished = true;
      timers.clearTimeout(timer);
      port.onMessage.removeListener(message);
      port.onDisconnect.removeListener(disconnect);
      signal?.removeEventListener('abort', abort);
      if (error) { port.disconnect(); reject(error); }
      else resolve(); // The sender disconnects after receiving our decode acknowledgement.
    }
    const abort = () => finish(new DiagramUnavailableError('The viewer closed during transfer'));
    const disconnect = () => {
      const reason = chrome.runtime.lastError?.message;
      finish(new DiagramUnavailableError(reason || 'Diagram no longer available'));
    };
    /** @param {any} value */
    const message = async (value) => {
      if (received || finished) return;
      received = true;
      try {
        if (value?.type !== 'diagram') throw new Error('Invalid diagram transfer');
        await display(validateDiagram(value.diagram));
        if (finished) return;
        port.postMessage({ type: 'ready' });
        finish();
      } catch (error) {
        finish(error);
      }
    };
    port.onMessage.addListener(message);
    port.onDisconnect.addListener(disconnect);
    signal?.addEventListener('abort', abort, { once: true });
  });
}
