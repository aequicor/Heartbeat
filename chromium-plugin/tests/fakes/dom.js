// @ts-check
/** Small DOM with node identity, focus and insertion semantics for side-panel behavior tests. */
export function fakeDocument() {
  const document = /** @type {any} */ ({ activeElement: null });
  class Element {
    /** @param {string} tag */
    constructor(tag) {
      this.tagName = tag;
      this.text = '';
      this.value = '';
      this.className = '';
      this.dataset = /** @type {Record<string, string>} */ ({});
      this.attrs = /** @type {Record<string, string>} */ ({});
      this.listeners = /** @type {Record<string, Function>} */ ({});
      this.children = /** @type {Element[]} */ ([]);
      this.parentElement = /** @type {Element | null} */ (null);
      this.open = false;
      this.checked = false;
      this.scrollHeight = 0;
      this.scrollTop = 0;
      this.clientHeight = 0;
    }
    get options() { return this.children; }
    get firstElementChild() { return this.children[0] ?? null; }
    /** @returns {string} */
    get textContent() { return this.text + this.children.map((it) => it.textContent).join(''); }
    /** @param {string} value */
    set textContent(value) { this.replaceChildren(); this.text = value; }
    /** @param {string} name @param {string} value */
    setAttribute(name, value) { this.attrs[name] = value; if (name === 'value') this.value = value; }
    /** @param {string} name @param {Function} listener */
    addEventListener(name, listener) { this.listeners[name] = listener; }
    /** @param {string} name @param {any} [event] */
    fire(name, event = {}) { return this.listeners[name]?.(event); }
    focus() { document.activeElement = this; }
    remove() {
      if (!this.parentElement) return;
      if (this.contains(document.activeElement)) document.activeElement = null;
      this.parentElement.children = this.parentElement.children.filter((it) => it !== this);
      this.parentElement = null;
    }
    /** @param {Element | null} node @returns {boolean} */
    contains(node) { return this === node || this.children.some((it) => it.contains(node)); }
    /** @param {Element} node @param {Element | null} before */
    insertBefore(node, before) {
      node.remove();
      const index = before ? this.children.indexOf(before) : this.children.length;
      this.children.splice(index, 0, node);
      node.parentElement = this;
    }
    /** @param {...(Element | string)} nodes */
    append(...nodes) {
      for (const node of nodes) this.insertBefore(typeof node === 'string' ? document.createTextNode(node) : node, null);
    }
    /** @param {...(Element | string)} nodes */
    replaceChildren(...nodes) { for (const child of [...this.children]) child.remove(); this.text = ''; this.append(...nodes); }
    /** @param {string} selector @returns {Element[]} */
    querySelectorAll(selector) {
      return this.children.flatMap((child) => {
        const matches = selector === 'input:checked' ? child.tagName === 'input' && child.checked : child.tagName === selector;
        return [...(matches ? [child] : []), ...child.querySelectorAll(selector)];
      });
    }
    /** @param {string} selector */
    querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  }
  const ids = new Map();
  document.createElement = (/** @type {string} */ tag) => new Element(tag);
  document.createElementNS = (/** @type {string} */ _namespace, /** @type {string} */ tag) => new Element(tag);
  document.createDocumentFragment = () => new Element('fragment');
  document.createTextNode = (/** @type {string} */ text) => { const node = new Element('text'); node.text = text; return node; };
  document.getElementById = (/** @type {string} */ id) => {
    if (!ids.has(id)) ids.set(id, new Element(id));
    return ids.get(id);
  };
  return document;
}
