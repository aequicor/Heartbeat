// @ts-check
import { applyTranscript } from './transcript.js';

/**
 * @typedef {{ modelId: string, effort?: string | null, approval?: string }} ChatSettings
 * @typedef {{ id: string, title: string, projectId?: string | null, updatedAt: number, running: boolean,
 *   pinned: boolean, unread: boolean, continuable: boolean, archived?: boolean, modelId?: string | null,
 *   settings?: ChatSettings | null, configuring?: boolean }} Chat
 * @typedef {{ id: string, name: string }} Project
 * @typedef {{ id: string, name: string, shortName: string, efforts?: string[], newChatEffort?: string | null,
 *   trust?: boolean, projects?: boolean, connection?: string | null }} Model
 * @typedef {{ models: Model[], newChat: ChatSettings | null }} Composer
 * @typedef {{ chatId: string, requestId: string, title: string, description?: string,
 *   options: { id: string, title: string, skip?: boolean }[], input?: Record<string, any> }} Permission
 * @typedef {{ kind: 'page', url: string, title: string, text: string, selection?: string }} PageAttachment
 * @typedef {object} PanelState
 * @property {string} status Link status from the service worker.
 * @property {{ chat?: boolean, agentControl?: boolean } | null} features
 * @property {Project[]} projects
 * @property {Chat[]} chats
 * @property {Permission[]} permissions
 * @property {string | null} selected Open chat id, or `null` for the chat list / a new chat.
 * @property {boolean} isComposingNew A new chat is being written.
 * @property {string | null} newChatProject Project of the new chat (`null`: without a project).
 * @property {import('./transcript.js').Entry[]} transcript Entries of the open chat.
 * @property {number} olderOmitted
 * @property {PageAttachment[]} attachments Pending attachments of the composer.
 * @property {boolean} isSending
 * @property {string | null} error Error code of the last failed action.
 * @property {{ isAllowed: boolean, controlled: number }} agent Consent to agent control and controlled tabs.
 * @property {Sidebar} sidebar How the chat list is shown.
 * @property {Composer} composer Models and the start page's settings of a new chat.
 * @property {{ modelId: string | null, approval: string | null }} draft The panel's own choice for new chats.
 * @property {string | null} composerMenu Open composer menu: `add`, `effort`, `model` or `project`.
 */

/**
 * The chat list like the studio's sidebar.
 * @typedef {object} Sidebar
 * @property {'sessions' | 'archive'} mode The rail's choice.
 * @property {boolean} isSearching The search field replaces the search row.
 * @property {string} query
 * @property {string[]} collapsed Collapsed sections (`pinned`, `projects`, `recent`) and projects (`project:<id>`).
 * @property {string | null} menu Chat whose action menu is open.
 * @property {string | null} renaming Chat whose title is being edited.
 */

/** @type {PanelState} */
export const initialState = Object.freeze({
  status: 'connecting',
  features: null,
  projects: [],
  chats: [],
  permissions: [],
  selected: null,
  isComposingNew: false,
  newChatProject: null,
  transcript: [],
  olderOmitted: 0,
  attachments: [],
  isSending: false,
  error: null,
  agent: { isAllowed: false, controlled: 0 },
  sidebar: { mode: 'sessions', isSearching: false, query: '', collapsed: [], menu: null, renaming: null },
  composer: { models: [], newChat: null },
  draft: { modelId: null, approval: null },
  composerMenu: null,
});

/**
 * Pure reducer of the side panel.
 * @param {PanelState} state
 * @param {{ type: string, [key: string]: any }} action
 * @returns {PanelState}
 */
