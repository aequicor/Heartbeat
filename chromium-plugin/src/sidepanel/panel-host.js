// @ts-check
import { pendingAttachmentKey, takeSelection } from '../background/context-menu.js';
import { PANEL_PORT } from '../background/panel-hub.js';
import { capturePage } from '../shared/page-capture.js';

/**
 * The extension side of the Compose side panel (Kotlin/Wasm, `chromium-plugin/panel`): the port to the service
 * worker, page capture and selections sent from the context menu. Values cross the wasm boundary as JSON strings.
 * Messages that arrive before the panel listens are kept and delivered in order.
 * @param {any} chrome
 * @param {Pick<LockManager, 'request'>} [locks]
 */
export function createPanelHost(chrome, locks = navigator.locks) {
  const port = chrome.runtime.connect({ name: PANEL_PORT });
  const panelWindow = chrome.windows.getCurrent();
  /** @type {((json: string) => void) | null} */
  let listener = null;
  /** @type {string[]} */
  const early = [];
  port.onMessage.addListener((/** @type {unknown} */ message) => {
    const json = JSON.stringify(message);
    if (listener) listener(json);
    else early.push(json);
  });

  return Object.freeze({
    /** @param {(json: string) => void} callback Receives every message of the service worker, earlier ones first. */
    listen(callback) {
      listener = callback;
      for (const json of early.splice(0)) callback(json);
    },

    /** @param {string} json A message for the service worker. */
    post(json) {
      port.postMessage(JSON.parse(json));
    },

    /** The active tab as a page attachment (JSON), or `null` when it cannot be attached. */
    async capturePage() {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      const page = tab?.id === undefined ? null : await capturePage(chrome, tab.id, tab.url);
      return page ? JSON.stringify(page) : null;
    },

    /** A selection the context menu sent to this window's panel (JSON), taken at most once. */
    async takeSelection() {
      const { id } = await panelWindow;
      if (id === undefined) return null;
      const page = await takeSelection(chrome, id, locks);
      return page ? JSON.stringify(page) : null;
    },

    /** @param {() => void} callback Called when the context menu sends a selection to this window. */
    onSelection(callback) {
      chrome.storage.session.onChanged.addListener(async (/** @type {Record<string, unknown>} */ changes) => {
        const { id } = await panelWindow;
        if (id !== undefined && pendingAttachmentKey(id) in changes) callback();
      });
    },
  });
}
