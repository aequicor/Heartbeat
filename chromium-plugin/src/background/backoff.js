// @ts-check

const FIRST_DELAY_MS = 1_000;
const MAX_DELAY_MS = 60_000;

/**
 * Delay before reconnect attempt number `attempt` (0-based): doubles from one second up to a minute.
 * While the port is closed the service worker may sleep; a periodic alarm wakes it for the next attempt.
 * @param {number} attempt
 */
export function backoffDelay(attempt) {
  const exponent = Math.min(Math.max(0, attempt), 16);
  return Math.min(FIRST_DELAY_MS * 2 ** exponent, MAX_DELAY_MS);
}
