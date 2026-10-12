// @ts-check
import { h } from './dom.js';

/**
 * Permission requests of the open chat; answering sends the chosen option with the structured input.
 * @param {Document} document
 * @param {import('../store.js').Permission[]} permissions
 * @param {(key: string, ...args: any[]) => string} t
 * @param {(permission: import('../store.js').Permission, optionId: string, answer?: Record<string, any>) => void} onRespond
 */
export function renderPermissions(document, permissions, t, onRespond) {
  const container = h(document, 'div', { className: 'permissions' });
  for (const permission of permissions) container.append(renderPermission(document, permission, t, onRespond));
  return container;
}

/**
 * @param {Document} document
 * @param {import('../store.js').Permission} permission
 * @param {(key: string, ...args: any[]) => string} t
 * @param {(permission: import('../store.js').Permission, optionId: string, answer?: Record<string, any>) => void} onRespond
 */
function renderPermission(document, permission, t, onRespond) {
  const input = permission.input;
  /** @type {() => Record<string, any> | undefined} */
  let readAnswer = () => undefined;
  /** @type {HTMLElement | null} */
  let field = null;
  if (input?.kind === 'text') {
    const text = /** @type {HTMLTextAreaElement} */ (
      h(document, input.multiline ? 'textarea' : 'input', { attrs: { 'aria-label': t('permissionAnswer') } })
    );
    if (input.placeholder) text.placeholder = input.placeholder;
    field = text;
    readAnswer = () => ({ text: text.value });
  } else if (input?.kind === 'single' || input?.kind === 'multi') {
    const type = input.kind === 'single' ? 'radio' : 'checkbox';
    const name = `choice-${permission.requestId}`;
    field = h(document, 'fieldset', { className: 'choices' });
    for (const choice of input.choices ?? []) {
      const box = /** @type {HTMLInputElement} */ (h(document, 'input', { attrs: { type, name, value: choice.id } }));
      field.append(h(document, 'label', {}, box, choice.title));
    }
    const choices = field;
    readAnswer = () => ({
      selected: [...choices.querySelectorAll('input:checked')].map((it) => /** @type {HTMLInputElement} */ (it).value),
    });
  }
  const actions = h(document, 'div', { className: 'actions' });
  for (const option of permission.options) {
    actions.append(
      h(document, 'button', {
        className: option.skip ? 'secondary' : 'primary',
        text: option.title,
        attrs: { type: 'button' },
        on: { click: () => onRespond(permission, option.id, option.skip ? undefined : readAnswer()) },
      }),
    );
  }
  return h(
    document,
    'section',
    { className: 'permission', attrs: { 'aria-live': 'assertive' } },
    h(document, 'p', { className: 'permission-title', text: permission.title }),
    permission.description ? h(document, 'p', { className: 'meta', text: permission.description }) : null,
    field,
    actions,
  );
}
