// @ts-check
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseInline, parseMarkdown, safeHref } from '../src/sidepanel/markdown.js';

test('blocks: headings, fenced code, lists, quotes and paragraphs', () => {
  const blocks = parseMarkdown('# Title\n\nText line\nnext\n\n```kotlin\nval a = 1\n```\n- one\n- two\n1. first\n> quoted');

  assert.deepEqual(blocks.map((block) => block.type), ['heading', 'paragraph', 'code', 'list', 'list', 'quote']);
  assert.deepEqual(blocks[2], { type: 'code', language: 'kotlin', text: 'val a = 1' });
  assert.equal(/** @type {any} */ (blocks[3]).items.length, 2);
  assert.equal(/** @type {any} */ (blocks[4]).ordered, true);
});

test('an unterminated fence keeps the rest as code', () => {
  assert.deepEqual(parseMarkdown('```\n<script>alert(1)</script>'), [
    { type: 'code', language: '', text: '<script>alert(1)</script>' },
  ]);
});

test('inline code, emphasis and links', () => {
  assert.deepEqual(parseInline('Run `ls` **now** and *see* [docs](https://example.com/a)'), [
    { type: 'text', text: 'Run ' },
    { type: 'code', text: 'ls' },
    { type: 'text', text: ' ' },
    { type: 'strong', children: [{ type: 'text', text: 'now' }] },
    { type: 'text', text: ' and ' },
    { type: 'em', children: [{ type: 'text', text: 'see' }] },
    { type: 'text', text: ' ' },
    { type: 'link', href: 'https://example.com/a', children: [{ type: 'text', text: 'docs' }] },
  ]);
});

test('links other than http(s) become plain text', () => {
  assert.deepEqual(parseInline('[x](javascript:alert(1))'), [{ type: 'text', text: 'x' }, { type: 'text', text: ')' }]);
  assert.deepEqual(parseInline('[x](chrome://settings)'), [{ type: 'text', text: 'x' }]);
  assert.equal(safeHref('data:text/html,hi'), null);
  assert.equal(safeHref('/relative'), null);
  assert.equal(safeHref('http://localhost:8080/'), 'http://localhost:8080/');
});

test('markup in text stays text', () => {
  assert.deepEqual(parseMarkdown('<img src=x onerror=alert(1)>'), [
    { type: 'paragraph', children: [{ type: 'text', text: '<img src=x onerror=alert(1)>' }] },
  ]);
});
