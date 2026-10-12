// @ts-check

/**
 * Creates an element; strings become text nodes, never markup.
 * @param {Document} document
 * @param {string} tag
 * @param {{ className?: string, text?: string, title?: string, attrs?: Record<string, string>,
 *   on?: Record<string, (event: any) => void> }} [props]
 * @param {...(Node | string | null | undefined | false)} children
 * @returns {HTMLElement}
 */
export function h(document, tag, props = {}, ...children) {
  const element = document.createElement(tag);
  if (props.className) element.className = props.className;
  if (props.text !== undefined) element.textContent = props.text;
  if (props.title) element.title = props.title;
  for (const [name, value] of Object.entries(props.attrs ?? {})) element.setAttribute(name, value);
  for (const [name, listener] of Object.entries(props.on ?? {})) element.addEventListener(name, listener);
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    element.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return element;
}

/**
 * Short relative time of an epoch-millisecond instant.
 * @param {number} at
 * @param {number} now
 * @param {string} locale
 */
export function relativeTime(at, now, locale) {
  const seconds = Math.round((at - now) / 1000);
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'narrow' });
  const units = /** @type {const} */ ([['day', 86_400], ['hour', 3_600], ['minute', 60]]);
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
  }
  return format.format(0, 'minute');
}
