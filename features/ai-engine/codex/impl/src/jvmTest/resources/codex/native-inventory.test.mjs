// Offline executable contract: node native-inventory.test.mjs <installed Codex CLI path>.
// Alternatively node --test reads HEARTBEAT_CODEX_INVENTORY_EXECUTABLE from the test process.
// No real model, credentials, user config, or native tool execution is involved.
import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import test from 'node:test';

const executeFile = promisify(execFile);
const executable = process.env.HEARTBEAT_CODEX_INVENTORY_EXECUTABLE || process.argv[2];
const source = await readFile(new URL('../../../commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/codex/impl/data/CodexNativeOff.kt', import.meta.url), 'utf8');
const isolation = await readFile(new URL('../../../commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/codex/impl/data/CodexIsolation.kt', import.meta.url), 'utf8');
const inventory = await readFile(new URL('../../../commonMain/kotlin/io/aequicor/heartbeat/feature/aiengine/codex/impl/data/CodexNativeInventory.kt', import.meta.url), 'utf8');
const values = (text, name) => {
  const body = text.match(new RegExp(`val ${name}(?::[^=]+)? = (?:listOf|setOf)\\(([\\s\\S]*?)\\n?\\s*\\)`))?.[1];
  assert.ok(body, `Missing Kotlin contract ${name}`);
  return [...body.matchAll(/"([^"\r\n]+)"/g)].map(match => match[1]);
};
const enhanced = values(source, 'enhancedFeatures');
const configKeys = new Set(values(inventory, 'CodexInventoryConfigKeys'));
const isUnset = value => value == null || (Array.isArray(value) ? value.length === 0
  : typeof value === 'object' && Object.values(value).every(isUnset));
const baseline = [...values(isolation, 'CodexDisabledCapabilities'), ...values(source, 'executionFeatures'), ...values(source, 'virtualFeatures')];
const inventoryNames = tools => tools.flatMap(tool => tool.type === 'namespace'
  ? [...(tool.name === 'functions' ? [] : [tool.name]), ...inventoryNames(tool.tools ?? [])]
  : [tool.name ?? tool.type]);

test('installed CLI closes actual image code mode delegation and goal ingress while preserving hosted tools', {
  skip: !executable && 'Pass the installed or selected Codex executable path to run the offline native contract',
  timeout: 75000,
}, async () => {
  const legacy = await capture(baseline.map(name => [name, false]));
  assert.ok(legacy.names.includes('view_image'), 'The legacy view_image_tool flag must expose the regression');
  assert.ok(legacy.names.includes('create_goal'), 'The legacy registry leaves native goal mutations available');

  const alternate = await capture([
    ...baseline.map(name => [name, false]),
    ['code_mode', false], ['code_mode_host', false],
    ['code_mode_only', true], ['code_mode_only_strict_3p_tools', true],
    ['multi_agent_v2', true], ['multi_agent_v2_dynamic_tools', true],
  ]);
  assert.ok(alternate.names.includes('exec'), 'code_mode_only independently exposes executable code');
  assert.ok(alternate.names.includes('collaboration'), 'multi_agent_v2 independently exposes delegation');

  const locked = await capture([
    // Simulate hostile or stale user overrides; host-owned Off values must win for every variant.
    ...enhanced.map(name => [name, true]),
    ...baseline.map(name => [name, false]),
    ...enhanced.map(name => [name, false]),
  ]);
  assert.deepEqual(locked.names.sort(), ['heartbeat_fixture_read', 'request_user_input']);
  for (const name of [...baseline, ...enhanced]) assert.equal(locked.features[name], false, name);
  // A clean home must pass the protected allowlist; otherwise every protected session would fail closed.
  for (const [key, value] of Object.entries(locked.config)) {
    if (key !== 'profile' && !isUnset(value)) assert.ok(configKeys.has(key), `Effective config key ${key} is not allowlisted`);
  }
  assert.equal(locked.nativeCalls, 0);
  const toolFree = await capture([
    ...baseline.map(name => [name, false]),
    ...enhanced.map(name => [name, false]),
    ['default_mode_request_user_input', false],
  ]);
  // The checked build retains request_user_input even with its default-mode flag Off.
  // CodexSession rejects its callbacks for tool-free launches/current policy; the JVM regression
  // covers that refusal without publishing a human questionnaire. This capture must report reality.
  assert.deepEqual(toolFree.names.sort(), ['heartbeat_fixture_read', 'request_user_input']);
  assert.equal(toolFree.features.default_mode_request_user_input, false);
  assert.equal(toolFree.nativeCalls, 0);

  console.log(`Offline tool inventory of the selected CLI: ${locked.names.join(', ')}`);
});

