import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

// Exercise the actual fixed script sent through this extension's CDP transport, including its redaction.
const source = readFileSync(new URL('../../features/browser/impl/src/jvmMain/kotlin/io/aequicor/heartbeat/feature/browser/impl/presentation/BrowserSafetyScript.kt', import.meta.url), 'utf8');
const template = source.split('    """\n')[1].split('\n    """.trimIndent()')[0];
const script = template
  .replace('$arguments', '{}')
  .replace('${JsonPrimitive(operation?.name.orEmpty())}', '"Type"')
  .replace('$geometry', '{document:"doc",url:"https://example.com",width:800,height:600,x:0,y:0}')
  .replaceAll("${'$'}", '$');

function element(autocomplete = '', type = 'text') {
  return {
    type, value: 'private-field-value', tagName: 'INPUT', innerText: '', isConnected: true,
    getAttribute: name => ({ autocomplete, type })[name] ?? null,
    getBoundingClientRect: () => ({ x: 0, y: 0, width: 100, height: 30 }),
    closest: () => null,
    checkVisibility: () => true,
  };
}

function page(focus) {
  return { window: {}, document: { title: 'Page', body: { innerText: 'Form' }, activeElement: focus } };
}

test('safety evidence redacts password and sensitive autocomplete tokens in every position', () => {
  for (const [autocomplete, type] of [
    ['', 'password'], ['cc-number', 'text'], ['section-checkout billing cc-number', 'text'],
    ['shipping cc-csc', 'text'], ['section-signin one-time-code', 'text'], ['BILLING CC-NUMBER', 'text'],
  ]) {
    const evidence = runInNewContext(script, page(element(autocomplete, type)));
    assert.equal(evidence.focus.isSensitive, true, autocomplete);
    assert.equal(evidence.focus.value, null, autocomplete);
    assert.equal(JSON.stringify(evidence).includes('private-field-value'), false, autocomplete);
  }
});

test('ordinary editing evidence keeps the exact focused value and detects a replaced focus', () => {
  const context = page(element('section-search nickname'));
  const before = runInNewContext(script, context);
  assert.equal(before.focus.isSensitive, false);
  assert.equal(before.focus.value, 'private-field-value');
  assert.equal(runInNewContext(script, context).focus.node, before.focus.node);
  context.document.activeElement = element('section-search nickname');
  assert.notEqual(runInNewContext(script, context).focus.node, before.focus.node);
});

test('safety observations preserve existing agent element references', () => {
  const context = page(element());
  const references = [context.document.activeElement];
  context.window.__heartbeatElements = references;
  runInNewContext(script, context);
  assert.equal(context.window.__heartbeatElements, references);
});

test('oversized attributes fail before page evidence crosses the transport', () => {
  const context = page(element());
  context.document.activeElement.href = `https://example.com/${'x'.repeat(50000)}`;
  assert.throws(() => runInNewContext(script, context), /exceeds the review limit/);
});
