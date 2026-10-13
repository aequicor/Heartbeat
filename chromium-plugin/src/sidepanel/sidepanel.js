// @ts-check
import { pendingAttachmentKey, takeSelection } from '../background/context-menu.js';
import { capturePage } from '../shared/page-capture.js';
import { CALL_TIMEOUT_MS, ErrorCode } from '../shared/protocol.js';
import { createTranslator, errorMessage } from '../shared/strings.js';
import { canSend, initialState, reduce, selectedChat, selectedPermissions } from './store.js';
import { renderChatList } from './views/chat-list.js';
import { createTranscriptRenderer } from './views/chat-view.js';
import { h } from './views/dom.js';
import { createPermissionRenderer } from './views/permission-card.js';
import { statusNotice } from './views/status.js';

/** Pages a prompt can carry (`ChatWireLimits.MAX_PAGES`). */
const MAX_PAGES = 4;

const t = createTranslator(chrome.i18n);
const locale = chrome.i18n.getUILanguage();
const port = chrome.runtime.connect({ name: 'heartbeat-panel' });
const panelWindow = chrome.windows.getCurrent();

/** @type {import('./store.js').PanelState} */
let state = initialState;
let filter = '';
let nextCall = 0;
/** @type {Map<number, { resolve: (value: any) => void, reject: (error: { code: string }) => void }>} */
const calls = new Map();

/** @param {string} id */
const byId = (id) => /** @type {HTMLElement} */ (document.getElementById(id));
const prompt = /** @type {HTMLTextAreaElement} */ (byId('prompt'));
const project = /** @type {HTMLSelectElement} */ (byId('project'));
const filterInput = /** @type {HTMLInputElement} */ (byId('filter'));
const transcriptView = h(document, 'div');
const updateTranscript = createTranscriptRenderer(document, transcriptView, t);
const updatePermissions = createPermissionRenderer(document, byId('permissions'), t, respond);
let renderedProjects = '';

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
  dispatch({ type: 'open', chatId });
  port.postMessage({ type: 'watch', key: 'chat', method: 'chat.watch', params: { chatId } });
  prompt.focus();
}

function back() {
  port.postMessage({ type: 'unwatch', key: 'chat' });
  dispatch({ type: 'back' });
}

async function send() {
  const text = prompt.value;
  if (!canSend(state, text)) return;
  const params = state.isComposingNew
    ? { projectId: state.newChatProject, text, attachments: state.attachments }
    : { chatId: state.selected, text, attachments: state.attachments };
  dispatch({ type: 'sending' });
  try {
    const result = await call('chat.send', params);
    prompt.value = '';
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
  byId('title').textContent = chat?.title ?? (state.isComposingNew ? t('newChat') : t('chats'));
  byId('back').hidden = isListing;
  byId('back').setAttribute('aria-label', t('back'));
  byId('new-chat').textContent = t('newChat');
  byId('new-chat').hidden = state.isComposingNew;
  filterInput.hidden = !isListing;
  filterInput.placeholder = t('search');

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
    content.replaceChildren(renderChatList(document, state, { t, filter, now: Date.now(), locale, onOpen: open }));
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
  project.hidden = !state.isComposingNew;
  const projectsSignature = JSON.stringify(state.projects);
  if (state.isComposingNew && projectsSignature !== renderedProjects) {
    renderedProjects = projectsSignature;
    project.replaceChildren(
      h(document, 'option', { text: t('withoutProject'), attrs: { value: '' } }),
      ...state.projects.map((it) => h(document, 'option', { text: it.name, attrs: { value: it.id } })),
    );
  }
  if (state.isComposingNew) project.value = state.newChatProject ?? '';
  project.setAttribute('aria-label', t('project'));
  prompt.placeholder = t(state.isComposingNew ? 'placeholderNew' : 'placeholderChat');
  const isClosed = Boolean(chat && !chat.continuable);
  byId('composer-hint').textContent = isClosed ? t('chatClosed') : '';
  const send = byId('send');
  send.textContent = t('send');
  send.title = t('sendHint');
  /** @type {HTMLButtonElement} */ (send).disabled = !canSend(state, prompt.value);
  const attach = byId('attach-page');
  attach.textContent = t('attachPage');
  /** @type {HTMLButtonElement} */ (attach).disabled = state.attachments.length >= MAX_PAGES;
  const stop = byId('stop');
  stop.textContent = t('stop');
  stop.hidden = !chat?.running;
  renderAttachments();
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
        h(document, 'button', {
          className: 'icon',
          text: '×',
          attrs: { type: 'button', 'aria-label': t('dismiss') },
          on: { click: () => dispatch({ type: 'detach', index }) },
        }),
      ),
    ),
  );
}

byId('back').addEventListener('click', back);
byId('new-chat').addEventListener('click', () => {
  port.postMessage({ type: 'unwatch', key: 'chat' });
  dispatch({ type: 'compose', projectId: null });
  prompt.focus();
});
byId('send').addEventListener('click', send);
byId('attach-page').addEventListener('click', attachPage);
byId('stop').addEventListener('click', stop);
byId('error-dismiss').addEventListener('click', () => dispatch({ type: 'dismiss' }));
byId('consent').addEventListener('change', (event) => {
  port.postMessage({ type: 'consent', isAllowed: /** @type {HTMLInputElement} */ (event.target).checked });
});
byId('revoke').addEventListener('click', () => port.postMessage({ type: 'revokeTabs' }));
project.addEventListener('change', () => dispatch({ type: 'compose', projectId: project.value || null }));
filterInput.addEventListener('input', () => {
  filter = filterInput.value;
  render(state);
});
prompt.addEventListener('input', () => {
  /** @type {HTMLButtonElement} */ (byId('send')).disabled = !canSend(state, prompt.value);
});
prompt.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    send();
  }
});

chrome.storage.session.onChanged.addListener(async (/** @type {Record<string, unknown>} */ changes) => {
  const { id } = await panelWindow;
  if (id !== undefined && pendingAttachmentKey(id) in changes) await takePendingAttachment();
});

port.postMessage({ type: 'watch', key: 'chats', method: 'chats.watch', params: {} });
render(initialState);
takePendingAttachment();
