// @ts-check
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { icon, ICONS } from '../src/sidepanel/icons.js';
import { fakeDocument } from './fakes/dom.js';

const KOTLIN = new URL('../../design-system/components/src/commonMain/kotlin/io/aequicor/heartbeat/ds/components/HbIcons.kt', import.meta.url);

/** Path data and kind of every icon the app declares. */
function appIcons() {
  const source = readFileSync(KOTLIN, 'utf8');
  const declarations = /public val (\w+): ImageVector = (outlineIcon|filledIcon)\(\s*"\w+",\s*((?:"[^"]*"\s*\+?\s*)+)/g;
  /** @type {Map<string, { path: string, filled: boolean }>} */
  const icons = new Map();
  for (const [, name, kind, body] of source.matchAll(declarations)) {
    const path = [...body.matchAll(/"([^"]*)"/g)].map((it) => it[1]).join('');
    icons.set(name, { path, filled: kind === 'filledIcon' });
  }
  return icons;
}

test('the panel draws the same icons as the app', () => {
  const app = appIcons();
  for (const [name, own] of Object.entries(ICONS)) {
    assert.deepEqual(own, app.get(name), name);
  }
});

test('outline icons are stroked like the app and colored by the text', () => {
  const document = fakeDocument();

  const outline = icon(document, 'Plus');
  const filled = icon(document, 'Stop', 14);

  assert.equal(outline.attrs.viewBox, '0 0 20 20');
  assert.equal(outline.attrs.width, '18');
  assert.equal(outline.children[0].attrs['stroke-width'], '1.4');
  assert.equal(outline.children[0].attrs.stroke, 'currentColor');
  assert.equal(filled.attrs.width, '14');
  assert.equal(filled.children[0].attrs.fill, 'currentColor');
});

test('no panel text is smaller than the app\'s caption', () => {
  const css = readFileSync(new URL('../src/sidepanel/sidepanel.css', import.meta.url), 'utf8');
  const sizes = [...css.matchAll(/(?:font-size|font|--font-[a-z]+):\s*(\d+)px/g)].map((it) => Number(it[1]));

  assert.ok(sizes.length > 0);
  assert.ok(sizes.every((size) => size >= 12), `sizes: ${sizes}`);
  assert.match(css, /--font-body: 14px\/21px/);
});
