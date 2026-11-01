import { test } from 'node:test';
import assert from 'node:assert/strict';
import { apply } from '../../jvmMain/resources/heartbeat-dsh-hosted.mjs';

function harness() {
  const listeners = new Map();
  const calls = [];
  const tool = { execute: async (args, execution) => { calls.push({ args, execution }); return 'ok'; } };
  apply({ tools: { get: () => tool }, on: (event, fn) => listeners.set(event, fn) });
  const exec = { name: 'mcp__heartbeat_tools__questionnaire_ask', callId: 'native-call',
    agent: {}, signal: new AbortController().signal, arguments: Object.freeze({ question: 'test' }) };
  return { listeners, calls, tool, exec };
}

test('transport gets native identity while durable arguments remain unchanged', async () => {
  const h = harness();
  assert.equal((await h.listeners.get('tools/pre-execute')(h.exec, async () => ({ kind: 'allow' }))).kind, 'ask');
  assert.equal(await h.tool.execute(h.exec.arguments, h.exec), 'ok');
  assert.deepEqual(h.calls[0].args, { question: 'test', __heartbeat_call: 'native-call' });
  assert.deepEqual(h.exec.arguments, { question: 'test' });
});

test('model cannot forge the reserved identity and concurrent calls keep their own ids', async () => {
  const h = harness();
  await h.listeners.get('tools/pre-execute')(h.exec, async () => ({ kind: 'allow' }));
  await Promise.all(['one', 'two'].map(callId => h.tool.execute({ __heartbeat_call: 'forged' }, { ...h.exec, callId })));
  assert.deepEqual(h.calls.map(c => c.args.__heartbeat_call), ['one', 'two']);
});

test('denial remains denial and native tool execution is untouched', async () => {
  const h = harness();
  const original = h.tool.execute;
  const deny = { kind: 'deny', reason: 'policy' };
  assert.equal(await h.listeners.get('tools/pre-execute')({ ...h.exec, name: 'shell' }, async () => deny), deny);
  assert.equal(h.tool.execute, original);
  assert.equal(await h.listeners.get('tools/pre-execute')(h.exec, async () => deny), deny);
});

test('cancelled execution cannot reach the MCP transport', async () => {
  const h = harness();
  await h.listeners.get('tools/pre-execute')(h.exec, async () => ({ kind: 'allow' }));
  const controller = new AbortController(); controller.abort();
  assert.throws(() => h.tool.execute({}, { ...h.exec, signal: controller.signal }), /no longer active/);
  assert.equal(h.calls.length, 0);
});

test('disposal revokes transport without retaining scoped tool definitions', async () => {
  const h = harness();
  await h.listeners.get('tools/pre-execute')(h.exec, async () => ({ kind: 'allow' }));
  h.listeners.get('dispose')();
  assert.throws(() => h.tool.execute({}, h.exec), /no longer active/);
  assert.equal(h.calls.length, 0);
});
