// @ts-check

/** Message keys of link states that block the chat. */
const STATUS_MESSAGES = Object.freeze({
  connecting: 'statusConnecting',
  handshaking: 'statusConnecting',
  host_missing: 'statusHostMissing',
  forbidden: 'statusForbidden',
  waiting_for_app: 'statusWaiting',
  incompatible: 'statusIncompatible',
  disconnected: 'statusDisconnected',
});

/**
 * What the panel says instead of the chat, or `null` when the chat is usable.
 * @param {import('../store.js').PanelState} state
 * @returns {{ key: string, isProblem: boolean } | null}
 */
export function statusNotice(state) {
  const key = /** @type {Record<string, string>} */ (STATUS_MESSAGES)[state.status];
  if (key) return { key, isProblem: !['connecting', 'handshaking'].includes(state.status) };
  if (state.status === 'ready' && !state.features?.chat) return { key: 'chatsOff', isProblem: true };
  return null;
}
