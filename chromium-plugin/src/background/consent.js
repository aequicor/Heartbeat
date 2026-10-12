// @ts-check

/** Local storage key of the user's consent to agent control in this browser. */
export const CONSENT_KEY = 'agentControlAllowed';

/**
 * Whether the user allowed Heartbeat agents to control this browser; off until switched on in the side panel.
 * @param {any} chrome
 */
export async function readConsent(chrome) {
  const stored = await chrome.storage.local.get(CONSENT_KEY);
  return stored?.[CONSENT_KEY] === true;
}

/**
 * @param {any} chrome
 * @param {boolean} isAllowed
 */
export function writeConsent(chrome, isAllowed) {
  return chrome.storage.local.set({ [CONSENT_KEY]: isAllowed });
}
