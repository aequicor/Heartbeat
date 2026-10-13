// @ts-check
import { pendingAttachmentKey, takeSelection } from '../background/context-menu.js';
import { capturePage } from '../shared/page-capture.js';
import { CALL_TIMEOUT_MS, ErrorCode } from '../shared/protocol.js';
import { createTranslator, errorMessage } from '../shared/strings.js';
import { icon } from './icons.js';
import { canSend, composerView, initialState, reduce, selectedChat, selectedPermissions } from './store.js';
import { renderChatList } from './views/chat-list.js';
import { createTranscriptRenderer } from './views/chat-view.js';
import { renderComposerContext, renderComposerLead, renderComposerTrail } from './views/composer.js';
import { h } from './views/dom.js';
import { createPermissionRenderer } from './views/permission-card.js';
import { statusNotice } from './views/status.js';

/** Pages a prompt can carry (`ChatWireLimits.MAX_PAGES`). */
const MAX_PAGES = 4;
/** The studio's `editorMaxHeight` on desktop. */
const PROMPT_MAX_HEIGHT = 168;
/** Where the panel remembers its model and approvals for new chats. */
const DRAFT_KEY = 'newChatDraft';

const t = createTranslator(chrome.i18n);
const port = chrome.runtime.connect({ name: 'heartbeat-panel' });
const panelWindow = chrome.windows.getCurrent();

/** @type {import('./store.js').PanelState} */
let state = initialState;
let nextCall = 0;
/** @type {Map<number, { resolve: (value: any) => void, reject: (error: { code: string }) => void }>} */
const calls = new Map();

/** @param {string} id */
const byId = (id) => /** @type {HTMLElement} */ (document.getElementById(id));
const prompt = /** @type {HTMLTextAreaElement} */ (byId('prompt'));
const searchField = /** @type {HTMLInputElement} */ (byId('search-field'));
const transcriptView = h(document, 'div');
const updateTranscript = createTranscriptRenderer(document, transcriptView, t);
const updatePermissions = createPermissionRenderer(document, byId('permissions'), t, respond);

/** @param {{ type: string, [key: string]: any }} action */
function dispatch(action) {
  const previous = state;
  state = reduce(state, action);
  if (state !== previous) render(previous);
}

/**
 * @param {string} method
 * @param {Record<string, any>} params
 * @returns {Promise<any>}
 */
function call(method, params) {
  const id = ++nextCall;
  return new Promise((resolve, reject) => {
    calls.set(id, { resolve, reject });
    setTimeout(() => {
      if (calls.delete(id)) reject({ code: ErrorCode.TIMEOUT });
    }, CALL_TIMEOUT_MS + 5_000);
    port.postMessage({ type: 'call', id, method, params });
  });
}

port.onMessage.addListener((/** @type {any} */ message) => {
  switch (message.type) {
    case 'status':
      dispatch({ type: 'status', status: message.status, features: message.features });
      break;
    case 'event':
      if (message.event === 'chats.changed') dispatch({ type: 'chats', ...message.params });
      if (message.event === 'chat.changed') {
        dispatch({ type: 'transcript', chatId: message.params.chatId, change: message.params });
      }
      break;
    case 'reply': {
      const pending = calls.get(message.id);
      if (!pending) break;
      calls.delete(message.id);
      if (message.error) pending.reject(message.error);
      else pending.resolve(message.result);
      break;
    }
    case 'agent':
      dispatch({ type: 'agent', isAllowed: message.isAllowed, controlled: message.controlled });
      break;
    case 'watchFailed':
      dispatch({ type: 'failed', code: message.error?.code ?? ErrorCode.INTERNAL });
      break;
    default:
      break;
  }
});

/** @param {string} chatId */
function open(chatId) {
  const chat = state.chats.find((it) => it.id === chatId);
  dispatch({ type: 'open', chatId });
  port.postMessage({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId } });
  prompt.focus();
  // Opening a chat reads it, as in the studio.
  if (chat?.unread) edit(chatId, 'unread', false);
}

