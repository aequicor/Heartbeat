// @ts-check

/**
 * Localized text from `_locales` (English and Russian); a missing message falls back to its key so tests and
 * broken locales stay readable.
 * @param {{ getMessage: (name: string, substitutions?: string[]) => string } | undefined} i18n
 * @returns {(key: string, ...substitutions: (string | number)[]) => string}
 */
export function createTranslator(i18n) {
  return (key, ...substitutions) => i18n?.getMessage(key, substitutions.map(String)) || key;
}

/** Message key of a wire error code. */
export const ERROR_MESSAGES = Object.freeze({
  busy: 'errorBusy',
  unknown_chat: 'errorUnknownChat',
  not_continuable: 'errorNotContinuable',
  unavailable: 'errorUnavailable',
  stale_request: 'errorStale',
  timeout: 'errorTimeout',
  bad_request: 'errorBadRequest',
  too_large: 'errorTooLarge',
});

/**
 * @param {string} code
 */
export function errorMessage(code) {
  return /** @type {Record<string, string>} */ (ERROR_MESSAGES)[code] ?? 'errorGeneric';
}
