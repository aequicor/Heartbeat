// @ts-check
import { parseMarkdown, renderMarkdown } from '../markdown.js';
import { h } from './dom.js';

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
  const container = h(document, 'div', { className: 'transcript', attrs: { role: 'log', 'aria-live': 'polite' } });
  if (state.olderOmitted > 0) {
    container.append(h(document, 'p', { className: 'notice', text: t('olderOmitted', state.olderOmitted) }));
  }
  for (const entry of state.transcript) container.append(renderEntry(document, entry, t));
  return container;
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
 */
function renderReply(document, entry, t) {
  const reply = h(document, 'div', { className: entry.streaming ? 'reply streaming' : 'reply' });
  for (const part of entry.parts ?? []) {
    if (part.type === 'text') {
      reply.append(h(document, 'div', { className: 'prose' }, renderMarkdown(parseMarkdown(part.text ?? ''), document)));
    } else if (part.type === 'reasoning') {
      reply.append(
        h(
          document,
          'details',
          { className: 'reasoning' },
          h(document, 'summary', { text: t('reasoning') }),
          h(document, 'div', { className: 'prose' }, renderMarkdown(parseMarkdown(part.text ?? ''), document)),
        ),
      );
    } else if (part.type === 'tool') {
      const status = t(/** @type {Record<string, string>} */ (TOOL_STATUS)[part.status] ?? 'toolRunning');
      reply.append(
        h(
          document,
          'details',
          { className: `tool ${part.status ?? ''}` },
          h(document, 'summary', {}, h(document, 'span', { text: part.title ?? '' }), h(document, 'span', { className: 'meta', text: status })),
          part.output ? h(document, 'pre', { text: part.output }) : null,
        ),
      );
    }
    if (part.truncated) reply.append(h(document, 'p', { className: 'meta', text: t('truncated') }));
  }
  return reply;
}