/** @param {string | null} projectId */
function compose(projectId) {
  port.postMessage({ type: 'unwatch', key: 'chat' });
  dispatch({ type: 'compose', projectId });
  prompt.focus();
}

/**
 * @param {string} chatId
 * @param {'rename' | 'pin' | 'unread' | 'archive'} kind
 * @param {string | boolean} value
 */
async function edit(chatId, kind, value) {
  try {
    await call('chat.edit', kind === 'rename' ? { chatId, kind, title: value } : { chatId, kind, value });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

/** @param {Record<string, any>} change */
const sidebar = (change) => dispatch({ type: 'sidebar', ...change });

/**
 * @param {'model' | 'effort' | 'approval'} kind
 * @param {string | null} value
 */
async function configure(kind, value) {
  try {
    await call('chat.configure', { chatId: state.selected, kind, value });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

/** @param {string | null} effort */
async function chooseNewChatEffort(effort) {
  const model = composerView(state).model;
  if (!model) return;
  try {
    await call('composer.effort', { modelId: model.id, effort });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

/**
 * Keeps the panel's choice for new chats; the start page of the app is left as it is.
 * @param {{ modelId?: string, approval?: string }} change
 */
function chooseDraft(change) {
  dispatch({ type: 'draft', ...change });
  try {
    chrome.storage.local?.set({ [DRAFT_KEY]: state.draft })?.catch?.(() => {});
  } catch {
    // Remembering the choice is a convenience; the panel works without storage.
  }
}

/** The editor grows with its text up to the studio's limit. */
function fitPrompt() {
  if (!prompt.style) return;
  prompt.style.height = 'auto';
  prompt.style.height = `${Math.min(prompt.scrollHeight, PROMPT_MAX_HEIGHT)}px`;
}

const closeMenu = () => dispatch({ type: 'composerMenu', menu: null });

/** @type {import('./views/composer.js').ComposerContext} */
const composerContext = {
  t,
  canAttach: true,
  onMenu: (menu) => dispatch({ type: 'composerMenu', menu }),
  onProject: (projectId) => dispatch({ type: 'compose', projectId }),
  onTemplate: (text) => {
    closeMenu();
    prompt.value = prompt.value.trim() ? `${prompt.value.trimEnd()}\n${text}` : text;
    fitPrompt();
    prompt.focus();
    /** @type {HTMLButtonElement} */ (byId('send')).disabled = !canSend(state, prompt.value);
  },
  onApproval: (approval) => {
    closeMenu();
    if (composerView(state).isNew) chooseDraft({ approval });
    else configure('approval', approval);
  },
  onEffort: (effort) => {
    closeMenu();
    if (composerView(state).isNew) chooseNewChatEffort(effort);
    else configure('effort', effort);
  },
  onModel: (modelId) => {
    closeMenu();
    if (composerView(state).isNew) chooseDraft({ modelId });
    else configure('model', modelId);
  },
  onAttachPage: () => {
    closeMenu();
    attachPage();
  },
};

/** @type {import('./views/chat-list.js').ListContext} */
const listContext = {
  t,
  onOpen: open,
  onNewChat: compose,
  onToggle: (key) => sidebar({ change: 'toggle', key }),
  onMenu: (chatId) => sidebar({ change: 'menu', chatId }),
  onRename: (chatId) => sidebar({ change: 'rename', chatId }),
  onEdit: edit,
};
let renderedRenaming = /** @type {string | null} */ (null);

function back() {
  port.postMessage({ type: 'unwatch', key: 'chat' });
  dispatch({ type: 'back' });
}

async function send() {
  const text = prompt.value;
  if (!canSend(state, text)) return;
  const view = composerView(state);
  const params = state.isComposingNew
    ? {
      projectId: state.newChatProject,
      text,
      attachments: state.attachments,
      settings: view.model ? { modelId: view.model.id, approval: view.approval } : undefined,
    }
    : { chatId: state.selected, text, attachments: state.attachments };
  dispatch({ type: 'sending' });
  try {
    const result = await call('chat.send', params);
    prompt.value = '';
    fitPrompt();
    const chatId = String(result.chatId);
    const isNew = chatId !== state.selected;
    dispatch({ type: 'sent', chatId });
    if (isNew) port.postMessage({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId } });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

async function stop() {
  if (!state.selected) return;
  try {
    await call('chat.stop', { chatId: state.selected });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

/**
 * @param {import('./store.js').Permission} permission
 * @param {string} optionId
 * @param {Record<string, any>} [answer]
 */
async function respond(permission, optionId, answer) {
  try {
    await call('permission.respond', { chatId: permission.chatId, requestId: permission.requestId, optionId, answer });
  } catch (error) {
    dispatch({ type: 'failed', code: /** @type {any} */ (error).code ?? ErrorCode.INTERNAL });
  }
}

async function attachPage() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const page = tab?.id === undefined ? null : await capturePage(chrome, tab.id, tab.url);
    if (!page) throw new Error('Page cannot be attached');
    dispatch({ type: 'attach', attachment: page });
  } catch (error) {
    dispatch({ type: 'failed', code: 'capture' });
  }
}

/** Picks up a selection sent from the context menu; a listing panel starts a new chat with it. */
async function takePendingAttachment() {
  const { id } = await panelWindow;
  if (id === undefined) return;
  const page = await takeSelection(chrome, id);
  if (!page) return;
  if (!state.selected && !state.isComposingNew) dispatch({ type: 'compose', projectId: null });
  dispatch({ type: 'attach', attachment: page });
  prompt.focus();
}

/** @param {import('./store.js').PanelState} previous */
function render(previous) {
  const chat = selectedChat(state);
  const isListing = !state.selected && !state.isComposingNew;
  byId('title').textContent = chat?.title ?? (state.isComposingNew ? t('newChat') : t('extName'));
  byId('title').className = isListing ? 'brand' : '';
  document.body?.classList?.toggle('listing', isListing);
  byId('back').hidden = isListing;
  byId('back').setAttribute('aria-label', t('back'));
  byId('new-chat').setAttribute('aria-label', t('newChat'));
  byId('new-chat').title = t('newChat');
  byId('new-chat').hidden = state.isComposingNew;
  renderSearch(isListing);
  renderRail(isListing);

  const notice = statusNotice(state);
  const noticeBox = byId('notice');
  noticeBox.hidden = !notice;
  noticeBox.className = notice?.isProblem ? 'banner problem' : 'banner';
  noticeBox.textContent = notice ? t(notice.key) : '';
  byId('error').hidden = !state.error;
  byId('error-text').textContent = state.error ? t(errorMessage(state.error)) : '';
  byId('error-dismiss').textContent = t('dismiss');

  renderAgent();

  const content = byId('content');
  const wasAtBottom = content.scrollHeight - content.scrollTop - content.clientHeight < 24;
  if (isListing) {
    // An open title editor keeps its node (and focus) until it closes; list updates wait for it.
    const isEditing = state.sidebar.renaming !== null && state.sidebar.renaming === renderedRenaming;
    if (!isEditing || previous.sidebar !== state.sidebar) {
      content.replaceChildren(renderChatList(document, state, listContext));
      renderedRenaming = state.sidebar.renaming;
      if (renderedRenaming) /** @type {HTMLElement | null} */ (content.querySelector('input'))?.focus();
    }
  } else if (state.isComposingNew) {
    const project = state.projects.find((it) => it.id === state.newChatProject);
    content.replaceChildren(h(
      document,
      'div',
      { className: 'new-chat' },
      h(document, 'h2', { text: project ? t('newHeadingIn', project.name) : t('newHeading') }),
      h(document, 'p', { className: 'meta', text: t('newHint') }),
    ));
  } else if (state.transcript !== previous.transcript || state.selected !== previous.selected) {
    updateTranscript(state);
    if (content.firstElementChild !== transcriptView) content.replaceChildren(transcriptView);
    if (wasAtBottom || state.selected !== previous.selected) content.scrollTop = content.scrollHeight;
  }
  updatePermissions(state.selected ? selectedPermissions(state) : []);
  renderComposer(chat, isListing);
}

/**
 * @param {import('./store.js').Chat | null} chat
 * @param {boolean} isListing
 */
function renderComposer(chat, isListing) {
  byId('composer').hidden = isListing;
  if (isListing) return;
  const view = composerView(state);
  composerContext.canAttach = state.attachments.length < MAX_PAGES;
  byId('composer-context').replaceChildren(...renderComposerContext(document, state, composerContext));
  byId('composer-lead').replaceChildren(...renderComposerLead(document, state, view, composerContext));
  byId('composer-trail').replaceChildren(...renderComposerTrail(document, state, view, composerContext));
  prompt.placeholder = t('placeholder');
  const isClosed = Boolean(chat && !chat.continuable);
  const modelHint = view.models.length ? 'chooseModelHint' : 'connectModelHint';
  const hint = chat?.archived ? 'chatArchived' : isClosed ? 'chatClosed' : view.isNew && !view.model ? modelHint : '';
  byId('composer-hint').textContent = hint ? t(hint) : '';
  const send = byId('send');
  send.setAttribute('aria-label', t('send'));
  send.title = t('sendHint');
  send.hidden = Boolean(chat?.running);
  /** @type {HTMLButtonElement} */ (send).disabled = !canSend(state, prompt.value);
  const stop = byId('stop');
  stop.setAttribute('aria-label', t('stop'));
  stop.title = t('stop');
  stop.hidden = !chat?.running;
  renderAttachments();
}

/** @param {boolean} isListing */
function renderSearch(isListing) {
  const { isSearching } = state.sidebar;
  byId('search').hidden = !isListing;
  byId('search-row').hidden = isSearching;
  byId('search-box').hidden = !isSearching;
  byId('search-row').replaceChildren(icon(document, 'Search'), h(document, 'span', { text: t('searchSessions') }));
  searchField.placeholder = t('searchSessions');
  byId('search-clear').setAttribute('aria-label', t('clearSearch'));
  byId('search-clear').title = t('clearSearch');
}

/** @param {boolean} isListing */
function renderRail(isListing) {
  byId('rail').hidden = !isListing;
  for (const [id, mode, iconName, label] of /** @type {const} */ ([
    ['rail-sessions', 'sessions', 'Chats', 'railSessions'],
    ['rail-archive', 'archive', 'Archive', 'railArchive'],
  ])) {
    const item = byId(id);
    item.className = state.sidebar.mode === mode ? 'rail-item selected' : 'rail-item';
    item.setAttribute('aria-pressed', String(state.sidebar.mode === mode));
    item.replaceChildren(icon(document, iconName), h(document, 'span', { text: t(label) }));
  }
}

/** Consent to agent control and the tabs agents hold, when the app offers agent control. */
function renderAgent() {
  const isOffered = state.status === 'ready' && Boolean(state.features?.agentControl);
  byId('agent').hidden = !isOffered && state.agent.controlled === 0;
  const consent = /** @type {HTMLInputElement} */ (byId('consent'));
  consent.checked = state.agent.isAllowed;
  byId('consent-label').textContent = t('agentConsent');
  byId('consent-hint').textContent = t('agentConsentHint');
  byId('agent-tabs').hidden = state.agent.controlled === 0;
  byId('agent-count').textContent = t('agentControlling', state.agent.controlled);
  byId('revoke').textContent = t('revokeAll');
}

function renderAttachments() {
  byId('attachments').replaceChildren(
    ...state.attachments.map((attachment, index) =>
      h(
        document,
        'span',
        { className: 'chip' },
        h(document, 'span', { text: attachment.title || attachment.url, title: attachment.url }),
        h(
          document,
          'button',
          {
            className: 'icon',
            attrs: { type: 'button', 'aria-label': t('dismiss') },
            on: { click: () => dispatch({ type: 'detach', index }) },
          },
          icon(document, 'Close', 14),
        ),
      ),
    ),
  );
}

byId('back').replaceChildren(icon(document, 'ArrowLeft'));
byId('new-chat').replaceChildren(icon(document, 'Plus'));
byId('back').addEventListener('click', back);
byId('new-chat').addEventListener('click', () => compose(null));
byId('search-clear').replaceChildren(icon(document, 'Close', 14));
byId('search-row').addEventListener('click', () => {
  sidebar({ change: 'search', isSearching: true });
  searchField.focus();
});
byId('search-clear').addEventListener('click', () => {
  searchField.value = '';
  sidebar({ change: 'search', isSearching: false });
});
searchField.addEventListener('input', () => sidebar({ change: 'query', query: searchField.value }));
searchField.addEventListener('keydown', (/** @type {KeyboardEvent} */ event) => {
  if (event.key !== 'Escape') return;
  searchField.value = '';
  sidebar({ change: 'search', isSearching: false });
});
byId('rail-sessions').addEventListener('click', () => sidebar({ change: 'mode', mode: 'sessions' }));
byId('rail-archive').addEventListener('click', () => sidebar({ change: 'mode', mode: 'archive' }));
// Open menus close on a click elsewhere or Escape.
document.addEventListener('click', (/** @type {MouseEvent} */ event) => {
  const target = /** @type {Element | null} */ (event.target);
  if (state.sidebar.menu && !target?.closest?.('.menu, .row-actions')) sidebar({ change: 'menu', chatId: null });
  if (state.composerMenu && !target?.closest?.('.menu-anchor')) closeMenu();
});
document.addEventListener('keydown', (/** @type {KeyboardEvent} */ event) => {
  if (event.key !== 'Escape') return;
  if (state.sidebar.menu) sidebar({ change: 'menu', chatId: null });
  if (state.composerMenu) closeMenu();
});
(async () => {
  try {
    const stored = (await chrome.storage.local?.get(DRAFT_KEY))?.[DRAFT_KEY];
    if (stored) dispatch({ type: 'draft', modelId: stored.modelId ?? null, approval: stored.approval ?? null });
  } catch {
    // Without storage the start page's settings apply.
  }
})();
byId('send').replaceChildren(icon(document, 'ArrowUp'));
byId('stop').replaceChildren(icon(document, 'Stop'));
byId('send').addEventListener('click', send);
byId('stop').addEventListener('click', stop);
byId('error-dismiss').addEventListener('click', () => dispatch({ type: 'dismiss' }));
byId('consent').addEventListener('change', (event) => {
  port.postMessage({ type: 'consent', isAllowed: /** @type {HTMLInputElement} */ (event.target).checked });
});
byId('revoke').addEventListener('click', () => port.postMessage({ type: 'revokeTabs' }));
prompt.addEventListener('input', () => {
  fitPrompt();
  /** @type {HTMLButtonElement} */ (byId('send')).disabled = !canSend(state, prompt.value);
});
// As in the studio on desktop: Enter sends, Shift+Enter breaks the line, Ctrl/Cmd+Enter always sends.
prompt.addEventListener('keydown', (/** @type {KeyboardEvent} */ event) => {
  if (event.key !== 'Enter' || event.isComposing || event.altKey) return;
  if (event.shiftKey && !(event.ctrlKey || event.metaKey)) return;
  event.preventDefault();
  send();
});

chrome.storage.session.onChanged.addListener(async (/** @type {Record<string, unknown>} */ changes) => {
  const { id } = await panelWindow;
  if (id !== undefined && pendingAttachmentKey(id) in changes) await takePendingAttachment();
});

port.postMessage({ type: 'watch', key: 'chats', method: 'chats.watch', params: {} });
render(initialState);
takePendingAttachment();
