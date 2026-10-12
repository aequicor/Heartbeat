// @ts-check
import { applyTranscript } from './transcript.js';

/**
 * @typedef {{ id: string, title: string, projectId?: string | null, updatedAt: number, running: boolean,
 *   pinned: boolean, unread: boolean, continuable: boolean }} Chat
 * @typedef {{ id: string, name: string }} Project
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
      return { ...state, projects: action.projects ?? [], chats: action.chats ?? [], permissions: action.permissions ?? [] };
    case 'transcript':
      if (action.chatId !== state.selected) return state;
      return {
        ...state,
        transcript: applyTranscript(state.transcript, action.change),
        olderOmitted: action.change.olderOmitted ?? 0,
      };
    case 'open':
      return { ...state, selected: action.chatId, isComposingNew: false, transcript: [], olderOmitted: 0, error: null };
    case 'compose':
      return {
        ...state,
        selected: null,
        isComposingNew: true,
        newChatProject: action.projectId ?? null,
        transcript: [],
        olderOmitted: 0,
        error: null,
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
 * Chats grouped for the list: those without a project first, then each project in the app's order;
 * a filter matches titles case-insensitively.
 * @param {PanelState} state
 * @param {string} [filter]
 * @returns {{ project: Project | null, chats: Chat[] }[]}
 */
export function groupedChats(state, filter = '') {
  const needle = filter.trim().toLocaleLowerCase();
  const visible = needle ? state.chats.filter((chat) => chat.title.toLocaleLowerCase().includes(needle)) : state.chats;
  const groups = [{ project: null, chats: visible.filter((chat) => !chat.projectId) }];
  for (const project of state.projects) {
    groups.push({ project, chats: visible.filter((chat) => chat.projectId === project.id) });
  }
  return groups.filter((group) => group.chats.length > 0);
}

/**
 * Whether a prompt can be sent now.
 * @param {PanelState} state
 * @param {string} text
 */
export function canSend(state, text) {
  if (state.status !== 'ready' || !state.features?.chat || state.isSending || !text.trim()) return false;
  if (state.isComposingNew) return true;
  const chat = selectedChat(state);
  return Boolean(chat && chat.continuable && !chat.running);
}
