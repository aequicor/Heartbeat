// @ts-check
import { browserInfo } from './browser-info.js';
import { createNativeLink } from './native-link.js';
import { PANEL_PORT, createPanelHub } from './panel-hub.js';

/** Periodic wake-up that reopens the native port while the service worker would otherwise sleep. */
const RECONNECT_ALARM = 'heartbeat-reconnect';

/** @type {ReturnType<typeof createPanelHub> | null} */
let hub = null;

const link = createNativeLink({
  chrome,
  extensionVersion: chrome.runtime.getManifest().version,
  browser: browserInfo(/** @type {any} */ (globalThis.navigator)),
  capabilities: () => ['chat'],
  onStatus: (status, hello) => hub?.onLinkStatus(status, hello),
  onEvent: (event, params) => hub?.onEvent(event, params),
});

hub = createPanelHub({ link, extensionOrigin: chrome.runtime.getURL('') });

chrome.runtime.onConnect.addListener((/** @type {any} */ port) => {
  if (port.name === PANEL_PORT) hub?.attach(port);
});

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
