// @ts-check
/**
 * Wire protocol shared with Heartbeat (features/chromium-bridge, `BridgeProtocol.kt`).
 * Chrome frames native messages itself, so the extension exchanges plain JSON objects:
 * `req` / `res` / `evt` from either side, `chunk` from the app only, `host` written by the native host.
 */

/** Protocol version the extension speaks. */
export const PROTOCOL_VERSION = 1;

/** Native-messaging host registered by Heartbeat. */
export const HOST_NAME = 'io.aequicor.heartbeat';

/** Default wait for an answer from the app, in milliseconds. */
export const CALL_TIMEOUT_MS = 30_000;

/** Error codes of `res.error.code`; mirrors `BridgeErrorCode`. */
export const ErrorCode = Object.freeze({
  BAD_REQUEST: 'bad_request',
  UNKNOWN_METHOD: 'unknown_method',
  UNSUPPORTED_PROTOCOL: 'unsupported_protocol',
  NOT_READY: 'not_ready',
  UNAVAILABLE: 'unavailable',
  BUSY: 'busy',
  UNKNOWN_CHAT: 'unknown_chat',
  NOT_CONTINUABLE: 'not_continuable',
  NO_MODEL: 'no_model',
  STALE_REQUEST: 'stale_request',
  TOO_LARGE: 'too_large',
  FORBIDDEN: 'forbidden',
  TIMEOUT: 'timeout',
  INTERNAL: 'internal',
});

/**
 * A protocol failure carrying one of {@link ErrorCode}.
 */
export class BridgeError extends Error {
  /**
   * @param {string} code
   * @param {string} [message]
   */
  constructor(code, message = '') {
    super(message || code);
    this.name = 'BridgeError';
    this.code = code;
  }
}

/**
 * Converts anything thrown by a local handler into the wire error of a response.
 * Unknown failures never leak their message to the app.
 * @param {unknown} error
 * @returns {{ code: string, message: string }}
 */
export function toWireError(error) {
  if (error instanceof BridgeError) return { code: error.code, message: error.message };
  return { code: ErrorCode.INTERNAL, message: 'Extension failed' };
}

/**
 * Generates request ids prefixed by the sender side (`e:` for the extension).
 * @param {string} prefix
 */
export function createIds(prefix) {
  let next = 0;
  return { next: () => `${prefix}:${++next}` };
}

/**
 * @param {unknown} value
 * @returns {value is Record<string, any>}
 */
export function isObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
