// @ts-check

/** Brands every Chromium browser reports besides its own. */
const GENERIC_BRANDS = /^(Chromium|Not.*Brand.*|Not\W*A\W*Brand)$/i;

/**
 * The browser brand and major version reported to Heartbeat in `hello`.
 * @param {{ userAgentData?: { brands?: { brand: string, version: string }[] } } | undefined} navigatorLike
 * @returns {{ brand: string, version: string }}
 */
export function browserInfo(navigatorLike) {
  const brands = navigatorLike?.userAgentData?.brands ?? [];
  const own = brands.find((entry) => !GENERIC_BRANDS.test(entry.brand));
  const chromium = brands.find((entry) => entry.brand === 'Chromium');
  const chosen = own ?? chromium;
  return chosen ? { brand: chosen.brand, version: chosen.version } : { brand: 'Chromium', version: '' };
}
