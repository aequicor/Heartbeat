// @ts-check

/** Limits the app accepts for a captured page (`ChatWireLimits`). */
export const PAGE_LIMITS = Object.freeze({ text: 200_000, selection: 20_000, title: 512, url: 4096 });

/**
 * Runs inside the page (serialized by `chrome.scripting.executeScript`), so it must not reference anything outside.
 * Returns the title, address, the user's selection and the readable text of the main content.
 */
export function readPage() {
  const root = document.querySelector('article, main, [role="main"]') ?? document.body;
  return {
    url: location.href,
    title: document.title,
    selection: String(globalThis.getSelection?.() ?? ''),
    text: root instanceof HTMLElement ? root.innerText : (root?.textContent ?? ''),
  };
}

/**
 * Bounds and tidies a page read by [readPage]; `null` for pages that cannot be attached.
 * @param {{ url?: unknown, title?: unknown, selection?: unknown, text?: unknown } | null | undefined} raw
 * @returns {{ kind: 'page', url: string, title: string, text: string, selection?: string } | null}
 */
export function normalizePage(raw) {
  const url = typeof raw?.url === 'string' ? raw.url : '';
  if (!/^https?:\/\//i.test(url) || url.length > PAGE_LIMITS.url) return null;
  const selection = tidy(String(raw?.selection ?? '')).slice(0, PAGE_LIMITS.selection);
  return {
    kind: 'page',
    url,
    title: String(raw?.title ?? '').replace(/\s+/g, ' ').trim().slice(0, PAGE_LIMITS.title),
    text: tidy(String(raw?.text ?? '')).slice(0, PAGE_LIMITS.text),
    ...(selection ? { selection } : {}),
  };
}

/**
 * Collapses runs of blank lines and trailing spaces, which pages produce in bulk.
 * @param {string} text
 */
function tidy(text) {
  return text.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

/**
 * Captures a tab. Access comes from `activeTab` (the panel was opened from the toolbar on that tab) or from an
 * optional host permission the user grants for the page's origin on request.
 * @param {any} chrome
 * @param {number} tabId
 * @param {string | undefined} tabUrl Address of the tab when known, for the permission request.
 */
export async function capturePage(chrome, tabId, tabUrl) {
  try {
    return await run(chrome, tabId);
  } catch (error) {
    const origin = tabUrl ? originPattern(tabUrl) : null;
    if (!origin || !(await chrome.permissions.request({ origins: [origin] }))) throw error;
    return run(chrome, tabId);
  }
}

/**
 * @param {any} chrome
 * @param {number} tabId
 */
async function run(chrome, tabId) {
  const [result] = await chrome.scripting.executeScript({ target: { tabId }, func: readPage });
  return normalizePage(result?.result);
}

/**
 * Host permission pattern of an http(s) address.
 * @param {string} url
 */
export function originPattern(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? `${parsed.origin}/*` : null;
  } catch {
    return null;
  }
}
