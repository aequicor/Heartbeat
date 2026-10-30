/** Runs inside the locked upstream checkout: real ACP/engine, scripted model, temporary home only. */
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { getLiveSessionById, IAgentLifecycleService, IAgentPermissionPolicyService } from '@moonshot-ai/agent-core-v2';
import { HeartbeatPermissionPolicy, installHeartbeatPermissions } from '../src/heartbeat-acp';
import { AcpSession } from '../src/session';
import { createTestClient, type TestClient } from './_helpers/acpClient';
import { writeFakeModelConfig } from './_helpers/fakeModelConfig';
import { createScriptedProvider } from './_helpers/scriptedProvider';

installHeartbeatPermissions();

describe('mandatory Heartbeat permission boundary', () => {
  let home: string | undefined;
  let client: TestClient | undefined;
  afterEach(async () => {
    await client?.close();
    if (home) await rm(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    client = undefined;
    home = undefined;
  });

  it.each([
    ['manual', 'allow'], ['auto', 'allow'], ['yolo', 'allow'],
    ['manual', 'deny'], ['auto', 'deny'], ['yolo', 'deny'],
  ])('ignores saved %s mode and %s rules', async (mode, decision) => {
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-'));
    await writeFakeModelConfig(home);
    const path = join(home, 'config.toml');
    const sentinel = join(home, 'bypass.txt');
    const hookCommand = `echo bypass > "${sentinel}"`;
    const config = `default_permission_mode = "${mode}"\nhooks = [{event="SessionStart",command=${JSON.stringify(hookCommand)}}]\n` +
      await readFile(path, 'utf8') +
      `\n[permission]\nrules = [{decision="${decision}", scope="user", pattern="Bash"}]\n`;
    await writeFile(path, config);
    const mcpConfig = JSON.stringify({ mcpServers: { saved: {
      command: process.execPath,
      args: ['-e', `require('fs').writeFileSync(${JSON.stringify(sentinel)}, 'bypass')`],
    } } });
    await writeFile(join(home, 'mcp.json'), mcpConfig);
    await mkdir(join(home, '.kimi-code'));
    await writeFile(join(home, '.kimi-code', 'mcp.json'), mcpConfig);
    const scripted = createScriptedProvider();
    client = await createTestClient({ homeDir: home, extraSeeds: [scripted.seed] });
    let executions = 0;
    let approvals = 0;
    client.onRequest('terminal/create', () => { executions++; return { terminalId: 't' }; });
    client.onRequest('terminal/output', () => ({ output: '', truncated: false, exitStatus: { exitCode: 0, signal: null } }));
    client.onRequest('terminal/wait_for_exit', () => ({ exitCode: 0, signal: null }));
    client.onRequest('terminal/kill', () => ({}));
    client.onRequest('terminal/release', () => ({}));
    client.onRequest('session/request_permission', (params) => {
      const toolCall = (params as { toolCall: { kind: string; rawInput: unknown } }).toolCall;
      expect(toolCall.kind).toBe('execute');
      expect(toolCall.rawInput).toEqual({ command: 'echo gated' });
      approvals++;
      // Even a client asking to remember an approval must not bypass the next request.
      return { outcome: { outcome: 'selected', optionId: approvals === 1 ? 'approve_always' : 'reject_once' } };
    });
    await client.send('initialize', { protocolVersion: 1, clientCapabilities: { terminal: true } });
    const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [] }) as { sessionId: string };
    await client.waitForSessionUpdate('available_commands_update', 10_000);
    for (let i = 0; i < 2; i++) {
      scripted.mockNextResponse({ type: 'function', id: `call_${i}`, name: 'Bash', arguments: '{"command":"echo gated"}' });
      scripted.mockNextText('done');
      await client.send('session/prompt', { sessionId, _meta: { heartbeatTurn: 'native-turn' }, prompt: [{ type: 'text', text: 'run echo' }] });
    }
    expect(approvals).toBe(2);
    expect(executions).toBe(1);
    expect(await readFile(path, 'utf8')).toBe(config);
    await expect(access(sentinel)).rejects.toThrow();
  }, 30_000);

  it('fails closed for rejection, cancelled, broken and noninteractive approval', async () => {
    const evaluation = await new HeartbeatPermissionPolicy().evaluate();
    expect(evaluation.policyName).toBe('heartbeat');
    const result = evaluation.result;
    expect(result.kind).toBe('ask');
    if (result.kind !== 'ask') throw new Error('mandatory gate missing');
    for (const decision of ['rejected', 'cancelled'] as const) {
      expect(result.resolveApproval?.({ decision })?.kind).toBe('deny');
    }
    expect(result.resolveError?.(new Error('bridge disconnected'))?.kind).toBe('deny');
    expect(result.resolveNonInteractive?.().kind).toBe('deny');
  });

  it.each(['reject', 'cancel', 'disconnect'])('gates normally approved Read and fails closed on %s', async (answer) => {
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-read-'));
    await writeFakeModelConfig(home);
    const scripted = createScriptedProvider();
    client = await createTestClient({ homeDir: home, extraSeeds: [scripted.seed] });
    let approvals = 0;
    let reads = 0;
    client.onRequest('fs/read_text_file', () => { reads++; return { content: 'private' }; });
    client.onRequest('session/request_permission', () => {
      approvals++;
      if (answer === 'disconnect') throw new Error('client disconnected');
      return { outcome: answer === 'cancel' ? { outcome: 'cancelled' } : { outcome: 'selected', optionId: 'reject_once' } };
    });
    await client.send('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: true } } });
    const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [] }) as { sessionId: string };
    await client.waitForSessionUpdate('available_commands_update', 10_000);
    scripted.mockNextResponse({ type: 'function', id: 'read', name: 'Read', arguments: JSON.stringify({ path: join(home, 'private.txt') }) });
    scripted.mockNextText('denied');
    await client.send('session/prompt', { sessionId, _meta: { heartbeatTurn: 'native-turn' }, prompt: [{ type: 'text', text: 'read private' }] });
    expect(approvals).toBe(1);
    expect(reads).toBe(0);
  }, 30_000);

  it('installs the mandatory policy in main, new and forked agent scopes', async () => {
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-agents-'));
    await writeFakeModelConfig(home);
    client = await createTestClient({ homeDir: home });
    await client.send('initialize', { protocolVersion: 1, clientCapabilities: {} });
    const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [] }) as { sessionId: string };
    const session = getLiveSessionById(client.server.core.accessor, sessionId)!;
    const lifecycle = session.accessor.get(IAgentLifecycleService);
    const child = await lifecycle.create({ agentId: 'child' });
    const fork = await lifecycle.fork(child, { agentId: 'fork' });
    for (const agentId of ['main', child.agentId, fork.agentId]) {
      const policy = lifecycle.handleOf(agentId)!.accessor.get(IAgentPermissionPolicyService);
      expect(policy).toBeInstanceOf(HeartbeatPermissionPolicy);
    }
  }, 30_000);

  it('does not attribute delayed approvals to the current main turn', async () => {
    type Driver = { launch: Promise<{ turn_id: number }>; heartbeatTurn: string; settled: boolean };
    const guard = Object.create(AcpSession.prototype) as {
      driver?: Driver;
      acceptsMainApproval(turn: number): Promise<string | undefined>;
    };
    guard.driver = { launch: Promise.resolve({ turn_id: 4 }), heartbeatTurn: 'turn-4', settled: false };
    expect(await guard.acceptsMainApproval(4)).toBe('turn-4');
    expect(await guard.acceptsMainApproval(3)).toBeUndefined();
    let finishLaunch!: (result: { turn_id: number }) => void;
    const launch = new Promise<{ turn_id: number }>((resolve) => { finishLaunch = resolve; });
    guard.driver = { launch, heartbeatTurn: 'old', settled: false };
    const delayed = guard.acceptsMainApproval(3);
    guard.driver = { launch: Promise.resolve({ turn_id: 4 }), heartbeatTurn: 'new', settled: false };
    finishLaunch({ turn_id: 3 });
    expect(await delayed).toBeUndefined();
  });

  it('forwards questions from the main agent', async () => {
    const child = false;
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-question-'));
    await writeFakeModelConfig(home);
    const scripted = createScriptedProvider();
    client = await createTestClient({ homeDir: home, extraSeeds: [scripted.seed] });
    let questions = 0;
    const observed: string[] = [];
    client.onRequest('session/request_permission', (params) => {
      const call = (params as { toolCall: { title: string; _meta: { heartbeatTurn: string; heartbeatQuestion?: boolean; heartbeatAgent?: string } } }).toolCall;
      observed.push(`${call.title}:${call._meta.heartbeatAgent}`);
      expect(call._meta.heartbeatTurn).toBe('question-turn');
      if (call._meta.heartbeatQuestion === true) {
        expect(call._meta.heartbeatAgent === 'main').toBe(!child);
        questions++;
        return { outcome: { outcome: 'selected', optionId: 'q0_opt_1' } };
      }
      return { outcome: { outcome: 'selected', optionId: 'approve_once' } };
    });
    await client.send('initialize', { protocolVersion: 1, clientCapabilities: {} });
    const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [] }) as { sessionId: string };
    await client.waitForSessionUpdate('available_commands_update', 10_000);
    if (child) scripted.mockNextResponse({ type: 'function', id: 'child', name: 'Agent',
      arguments: JSON.stringify({ description: 'ask', prompt: 'ask a question' }) });
    scripted.mockNextResponse({ type: 'function', id: 'question', name: 'AskUserQuestion',
      arguments: JSON.stringify({ questions: [{ question: 'Pick one', header: 'Pick', options: [{ label: 'A' }, { label: 'B' }], multi_select: false }] }) });
    scripted.mockNextText('done');
    if (child) scripted.mockNextText('parent done');
    await client.send('session/prompt', { sessionId, _meta: { heartbeatTurn: 'question-turn' }, prompt: [{ type: 'text', text: 'ask' }] });
    expect(questions, observed.join(', ')).toBe(1);
  }, 30_000);

  it.each([false, true])('binds HTTP MCP calls to the originating turn, child=%s', async (child) => {
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-http-'));
    await writeFakeModelConfig(home);
    const scripted = createScriptedProvider();
    client = await createTestClient({ homeDir: home, extraSeeds: [scripted.seed] });
    let approvalCall: string | undefined;
    let receivedCall: unknown;
    const http = createServer(async (req, res) => {
      if (req.method !== 'POST') { res.writeHead(405).end(); return; }
      const chunks: Buffer[] = [];
      for await (const chunk of req) chunks.push(Buffer.from(chunk));
      const message = JSON.parse(Buffer.concat(chunks).toString());
      if (message.id === undefined) { res.writeHead(202).end(); return; }
      let result: unknown;
      if (message.method === 'initialize') {
        result = { protocolVersion: '2025-03-26', capabilities: { tools: {} }, serverInfo: { name: 'test', version: '1' } };
      } else if (message.method === 'tools/list') {
        result = { tools: [{ name: 'echo', description: 'Echo', inputSchema: { type: 'object', properties: {} } }] };
      } else if (message.method === 'tools/call') {
        receivedCall = message.params.arguments.__heartbeat_call;
        result = { content: [{ type: 'text', text: 'ok' }], isError: false };
      } else {
        result = {};
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
    });
    await new Promise<void>((resolve) => http.listen(0, '127.0.0.1', resolve));
    try {
      const address = http.address();
      if (address === null || typeof address === 'string') throw new Error('HTTP address unavailable');
      client.onRequest('session/request_permission', (params) => {
        const meta = (params as { toolCall: { _meta: { heartbeatCall?: string; heartbeatTurn: string; heartbeatAgent?: string } } }).toolCall._meta;
        expect(meta.heartbeatTurn).toBe('origin-one');
        if (meta.heartbeatCall !== undefined) {
          expect(meta.heartbeatAgent === 'main').toBe(!child);
          approvalCall = meta.heartbeatCall;
        }
        return { outcome: { outcome: 'selected', optionId: 'approve_once' } };
      });
      await client.send('initialize', { protocolVersion: 1, clientCapabilities: {} });
      const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [
        { type: 'http', name: 'heartbeat_tools', url: `http://127.0.0.1:${address.port}/mcp`, headers: [] },
      ] }) as { sessionId: string };
      await client.waitForSessionUpdate('available_commands_update', 10_000);
      if (child) {
        scripted.mockNextResponse({ type: 'function', id: 'child_launch', name: 'Agent',
          arguments: JSON.stringify({ description: 'child test', prompt: 'use the echo tool' }) });
      }
      scripted.mockNextResponse({ type: 'function', id: 'mcp_http', name: 'mcp__heartbeat_tools__echo', arguments: '{"__heartbeat_call":"forged"}' });
      scripted.mockNextText('done');
      if (child) scripted.mockNextText('parent done');
      await client.send('session/prompt', { sessionId, _meta: { heartbeatTurn: 'origin-one' }, prompt: [{ type: 'text', text: 'use echo' }] });
      expect(approvalCall).toMatch(/^[0-9a-f-]{36}$/);
      expect(receivedCall).toBe(approvalCall);
    } finally {
      http.closeAllConnections();
      await new Promise<void>((resolve) => http.close(() => resolve()));
    }
  }, 30_000);

  it('gates tools of MCP servers explicitly provided by Heartbeat', async () => {
    home = await mkdtemp(join(tmpdir(), 'heartbeat-kimi-mcp-'));
    await writeFakeModelConfig(home);
    const scripted = createScriptedProvider();
    client = await createTestClient({ homeDir: home, extraSeeds: [scripted.seed] });
    let approvals = 0;
    client.onRequest('session/request_permission', (params) => {
      const call = (params as { toolCall: { _meta?: { heartbeatCall?: string; heartbeatTurn?: string } } }).toolCall;
      expect(call._meta?.heartbeatCall).toMatch(/^[0-9a-f-]{36}$/);
      expect(call._meta?.heartbeatTurn).toBe("host-turn-1");
      approvals++;
      return { outcome: { outcome: 'selected', optionId: 'reject_once' } };
    });
    await client.send('initialize', { protocolVersion: 1, clientCapabilities: {} });
    const fixture = fileURLToPath(new URL('../../agent-core-v2/test/mcpCore/fixtures/mock-stdio-server.mjs', import.meta.url));
    const { sessionId } = await client.send('session/new', { cwd: home, mcpServers: [
      { name: 'heartbeat_tools', command: process.execPath, args: [fixture], env: [] },
    ] }) as { sessionId: string };
    await client.waitForSessionUpdate('available_commands_update', 10_000);
    scripted.mockNextResponse({ type: 'function', id: 'mcp_call', name: 'mcp__heartbeat_tools__echo', arguments: '{"text":"gated"}' });
    scripted.mockNextText('denied');
    await client.send('session/prompt', { sessionId, _meta: { heartbeatTurn: 'host-turn-1' }, prompt: [{ type: 'text', text: 'use echo' }] });
    expect(approvals).toBe(1);
  }, 30_000);
});
