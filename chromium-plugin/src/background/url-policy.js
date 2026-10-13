// @ts-check

/** Store pages the debugger must never touch, whatever the app asks. */
const STORE_HOSTS = new Set(['chromewebstore.google.com', 'microsoftedge.microsoft.com', 'addons.opera.com']);

/**
 * Whether an agent may drive a tab showing [url]: web pages only, never browser, extension or store pages.
 * The app applies its own navigation policy on top (public web, approved origins).
 * @param {string | undefined | null} url
 */
export function isControllableUrl(url) {
  if (!url) return false;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  if (STORE_HOSTS.has(parsed.hostname)) return false;
  return !(parsed.hostname === 'chrome.google.com' && parsed.pathname.startsWith('/webstore'));
}
