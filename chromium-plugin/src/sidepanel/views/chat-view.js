// @ts-check
import { parseMarkdown, renderMarkdown } from '../markdown.js';
import { h, reconcileChildren } from './dom.js';

const TOOL_STATUS = Object.freeze({
  pending: 'toolPending',
  running: 'toolRunning',
  done: 'toolDone',
  failed: 'toolFailed',
  cancelled: 'toolCancelled',
});

const NOTICES = Object.freeze({
  stopped: 'noticeStopped',
  failed: 'noticeFailed',
  compacted: 'noticeCompacted',
  rolled_over: 'noticeRolledOver',
});

/**
 * The transcript of the open chat.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {(key: string, ...args: any[]) => string} t
 */
export function renderTranscript(document, state, t) {
  const container = h(document, 'div');
  createTranscriptRenderer(document, container, t)(state);
  return container;
}

/**
 * Updates entries in place so tool/reasoning details stay open and keyboard focus survives streaming.
 * @param {Document} document
 * @param {HTMLElement} container
 * @param {(key: string, ...args: any[]) => string} t
 */
export function createTranscriptRenderer(document, container, t) {
  container.className = 'transcript';
  container.setAttribute('role', 'log');
  container.setAttribute('aria-live', 'polite');
  /** @type {Map<string, { signature: string, type: string, node: HTMLElement }>} */
  const entries = new Map();
  const omitted = h(document, 'p', { className: 'notice' });
  let chatId = /** @type {string | null} */ (null);
  return (/** @type {import('../store.js').PanelState} */ state) => {
    if (chatId !== state.selected) entries.clear();
    chatId = state.selected;
    const keep = new Set(state.transcript.map((it) => it.id));
    for (const key of entries.keys()) if (!keep.has(key)) entries.delete(key);
    const children = state.transcript.map((entry) => {
      const signature = JSON.stringify(entry);
      let previous = entries.get(entry.id);
      if (previous?.signature !== signature) {
        const node = entry.type === 'reply'
          ? renderReply(document, entry, t, previous?.type === 'reply' ? previous.node : undefined)
          : renderEntry(document, entry, t);
        previous = { signature, type: entry.type, node };
        entries.set(entry.id, previous);
      }
      return previous.node;
    });
    if (state.olderOmitted > 0) {
      omitted.textContent = t('olderOmitted', state.olderOmitted);
      children.unshift(omitted);
    }
    reconcileChildren(container, children);
  };
}

/**
 * @param {Document} document
 * @param {import('../transcript.js').Entry} entry
 * @param {(key: string, ...args: any[]) => string} t
 */
function renderEntry(document, entry, t) {
  switch (entry.type) {
    case 'prompt':
      return h(
        document,
        'div',
        { className: 'prompt' },
        h(document, 'p', { text: entry.text ?? '' }),
        entry.attachments ? h(document, 'p', { className: 'meta', text: t('attachments', entry.attachments) }) : null,
        entry.truncated ? h(document, 'p', { className: 'meta', text: t('truncated') }) : null,
      );
    case 'reply':
      return renderReply(document, entry, t);
    default:
      return h(document, 'p', {
        className: 'notice',
        text: t(/** @type {Record<string, string>} */ (NOTICES)[entry.notice] ?? 'noticeFailed'),
      });
  }
}

/**
 * @param {Document} document
 * @param {import('../transcript.js').Entry} entry
 * @param {(key: string, ...args: any[]) => string} t
 * @param {HTMLElement} [existing]
 */
function renderReply(document, entry, t, existing) {
  const reply = existing ?? h(document, 'div');
  reply.className = entry.streaming ? 'reply streaming' : 'reply';
  const details = new Map([...reply.querySelectorAll('details')].map((it) => [it.dataset.partKey, it]));
  /** @type {HTMLElement[]} */
  const children = [];
  if (entry.truncated) children.push(h(document, 'p', { className: 'meta', text: t('truncated') }));
  for (const [index, part] of (entry.parts ?? []).entries()) {
    if (part.type === 'text') {
      children.push(h(document, 'div', { className: 'prose' }, renderMarkdown(parseMarkdown(part.text ?? ''), document)));
    } else if (part.type === 'reasoning' || part.type === 'tool') {
      const key = part.type === 'tool' ? `tool:${part.id}` : `reasoning:${index}`;
      const detail = details.get(key) ?? /** @type {HTMLDetailsElement} */ (h(document, 'details'));
      detail.dataset.partKey = key;
      detail.className = part.type === 'tool' ? `tool ${part.status ?? ''}` : 'reasoning';
      const summary = detail.querySelector('summary') ?? h(document, 'summary');
      summary.textContent = part.type === 'tool'
        ? `${part.title ?? ''} — ${t(/** @type {Record<string, string>} */ (TOOL_STATUS)[part.status] ?? 'toolRunning')}`
        : t('reasoning');
      const body = part.type === 'tool'
        ? h(document, 'pre', { text: part.output ?? '' })
        : h(document, 'div', { className: 'prose' }, renderMarkdown(parseMarkdown(part.text ?? ''), document));
      reconcileChildren(detail, [summary, body]);
      children.push(detail);
    }
    if (part.truncated) children.push(h(document, 'p', { className: 'meta', text: t('truncated') }));
  }
  reconcileChildren(reply, children);
  return reply;
}