export function reduce(state, action) {
  switch (action.type) {
    case 'status':
      return { ...state, status: action.status, features: action.features ?? null };
    case 'chats':
      return {
        ...state, projects: action.projects ?? [], chats: action.chats ?? [], permissions: action.permissions ?? [],
        composer: { models: action.composer?.models ?? [], newChat: action.composer?.newChat ?? null },
        newChatProject: (action.projects ?? []).some((/** @type {Project} */ it) => it.id === state.newChatProject)
          ? state.newChatProject : null,
      };
    case 'transcript':
      if (action.chatId !== state.selected) return state;
      return {
        ...state,
        transcript: applyTranscript(state.transcript, action.change),
        olderOmitted: action.change.olderOmitted ?? 0,
      };
    case 'open':
      return {
        ...state, selected: action.chatId, isComposingNew: false, transcript: [], olderOmitted: 0, error: null, composerMenu: null,
      };
    case 'compose':
      return {
        ...state,
        selected: null,
        isComposingNew: true,
        newChatProject: action.projectId ?? null,
        transcript: [],
        olderOmitted: 0,
        error: null,
        composerMenu: null,
      };
    case 'back':
      return { ...state, selected: null, isComposingNew: false, transcript: [], olderOmitted: 0 };
    case 'attach':
      return { ...state, attachments: [...state.attachments.filter((it) => it.url !== action.attachment.url), action.attachment] };
    case 'detach':
      return { ...state, attachments: state.attachments.filter((_, index) => index !== action.index) };
    case 'sending':
      return { ...state, isSending: true, error: null };
    case 'sent':
      return {
        ...state,
        isSending: false,
        attachments: [],
        selected: action.chatId,
        isComposingNew: false,
        transcript: state.selected === action.chatId ? state.transcript : [],
      };
    case 'failed':
      return { ...state, isSending: false, error: action.code };
    case 'dismiss':
      return { ...state, error: null };
    case 'agent':
      return { ...state, agent: { isAllowed: action.isAllowed === true, controlled: Number(action.controlled) || 0 } };
    case 'draft':
      return { ...state, draft: { ...state.draft, ...pick(action, ['modelId', 'approval']) } };
    case 'composerMenu':
      return { ...state, composerMenu: state.composerMenu === action.menu ? null : action.menu ?? null };
    case 'sidebar':
      return { ...state, sidebar: { ...state.sidebar, ...sidebarChange(state.sidebar, action) } };
    default:
      return state;
  }
}

/**
 * The open chat, if it is still listed.
 * @param {PanelState} state
 */
export function selectedChat(state) {
  return state.chats.find((chat) => chat.id === state.selected) ?? null;
}

/**
 * Permissions waiting in the open chat.
 * @param {PanelState} state
 */
export function selectedPermissions(state) {
  return state.permissions.filter((permission) => permission.chatId === state.selected);
}

/**
 * @param {Sidebar} sidebar
 * @param {{ [key: string]: any }} action
 * @returns {Partial<Sidebar>}
 */
function sidebarChange(sidebar, action) {
  switch (action.change) {
    case 'mode':
      return { mode: action.mode, menu: null, renaming: null };
    case 'search':
      return { isSearching: action.isSearching === true, query: action.isSearching ? sidebar.query : '' };
    case 'query':
      return { query: String(action.query ?? '') };
    case 'toggle':
      return {
        collapsed: sidebar.collapsed.includes(action.key)
          ? sidebar.collapsed.filter((it) => it !== action.key)
          : [...sidebar.collapsed, action.key],
      };
    case 'menu':
      return { menu: action.chatId ?? null, renaming: null };
    case 'rename':
      return { renaming: action.chatId ?? null, menu: null };
    default:
      return {};
  }
}

/**
 * @typedef {{ key: string, title: string, collapsible: boolean, collapsed: boolean, rows: Chat[],
 *   projects?: { project: Project, collapsed: boolean, rows: Chat[] }[], empty?: string }} SidebarSection
 */

/**
 * Sections of the chat list as the studio's sidebar arranges them, newest first in each: search results; the
 * archive; or «Закреплённые», «Проекты» (chats of expanded projects) and «Недавние» (chats without a project
 * and those of collapsed projects). Every chat appears once; titles are message keys.
 * @param {PanelState} state
 * @returns {SidebarSection[]}
 */
