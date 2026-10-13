// @ts-check
import { icon } from '../icons.js';
import { h } from './dom.js';

/** Prompt templates of the studio's «+» menu: label key and the text it appends to the draft. */
export const TEMPLATES = Object.freeze([
  ['templatePlan', 'templatePlanPrompt'],
  ['templateTests', 'templateTestsPrompt'],
  ['templateReview', 'templateReviewPrompt'],
]);

/** Approvals of the studio's «+» menu, by their wire names. */
const APPROVALS = Object.freeze([
  ['ask', 'approvalAsk'],
  ['auto_edits', 'approvalEdits'],
  ['auto_approve', 'approvalAuto'],
]);

/**
 * @typedef {object} ComposerContext
 * @property {(key: string, ...args: any[]) => string} t
 * @property {(menu: string) => void} onMenu Opens or closes a menu.
 * @property {(projectId: string | null) => void} onProject
 * @property {(text: string) => void} onTemplate
 * @property {(approval: string) => void} onApproval
 * @property {(effort: string | null) => void} onEffort
 * @property {(modelId: string) => void} onModel
 * @property {() => void} onAttachPage
 * @property {boolean} canAttach
 */

/**
 * The row above the composer box: the project of a new chat as a pill with its menu, or an existing chat's
 * project as a caption.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {ComposerContext} context
 */
export function renderComposerContext(document, state, context) {
  const { t } = context;
  const projectOf = (/** @type {string | null | undefined} */ id) => state.projects.find((it) => it.id === id) ?? null;
  if (!state.isComposingNew) {
    const chat = state.chats.find((it) => it.id === state.selected);
    const project = projectOf(chat?.projectId);
    return project ? [h(document, 'span', { className: 'meta', text: project.name })] : [];
  }
  const current = projectOf(state.newChatProject);
  const choices = [
    ...state.projects.map((it) => ({ id: it.id, label: it.name })),
    { id: null, label: t('withoutProject') },
  ];
  return [
    menuButton(document, 'project', current?.name ?? t('withoutProject'), 'Folder', state, context, choices.map((it) =>
      menuItem(document, it.label, it.id === (current?.id ?? null), () => context.onProject(it.id))), t('project')),
  ];
}

/**
 * Leading toolbar controls: the «+» menu (templates and, when the model applies them, approvals) and attaching the
 * current page.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {import('../store.js').ComposerView} view
 * @param {ComposerContext} context
 */
export function renderComposerLead(document, state, view, context) {
  const { t } = context;
  const items = TEMPLATES.map(([label, text]) => menuItem(document, t(label), false, () => context.onTemplate(t(text))));
  if (view.model?.trust) {
    items.push(h(document, 'p', { className: 'menu-section', text: t('approvalMenu') }));
    for (const [approval, label] of APPROVALS) {
      items.push(menuItem(document, t(label), view.approval === approval, () => context.onApproval(approval), view.isLocked));
    }
  }
  const add = menuButton(document, 'add', '', 'Plus', state, context, items, t('composerAdd'));
  const attach = h(
    document,
    'button',
    {
      className: 'icon',
      title: t('attachPage'),
      attrs: { type: 'button', 'aria-label': t('attachPage') },
      on: { click: context.onAttachPage },
    },
    icon(document, 'FileText'),
  );
  /** @type {HTMLButtonElement} */ (attach).disabled = !context.canAttach;
  return [add, attach];
}

/**
 * Trailing toolbar controls: the reasoning effort (when the model has levels) and the model.
 * @param {Document} document
 * @param {import('../store.js').PanelState} state
 * @param {import('../store.js').ComposerView} view
 * @param {ComposerContext} context
 */
export function renderComposerTrail(document, state, view, context) {
  const { t } = context;
  const controls = [];
  const efforts = view.model?.efforts ?? [];
  if (view.model && efforts.length) {
    const levels = [null, ...efforts];
    controls.push(menuButton(document, 'effort', view.effort ?? t('effortAuto'), 'Sparkles', state, context, [
      h(document, 'p', { className: 'menu-section', text: t('effortMenu') }),
      ...levels.map((level) => menuItem(document, level ?? t('effortAuto'), level === view.effort, () =>
        context.onEffort(level))),
    ], t('effortMenu'), view.isLocked, true));
  }
  const models = view.models.map((model) =>
    menuItem(document, model.name, model.id === view.model?.id, () => context.onModel(model.id)));
  controls.push(menuButton(document, 'model', view.model?.shortName ?? t('modelNotSelected'), 'Layers', state, context, [
    h(document, 'p', { className: 'menu-section', text: t('modelMenu') }),
    ...models,
  ], t('modelMenu'), view.isLocked || (!view.isNew && models.length < 2)));
  return controls;
}

/**
 * A pill (or a square icon button when [label] is empty) with its menu opening above the composer.
 * @param {Document} document
 * @param {string} key
 * @param {string} label
 * @param {keyof typeof import('../icons.js').ICONS} iconName
 * @param {import('../store.js').PanelState} state
 * @param {ComposerContext} context
 * @param {HTMLElement[]} items
 * @param {string} accessibleLabel
 * @param {boolean} [isDisabled]
 * @param {boolean} [isAccent]
 */
function menuButton(document, key, label, iconName, state, context, items, accessibleLabel, isDisabled = false, isAccent = false) {
  const isOpen = state.composerMenu === key && !isDisabled;
  const button = h(
    document,
    'button',
    {
      className: ['pill', isAccent ? 'accent' : '', label ? '' : 'square'].filter(Boolean).join(' '),
      title: accessibleLabel,
      attrs: { type: 'button', 'aria-haspopup': 'menu', 'aria-expanded': String(isOpen), 'aria-label': accessibleLabel },
      on: { click: () => context.onMenu(key) },
    },
    icon(document, iconName, label ? 14 : 18),
    label ? h(document, 'span', { className: 'pill-label', text: label }) : null,
    label ? icon(document, 'ChevronDown', 14) : null,
  );
  /** @type {HTMLButtonElement} */ (button).disabled = isDisabled;
  const wrapper = h(document, 'span', { className: 'menu-anchor', attrs: { 'data-menu': key } }, button);
  if (isOpen) wrapper.append(h(document, 'div', { className: 'menu up', attrs: { role: 'menu' } }, ...items));
  return wrapper;
}

/**
 * @param {Document} document
 * @param {string} label
 * @param {boolean} isSelected
 * @param {() => void} onSelect
 * @param {boolean} [isDisabled]
 */
function menuItem(document, label, isSelected, onSelect, isDisabled = false) {
  const item = h(
    document,
    'button',
    {
      className: isSelected ? 'menu-item selected' : 'menu-item',
      attrs: { type: 'button', role: 'menuitemradio', 'aria-checked': String(isSelected) },
      on: { click: onSelect },
    },
    h(document, 'span', { text: label }),
    isSelected ? icon(document, 'Check', 14) : null,
  );
  /** @type {HTMLButtonElement} */ (item).disabled = isDisabled;
  return item;
}
