// @ts-check
import { icon } from '../icons.js';
import { sidebarSections } from '../store.js';
import { h } from './dom.js';

/**
 * @typedef {object} ListContext
 * @property {(key: string, ...args: any[]) => string} t
 * @property {(chatId: string) => void} onOpen
 * @property {(projectId: string | null) => void} onNewChat
 * @property {(key: string) => void} onToggle Collapses or expands a section or a project.
 * @property {(chatId: string | null) => void} onMenu
 * @property {(chatId: string | null) => void} onRename Starts (or cancels with `null`) editing a title.
 * @property {(chatId: string, kind: 'rename' | 'pin' | 'unread' | 'archive', value: string | boolean) => void} onEdit
 */

/**
 * The chat list like the studio's sidebar: collapsible sections of 28px rows with a status slot that gives way to
 * the row's actions on hover or focus.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {ListContext} context
 */
export function renderChatList(document, state, context) {
  const { t } = context;
  const waiting = new Set(state.permissions.map((it) => it.chatId));
  const list = h(document, 'div', { className: 'session-list', attrs: { role: 'list' } });
  for (const section of sidebarSections(state)) {
    list.append(sectionHeader(document, section, context));
    if (section.collapsed) continue;
    for (const group of section.projects ?? []) {
      list.append(projectRow(document, group.project, group.collapsed, context));
      if (group.collapsed) continue;
      if (!group.rows.length) list.append(h(document, 'p', { className: 'empty-note nested', text: t('noSessions') }));
      for (const chat of group.rows) list.append(sessionRow(document, chat, 1, waiting.has(chat.id), state, context));
    }
    for (const chat of section.rows) list.append(sessionRow(document, chat, 0, waiting.has(chat.id), state, context));
    if (!section.rows.length && !section.projects && section.empty) {
      list.append(h(document, 'p', { className: 'empty-note', text: t(section.empty) }));
    }
  }
  return list;
}

/**
 * @param {Document} document
 * @param {import('../store.js').SidebarSection} section
 * @param {ListContext} context
 */
function sectionHeader(document, section, context) {
  const title = context.t(section.title);
  if (!section.collapsible) return h(document, 'h2', { className: 'section', text: title });
  return h(
    document,
    'button',
    {
      className: section.collapsed ? 'section collapsed' : 'section',
      attrs: { type: 'button', 'aria-expanded': String(!section.collapsed) },
      on: { click: () => context.onToggle(section.key) },
    },
    h(document, 'span', { text: title }),
    icon(document, section.collapsed ? 'ChevronRight' : 'ChevronUp', 14),
  );
}

/**
 * @param {Document} document
 * @param {import('../store.js').Project} project
 * @param {boolean} collapsed
 * @param {ListContext} context
 */
function projectRow(document, project, collapsed, context) {
  const { t } = context;
  return h(
    document,
    'div',
    { className: 'row project' },
    h(
      document,
      'button',
      {
        className: 'row-main',
        attrs: { type: 'button', 'aria-expanded': String(!collapsed) },
        on: { click: () => context.onToggle(`project:${project.id}`) },
      },
      icon(document, collapsed ? 'Folder' : 'FolderOpen'),
      h(document, 'span', { className: 'row-title', text: project.name, title: project.name }),
    ),
    h(
      document,
      'span',
      { className: 'row-actions' },
      h(
        document,
        'button',
        {
          className: 'icon',
          title: t('newSessionIn', project.name),
          attrs: { type: 'button', 'aria-label': t('newSessionIn', project.name) },
          on: { click: () => context.onNewChat(project.id) },
        },
        icon(document, 'Plus'),
      ),
    ),
  );
}

/**
 * @param {Document} document
 * @param {import('../store.js').Chat} chat
 * @param {number} level
 * @param {boolean} isWaiting
 * @param {import('../store.js').PanelState} state
 * @param {ListContext} context
 */
