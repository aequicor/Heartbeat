// @ts-check

/**
 * A small, safe Markdown subset for agent replies: paragraphs, headings, fenced code, lists, quotes, inline code,
 * bold, italic and links. Parsing yields plain data; rendering builds DOM nodes with `textContent` only, so no
 * markup from a reply is ever interpreted. Links are kept only for http(s) URLs.
 */

/**
 * @typedef {{ type: 'text', text: string } | { type: 'code', text: string } | { type: 'strong', children: Inline[] }
 *   | { type: 'em', children: Inline[] } | { type: 'link', href: string, children: Inline[] }} Inline
 * @typedef {{ type: 'paragraph', children: Inline[] } | { type: 'heading', level: number, children: Inline[] }
 *   | { type: 'code', language: string, text: string } | { type: 'list', ordered: boolean, items: Inline[][] }
 *   | { type: 'quote', children: Inline[] }} Block
 */

const FENCE = /^\s*```\s*([\w+-]*)\s*$/;
const HEADING = /^(#{1,6})\s+(.*)$/;
const BULLET = /^\s*[-*+]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;
const QUOTE = /^\s*>\s?(.*)$/;

/**
 * @param {string} source
 * @returns {Block[]}
 */
export function parseMarkdown(source) {
  /** @type {Block[]} */
  const blocks = [];
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  /** @type {string[]} */
  let paragraph = [];
  const flush = () => {
    if (paragraph.length) blocks.push({ type: 'paragraph', children: parseInline(paragraph.join('\n')) });
    paragraph = [];
  };
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const fence = FENCE.exec(line);
    if (fence) {
      flush();
      const code = [];
      for (index++; index < lines.length && !FENCE.test(lines[index]); index++) code.push(lines[index]);
      blocks.push({ type: 'code', language: fence[1], text: code.join('\n') });
      continue;
    }
    if (!line.trim()) {
      flush();
      continue;
    }
    const heading = HEADING.exec(line);
    if (heading) {
      flush();
      blocks.push({ type: 'heading', level: heading[1].length, children: parseInline(heading[2]) });
      continue;
    }
    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    if (bullet || numbered) {
      flush();
      const ordered = Boolean(numbered);
      const pattern = ordered ? NUMBERED : BULLET;
      const items = [];
      for (; index < lines.length && pattern.test(lines[index]); index++) {
        items.push(parseInline(/** @type {RegExpExecArray} */ (pattern.exec(lines[index]))[1]));
      }
      index--;
      blocks.push({ type: 'list', ordered, items });
      continue;
    }
    const quote = QUOTE.exec(line);
    if (quote) {
      flush();
      const quoted = [];
      for (; index < lines.length && QUOTE.test(lines[index]); index++) {
        quoted.push(/** @type {RegExpExecArray} */ (QUOTE.exec(lines[index]))[1]);
      }
      index--;
      blocks.push({ type: 'quote', children: parseInline(quoted.join('\n')) });
      continue;
    }
    paragraph.push(line);
  }
  flush();
  return blocks;
}

const INLINE = /(`+)([\s\S]*?)\1|\*\*([\s\S]+?)\*\*|__([\s\S]+?)__|\*([^*\s][\s\S]*?)\*|_([^_\s][\s\S]*?)_|\[([^\]]+)\]\(([^)\s]+)\)/;

/**
 * @param {string} text
 * @returns {Inline[]}
 */
export function parseInline(text) {
  /** @type {Inline[]} */
  const result = [];
  let rest = text;
  while (rest) {
    const match = INLINE.exec(rest);
    if (!match) {
      result.push({ type: 'text', text: rest });
      break;
    }
    if (match.index > 0) result.push({ type: 'text', text: rest.slice(0, match.index) });
    if (match[1]) {
      result.push({ type: 'code', text: match[2] });
    } else if (match[3] ?? match[4]) {
      result.push({ type: 'strong', children: parseInline(match[3] ?? match[4]) });
    } else if (match[5] ?? match[6]) {
      result.push({ type: 'em', children: parseInline(match[5] ?? match[6]) });
    } else {
      const href = safeHref(match[8]);
      const children = parseInline(match[7]);
      if (href) result.push({ type: 'link', href, children });
      else result.push(...children);
    }
    rest = rest.slice(match.index + match[0].length);
  }
  return result;
}

/**
 * Only absolute http(s) links survive.
 * @param {string} raw
 * @returns {string | null}
 */
export function safeHref(raw) {
  try {
    const url = new URL(raw);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

/**
 * Builds DOM for [blocks] in [document].
 * @param {Block[]} blocks
 * @param {Document} document
 * @returns {DocumentFragment}
 */
export function renderMarkdown(blocks, document) {
  const fragment = document.createDocumentFragment();
  for (const block of blocks) {
    switch (block.type) {
      case 'paragraph':
        fragment.append(inlineElement('p', block.children, document));
        break;
      case 'heading':
        fragment.append(inlineElement(block.level <= 2 ? 'h3' : 'h4', block.children, document));
        break;
      case 'quote':
        fragment.append(inlineElement('blockquote', block.children, document));
        break;
      case 'code': {
        const pre = document.createElement('pre');
        const code = document.createElement('code');
        code.textContent = block.text;
        if (block.language) code.dataset.language = block.language;
        pre.append(code);
        fragment.append(pre);
        break;
      }
      case 'list': {
        const list = document.createElement(block.ordered ? 'ol' : 'ul');
        for (const item of block.items) list.append(inlineElement('li', item, document));
        fragment.append(list);
        break;
      }
      default:
        break;
    }
  }
  return fragment;
}

/**
 * @param {string} tag
 * @param {Inline[]} children
 * @param {Document} document
 */
function inlineElement(tag, children, document) {
  const element = document.createElement(tag);
  for (const child of children) element.append(inlineNode(child, document));
  return element;
}

/**
 * @param {Inline} inline
 * @param {Document} document
 * @returns {Node}
 */
function inlineNode(inline, document) {
  switch (inline.type) {
    case 'text':
      return document.createTextNode(inline.text);
    case 'code': {
      const code = document.createElement('code');
      code.textContent = inline.text;
      return code;
    }
    case 'strong':
      return inlineElement('strong', inline.children, document);
    case 'em':
      return inlineElement('em', inline.children, document);
    case 'link': {
      const link = inlineElement('a', inline.children, document);
      link.setAttribute('href', inline.href);
      link.setAttribute('target', '_blank');
      link.setAttribute('rel', 'noopener noreferrer');
      return link;
    }
    default:
      return document.createTextNode('');
  }
}
