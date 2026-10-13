// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { onSelectionMenu, pendingAttachmentKey, takeSelection, SELECTION_MENU } from '../src/background/context-menu.js';
import { fakeLocks } from './fakes/locks.js';
import { capturePage, normalizePage, originPattern } from '../src/shared/page-capture.js';

test('captured pages are bounded and tidied', () => {
  const page = normalizePage({
    url: 'https://example.com/a',
    title: '  Docs \n Title ',
    selection: '',
    text: `Line  \n\n\n\nNext${'x'.repeat(300_000)}`,
  });

  assert.equal(page?.title, 'Docs Title');
  assert.equal(page?.text.startsWith('Line\n\nNext'), true);
  assert.equal(page?.text.length, 200_000);
  assert.equal('selection' in (page ?? {}), false);
});

test('only http(s) pages can be attached', () => {
  assert.equal(normalizePage({ url: 'chrome://settings', title: 'x' }), null);
  assert.equal(normalizePage({ url: 'file:///etc/hosts' }), null);
  assert.equal(normalizePage(undefined), null);
  assert.equal(originPattern('https://a.example.com:8443/x?y'), 'https://a.example.com:8443/*');
  assert.equal(originPattern('chrome-extension://abc/'), null);
});

test('a refused script asks for the page origin and retries', async () => {
  let attempts = 0;
  /** @type {any[]} */
  const requested = [];
  const chrome = {
    scripting: {
      executeScript: async () => {
        if (attempts++ === 0) throw new Error('Cannot access contents of the page');
        return [{ result: { url: 'https://example.com/', title: 'Example', text: 'Body', selection: 'Sel' } }];
      },
    },
    permissions: { request: async (/** @type {any} */ request) => requested.push(request) > 0 },
  };

  const page = await capturePage(chrome, 7, 'https://example.com/');

  assert.deepEqual(requested, [{ origins: ['https://example.com/*'] }]);
  assert.deepEqual(page, { kind: 'page', url: 'https://example.com/', title: 'Example', text: 'Body', selection: 'Sel' });
});

test('the selection menu opens the panel on the tab and leaves the selection for it', async () => {
  /** @type {any[]} */
  const calls = [];
  const chrome = {
    sidePanel: { open: async (/** @type {any} */ options) => calls.push(['open', options]) },
    storage: { session: { set: async (/** @type {any} */ value) => calls.push(['set', value]) } },
  };

  await onSelectionMenu(chrome, { menuItemId: SELECTION_MENU, selectionText: 'Quote' }, {
    id: 3,
    windowId: 10,
    title: 'Article',
    url: 'https://example.com/post',
  }, fakeLocks());
  await onSelectionMenu(chrome, { menuItemId: SELECTION_MENU, selectionText: 'x' }, { id: 4, windowId: 10, url: 'chrome://newtab' }, fakeLocks());

  assert.deepEqual(calls, [
    ['open', { tabId: 3 }],
    ['set', { [pendingAttachmentKey(10)]: { kind: 'page', url: 'https://example.com/post', title: 'Article', text: '', selection: 'Quote' } }],
  ]);
});


test('selection goes only to its window and is consumed once by concurrent panels', async () => {
  const locks = fakeLocks();
  const values = /** @type {Record<string, any>} */ ({});
  const chrome = {
    sidePanel: { open: async () => {} },
    storage: { session: {
      get: async (/** @type {string} */ key) => ({ [key]: values[key] }),
      set: async (/** @type {Record<string, any>} */ next) => Object.assign(values, next),
      remove: async (/** @type {string} */ key) => { delete values[key]; },
    } },
  };
  await onSelectionMenu(chrome, { menuItemId: SELECTION_MENU, selectionText: 'Private quote' },
    { id: 3, windowId: 10, url: 'https://example.com/' }, locks);
  assert.equal(await takeSelection(chrome, 20, locks), undefined);
  const consumed = await Promise.all([takeSelection(chrome, 10, locks), takeSelection(chrome, 10, locks)]);
  assert.equal(consumed.filter(Boolean).length, 1);
  assert.equal(consumed.find(Boolean).selection, 'Private quote');
  assert.deepEqual(values, {});
});