function sessionRow(document, chat, level, isWaiting, state, context) {
  const { t } = context;
  const isMenuOpen = state.sidebar.menu === chat.id;
  const isRenaming = state.sidebar.renaming === chat.id;
  const status = statusSlot(document, chat, isWaiting, t);
  const classes = ['row', 'session', level ? 'nested' : '', isMenuOpen ? 'menu-open' : '', chat.unread ? 'unread' : ''];
  const row = h(document, 'div', {
    className: classes.filter(Boolean).join(' '),
    attrs: { role: 'listitem', 'data-chat': chat.id },
    on: {
      contextmenu: (/** @type {Event} */ event) => {
        event.preventDefault();
        context.onMenu(chat.id);
      },
    },
  });
  if (isRenaming) {
    row.append(renameField(document, chat, context));
    return row;
  }
  row.append(
    h(
      document,
      'button',
      {
        className: 'row-main',
        title: chat.title,
        attrs: { type: 'button' },
        on: { click: () => context.onOpen(chat.id) },
      },
      h(document, 'span', { className: 'row-title', text: chat.title }),
    ),
    status ?? '',
    h(
      document,
      'span',
      { className: 'row-actions' },
      actionButton(document, chat.archived ? 'Undo' : 'Archive', t(chat.archived ? 'restore' : 'archive'), () =>
        context.onEdit(chat.id, 'archive', !chat.archived)),
      actionButton(document, 'More', t('sessionActions'), () => context.onMenu(isMenuOpen ? null : chat.id)),
    ),
  );
  if (isMenuOpen) row.append(sessionMenu(document, chat, context));
  return row;
}

/**
 * The first matching state, as in the studio: waiting for permission, running, unread, pinned.
 * @param {Document} document
 * @param {import('../store.js').Chat} chat
 * @param {boolean} isWaiting
 * @param {(key: string) => string} t
 */
function statusSlot(document, chat, isWaiting, t) {
  if (isWaiting) {
    return h(document, 'span', { className: 'status sticky accent', title: t('awaitingPermission') }, icon(document, 'Lock', 14));
  }
  if (chat.running) {
    return h(document, 'span', { className: 'status sticky', title: t('agentWorking') }, h(document, 'span', { className: 'spinner' }));
  }
  if (chat.unread) return h(document, 'span', { className: 'status', title: t('unread') }, h(document, 'span', { className: 'dot' }));
  if (chat.pinned) return h(document, 'span', { className: 'status' }, icon(document, 'Pin', 14));
  return null;
}

/**
 * @param {Document} document
 * @param {keyof typeof import('../icons.js').ICONS} name
 * @param {string} label
 * @param {() => void} onClick
 */
function actionButton(document, name, label, onClick) {
  return h(
    document,
    'button',
    { className: 'icon', title: label, attrs: { type: 'button', 'aria-label': label }, on: { click: onClick } },
    icon(document, name),
  );
}

/**
 * @param {Document} document
 * @param {import('../store.js').Chat} chat
 * @param {ListContext} context
 */
function sessionMenu(document, chat, context) {
  const { t } = context;
  /** @type {[string, () => void][]} */
  const items = [[t('rename'), () => context.onRename(chat.id)]];
  if (!chat.archived) items.push([t(chat.pinned ? 'unpin' : 'pin'), () => context.onEdit(chat.id, 'pin', !chat.pinned)]);
  items.push([t(chat.unread ? 'markRead' : 'markUnread'), () => context.onEdit(chat.id, 'unread', !chat.unread)]);
  const menu = h(document, 'div', { className: 'menu', attrs: { role: 'menu' } });
  for (const [label, action] of items) menu.append(menuItem(document, label, action, context));
  menu.append(h(document, 'hr'));
  menu.append(menuItem(document, t(chat.archived ? 'restore' : 'archive'), () =>
    context.onEdit(chat.id, 'archive', !chat.archived), context));
  return menu;
}

/**
 * @param {Document} document
 * @param {string} label
 * @param {() => void} action
 * @param {ListContext} context
 */
function menuItem(document, label, action, context) {
  return h(document, 'button', {
    className: 'menu-item',
    text: label,
    attrs: { type: 'button', role: 'menuitem' },
    on: {
      click: () => {
        context.onMenu(null);
        action();
      },
    },
  });
}

/**
 * Inline title editor: Enter or leaving commits a changed title, Escape cancels.
 * @param {Document} document
 * @param {import('../store.js').Chat} chat
 * @param {ListContext} context
 */
function renameField(document, chat, context) {
  const field = /** @type {HTMLInputElement} */ (h(document, 'input', {
    className: 'rename',
    attrs: { type: 'text', 'aria-label': context.t('sessionTitle'), maxlength: '512' },
  }));
  field.value = chat.title;
  let isDone = false;
  const finish = (/** @type {boolean} */ isCommitted) => {
    if (isDone) return;
    isDone = true;
    const title = field.value.trim();
    context.onRename(null);
    if (isCommitted && title && title !== chat.title) context.onEdit(chat.id, 'rename', title);
  };
  field.addEventListener('keydown', (/** @type {KeyboardEvent} */ event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      finish(true);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      finish(false);
    }
  });
  field.addEventListener('blur', () => finish(true));
  return field;
}
