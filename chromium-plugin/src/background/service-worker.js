// @ts-check
import { createTranslator } from '../shared/strings.js';
import { browserInfo } from './browser-info.js';
import { CONSENT_KEY, readConsent, writeConsent } from './consent.js';
import { installSelectionMenu, onSelectionMenu } from './context-menu.js';
import { createNativeLink } from './native-link.js';
import { PANEL_PORT, createPanelHub } from './panel-hub.js';
import { createTabControl } from './tab-control.js';

/** Periodic wake-up that reopens the native port while the service worker would otherwise sleep. */
const RECONNECT_ALARM = 'heartbeat-reconnect';

/** @type {ReturnType<typeof createPanelHub> | null} */
let hub = null;
/** The user's consent to agent control; read from storage right below. */
let isAgentAllowed = false;

const capabilities = () => (isAgentAllowed ? ['studio', 'agentControl'] : ['studio']);

const tabs = createTabControl({
  chrome,
  isAllowed: () => isAgentAllowed,
  notify: (event, params) => {
    link.notify(event, params);
    if (event === 'tabs.summary') hub?.onAgentChanged();
  },
});

const link = createNativeLink({
  chrome,
  extensionVersion: chrome.runtime.getManifest().version,
  browser: browserInfo(/** @type {any} */ (globalThis.navigator)),
  capabilities,
  onStatus: (status, hello) => {
    // Without the app nobody owns the agent's tabs any more.
    if (status !== 'ready') tabs.releaseAll();
    hub?.onLinkStatus(status, hello);
  },
  onEvent: (event, params) => hub?.onEvent(event, params),
  onRequest: (method, params) => tabs.handle(method, params),
});

hub = createPanelHub({
  link,
  extensionOrigin: chrome.runtime.getURL(''),
  agent: {
    state: () => ({ isAllowed: isAgentAllowed, controlled: tabs.size }),
    setAllowed: (isAllowed) => writeConsent(chrome, isAllowed),
    revokeAll: () => tabs.revokeAll(),
  },
});

/** @param {boolean} isAllowed */
function applyConsent(isAllowed) {
  if (isAgentAllowed === isAllowed) return;
  isAgentAllowed = isAllowed;
  if (!isAllowed) tabs.revokeAll();
  link.notify('capabilities.changed', { capabilities: capabilities() });
  hub?.onAgentChanged();
}

readConsent(chrome).then(applyConsent);
chrome.storage.onChanged.addListener((/** @type {any} */ changes, /** @type {string} */ area) => {
  if (area === 'local' && CONSENT_KEY in changes) applyConsent(changes[CONSENT_KEY].newValue === true);
});

chrome.runtime.onConnect.addListener((/** @type {any} */ port) => {
  if (port.name === PANEL_PORT) hub?.attach(port);
});

chrome.runtime.onInstalled.addListener(() => installSelectionMenu(chrome, createTranslator(chrome.i18n)));
chrome.contextMenus.onClicked.addListener((/** @type {any} */ info, /** @type {any} */ tab) => {
  onSelectionMenu(chrome, info, tab).catch(() => {});
});

// The toolbar button opens the side panel with the chat.
chrome.sidePanel?.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});

// Alarms may be cleared by a browser restart, so every service worker start re-creates the missing one.
chrome.alarms.get(RECONNECT_ALARM).then((/** @type {unknown} */ alarm) => {
  if (!alarm) chrome.alarms.create(RECONNECT_ALARM, { periodInMinutes: 1 });
});

chrome.alarms.onAlarm.addListener((/** @type {{ name: string }} */ alarm) => {
  if (alarm.name === RECONNECT_ALARM) link.connect();
});

// Listening for browser start makes Chrome wake the worker then, so the bridge comes up with the browser.
chrome.runtime.onStartup.addListener(() => link.connect());

link.connect();
