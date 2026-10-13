// @ts-check
import { normalizePage } from '../shared/page-capture.js';

/** Context menu item that sends the selection to the side panel. */
export const SELECTION_MENU = 'heartbeat-send-selection';

/** Per-window storage and lock key, shared by the producer and all panels in that window. @param {number} windowId */
export const pendingAttachmentKey = (windowId) => `pendingAttachment:${windowId}`;

/**
 * Consumes a selection exactly once, even when two panels in the target window open together.
 * @param {any} chrome
 * @param {number} windowId
 * @param {Pick<LockManager, 'request'>} [locks]
 */
export async function takeSelection(chrome, windowId, locks = navigator.locks) {
  const key = pendingAttachmentKey(windowId);
  return locks.request(key, async () => {
    const page = (await chrome.storage.session.get(key))?.[key];
    if (page) await chrome.storage.session.remove(key);
    return page;
  });
}

/**
 * Registers the selection item; the click hands the selection to the side panel through session storage and opens
 * the panel on that tab (a menu click counts as the user gesture `sidePanel.open` needs).
 * @param {any} chrome
 * @param {(key: string) => string} t
 */
export function installSelectionMenu(chrome, t) {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: SELECTION_MENU, title: t('sendSelection'), contexts: ['selection'] });
  });
}

/**
 * @param {any} chrome
 * @param {{ menuItemId: string, selectionText?: string, pageUrl?: string }} info
 * @param {{ id?: number, windowId?: number, title?: string, url?: string } | undefined} tab
 * @param {Pick<LockManager, 'request'>} [locks]
 */
export async function onSelectionMenu(chrome, info, tab, locks = navigator.locks) {
  if (info.menuItemId !== SELECTION_MENU || tab?.id === undefined || tab.windowId === undefined) return;
  const page = normalizePage({
    url: tab.url ?? info.pageUrl,
    title: tab.title,
    selection: info.selectionText,
    text: '',
  });
  if (!page) return;
  const open = chrome.sidePanel.open({ tabId: tab.id });
  const key = pendingAttachmentKey(tab.windowId);
  await Promise.all([open, locks.request(key, () => chrome.storage.session.set({ [key]: page }))]);
}
