// @ts-check
import { browserInfo } from './browser-info.js';
import { createNativeLink } from './native-link.js';

/** Periodic wake-up that reopens the native port while the service worker would otherwise sleep. */
const RECONNECT_ALARM = 'heartbeat-reconnect';

const link = createNativeLink({
  chrome,
  extensionVersion: chrome.runtime.getManifest().version,
  browser: browserInfo(/** @type {any} */ (globalThis.navigator)),
  capabilities: () => ['chat'],
  onStatus: (status, hello) => {
    chrome.storage.session.set({ link: { status, features: hello?.features ?? null } });
  },
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