export function sidebarSections(state) {
  const { sidebar } = state;
  const byRecency = (/** @type {Chat[]} */ chats) => [...chats].sort((a, b) => b.updatedAt - a.updatedAt);
  const isArchive = sidebar.mode === 'archive';
  const shown = state.chats.filter((chat) => Boolean(chat.archived) === isArchive);
  const needle = sidebar.isSearching ? sidebar.query.trim().toLocaleLowerCase() : '';
  if (needle) {
    const rows = byRecency(shown.filter((chat) => chat.title.toLocaleLowerCase().includes(needle)));
    return [{ key: 'results', title: 'sectionResults', collapsible: false, collapsed: false, rows, empty: 'nothingFound' }];
  }
  if (isArchive) {
    return [{ key: 'archive', title: 'railArchive', collapsible: false, collapsed: false, rows: byRecency(shown), empty: 'archiveEmpty' }];
  }
  const isCollapsed = (/** @type {string} */ key) => sidebar.collapsed.includes(key);
  const pinned = shown.filter((chat) => chat.pinned);
  const unpinned = shown.filter((chat) => !chat.pinned);
  const projectsCollapsed = isCollapsed('projects');
  const projects = state.projects.map((project) => ({
    project,
    collapsed: isCollapsed(`project:${project.id}`),
    rows: byRecency(unpinned.filter((chat) => chat.projectId === project.id)),
  }));
  const expanded = new Set(projectsCollapsed ? [] : projects.filter((it) => !it.collapsed).map((it) => it.project.id));
  const sections = [];
  if (pinned.length) {
    sections.push({ key: 'pinned', title: 'sectionPinned', collapsible: true, collapsed: isCollapsed('pinned'), rows: byRecency(pinned) });
  }
  if (projects.length) {
    sections.push({ key: 'projects', title: 'sectionProjects', collapsible: true, collapsed: projectsCollapsed, rows: [], projects });
  }
  sections.push({
    key: 'recent',
    title: 'sectionRecent',
    collapsible: true,
    collapsed: isCollapsed('recent'),
    rows: byRecency(unpinned.filter((chat) => !chat.projectId || !expanded.has(chat.projectId))),
  });
  return sections;
}

/**
 * @param {{ [key: string]: any }} source
 * @param {string[]} keys
 */
function pick(source, keys) {
  return Object.fromEntries(keys.filter((key) => key in source).map((key) => [key, source[key]]));
}

/**
 * @typedef {{ isNew: boolean, model: Model | null, models: Model[], effort: string | null, approval: string,
 *   isLocked: boolean }} ComposerView
 */

/**
 * What the composer shows, as the studio decides it: a new chat runs on the panel's choice or the start page's
 * model (one that works in a project when the chat has one); an existing chat shows its confirmed settings and may
 * switch only within its connection. Pickers wait while a change is in flight and stay off
 * for archived chats and chats without a model.
 * @param {PanelState} state
 * @returns {ComposerView}
 */
export function composerView(state) {
  const { models, newChat } = state.composer;
  const chat = selectedChat(state);
  if (state.isComposingNew || !chat) {
    const projectId = state.newChatProject;
    const offered = models.filter((it) => !projectId || it.projects);
    const modelId = state.draft.modelId ?? newChat?.modelId ?? null;
    const model = offered.find((it) => it.id === modelId) ?? null;
    const approval = state.draft.approval ?? newChat?.approval ?? 'ask';
    return { isNew: true, model, models: offered, effort: model?.newChatEffort ?? null, approval, isLocked: false };
  }
  const modelId = chat.settings?.modelId ?? chat.modelId ?? null;
  const model = models.find((it) => it.id === modelId) ?? null;
  const connection = model?.connection;
  const offered = models.filter((it) => (!chat.projectId || it.projects) &&
    (it.id === modelId || (Boolean(connection) && it.connection === connection)));
  return {
    isNew: false,
    model,
    models: offered,
    effort: chat.settings?.effort ?? null,
    approval: chat.settings?.approval ?? newChat?.approval ?? 'ask',
    isLocked: Boolean(chat.configuring) || Boolean(chat.archived) || !modelId,
  };
}

/**
 * Whether a prompt can be sent now.
 * @param {PanelState} state
 * @param {string} text
 */
export function canSend(state, text) {
  if (state.status !== 'ready' || !state.features?.chat || state.isSending || !text.trim()) return false;
  if (state.isComposingNew) return composerView(state).model !== null;
  const chat = selectedChat(state);
  return Boolean(chat && chat.continuable && !chat.running && !chat.archived);
}
