// @ts-check
import { normalizePage } from '../shared/page-capture.js';

/** Context menu item that sends the selection to the side panel. */
export const SELECTION_MENU = 'heartbeat-send-selection';

/** Session storage key of a selection waiting for the side panel. */
export const PENDING_ATTACHMENT = 'pendingAttachment';

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
 */
export async function onSelectionMenu(chrome, info, tab) {
  if (info.menuItemId !== SELECTION_MENU || tab?.id === undefined) return;
  const page = normalizePage({
    url: tab.url ?? info.pageUrl,
    title: tab.title,
    selection: info.selectionText,
    text: '',
  });
  if (!page) return;
  const open = chrome.sidePanel.open({ tabId: tab.id });
  await chrome.storage.session.set({ [PENDING_ATTACHMENT]: page });
  await open;
}