async function capture(features) {
  const root = await mkdtemp(join(tmpdir(), 'heartbeat-codex-inventory-'));
  assert.equal(dirname(resolve(root)), resolve(tmpdir()));
  assert.ok(basename(root).startsWith('heartbeat-codex-inventory-'));
  const home = join(root, 'home');
  const cwd = join(root, 'work');
  await mkdir(home);
  await mkdir(cwd);
  let receive;
  const received = new Promise(resolveRequest => { receive = resolveRequest; });
  let nativeCalls = 0;
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(request.headers.authorization, undefined);
    receive(inventoryNames([...(body.tools ?? []), ...(body.input ?? []).filter(item => item.type === 'additional_tools').flatMap(item => item.tools ?? [])]));
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const result = { id: 'fixture-response', object: 'response', status: 'completed', output: [], usage: { input_tokens: 1, output_tokens: 0, total_tokens: 1 } };
    response.end(`event: response.completed\ndata: ${JSON.stringify({ type: 'response.completed', response: result })}\n\n`);
  });
  await new Promise(resolveListen => server.listen(0, '127.0.0.1', resolveListen));
  const port = server.address().port;
  const args = [
    'app-server', '-c', 'model_provider="inventory"', '-c', 'model="fixture-model"',
    '-c', `model_providers.inventory={name="Offline fixture",base_url="http://127.0.0.1:${port}/v1",wire_api="responses",requires_openai_auth=false}`,
    '-c', 'web_search="disabled"',
  ];
  for (const [name, value] of features) args.push('-c', `features.${name}=${value}`);
  const retained = new Set(['PATH', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL']);
  const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => retained.has(key.toUpperCase())));
  // CODEX_HOME retains its intended meaning and is assigned only in the isolated child's environment.
  environment.CODEX_HOME = home;
  const child = spawn(executable, args, { cwd, env: environment, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  const closed = new Promise(resolveExit => child.once('close', resolveExit));
  const pending = new Map();
  let sequence = 0;
  let timer;
  const lines = createInterface({ input: child.stdout });
  lines.on('line', line => {
    const message = JSON.parse(line);
    if (message.id != null && pending.has(message.id)) {
      const handler = pending.get(message.id);
      pending.delete(message.id);
      message.error ? handler.reject(new Error(JSON.stringify(message.error))) : handler.resolve(message.result);
    } else if (message.id != null && message.method) {
      nativeCalls++;
      child.stdin.write(JSON.stringify({ id: message.id, error: { code: -32601, message: 'No tool execution in inventory fixture' } }) + '\n');
    }
  });
  child.stderr.resume();
  const rpc = (method, params) => new Promise((resolveResult, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve: resolveResult, reject });
    child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
  try {
    return await Promise.race([
      (async () => {
        await rpc('initialize', { clientInfo: { name: 'heartbeat-inventory-fixture', version: '1' }, capabilities: { experimentalApi: true } });
        child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
        const effective = await rpc('config/read', { includeLayers: true, cwd });
        const opened = await rpc('thread/start', {
          model: 'fixture-model', modelProvider: 'inventory', cwd, approvalPolicy: 'never', sandbox: 'read-only', ephemeral: true,
          dynamicTools: [{ name: 'heartbeat_fixture_read', description: 'Host fixture tool', inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'], additionalProperties: false } }],
        });
        await rpc('turn/start', { threadId: opened.thread.id, input: [{ type: 'text', text: 'Return no output. Do not call tools.' }] });
        return { names: await received, config: effective.config, features: effective.config.features, nativeCalls };
      })(),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Offline inventory fixture timeout')), 15000); }),
    ]);
  } finally {
    clearTimeout(timer);
    child.stdin.destroy();
    let cleanupError;
    if (child.exitCode == null) {
      try {
        if (process.platform === 'win32') {
          await executeFile(join(process.env.SystemRoot, 'System32', 'taskkill.exe'), ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, timeout: 5000 });
        } else {
          child.kill();
        }
      } catch (error) {
        // Closing stdin can let app-server exit before taskkill observes the PID.
        if (child.exitCode == null && child.signalCode == null) {
          cleanupError = error;
          child.kill();
        }
      }
    }
    await closed;
    lines.close();
    await new Promise(resolveClose => server.close(resolveClose));
    await rm(root, { recursive: true, force: true });
    if (cleanupError) throw cleanupError;
  }
}
