// @ts-check
import { groupedChats } from '../store.js';
import { h, relativeTime } from './dom.js';

/**
 * The chat list grouped by project.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {{ t: (key: string, ...args: any[]) => string, filter: string, now: number, locale: string,
 *   onOpen: (chatId: string) => void }} context
 */
export function renderChatList(document, state, context) {
  const { t } = context;
  const groups = groupedChats(state, context.filter);
  if (!groups.length) return h(document, 'p', { className: 'empty', text: t('noChats') });
  const list = h(document, 'div', { className: 'chat-list', attrs: { role: 'list' } });
  for (const group of groups) {
    list.append(h(document, 'h2', { className: 'group', text: group.project?.name ?? t('withoutProject') }));
    for (const chat of group.chats) {
      const row = h(
        document,
        'button',
        {
          className: 'chat-row',
          attrs: { type: 'button', role: 'listitem', 'data-chat': chat.id },
          on: { click: () => context.onOpen(chat.id) },
        },
        h(document, 'span', { className: 'chat-title', text: chat.title }),
        chat.running ? h(document, 'span', { className: 'badge running', text: t('running') }) : null,
        chat.unread ? h(document, 'span', { className: 'badge', text: t('unread') }) : null,
        h(document, 'span', { className: 'chat-time', text: relativeTime(chat.updatedAt, context.now, context.locale) }),
      );
      list.append(row);
    }
  }
  return list;
}
