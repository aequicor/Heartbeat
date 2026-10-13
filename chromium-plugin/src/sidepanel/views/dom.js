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
 * Reconciles keyed children without detaching nodes that already occupy their place (preserving input focus).
 * @param {HTMLElement} parent
 * @param {HTMLElement[]} children
 */
export function reconcileChildren(parent, children) {
  const keep = new Set(children);
  for (const child of [...parent.children]) if (!keep.has(/** @type {HTMLElement} */ (child))) child.remove();
  children.forEach((child, index) => {
    if (parent.children[index] !== child) parent.insertBefore(child, parent.children[index] ?? null);
  });
}
