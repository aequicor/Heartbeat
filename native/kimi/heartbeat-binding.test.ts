import { describe, expect, it } from 'vitest';
import { createMcpTool } from '../../../src/agent/mcp/tools/mcp';
import type { MCPClient } from '../../../src/mcpCore/types';

describe('Heartbeat MCP call binding', () => {
  it('generates a fresh execution identity and overwrites model supplied values', async () => {
    const calls: Record<string, unknown>[] = [];
    const client: MCPClient = {
      async listTools() { return []; },
      async callTool(_name, args) {
        calls.push(args);
        return { content: [{ type: 'text', text: 'ok' }], isError: false };
      },
      async ping() {},
    };
    const tool = createMcpTool(
      'mcp__heartbeat_tools__example',
      { name: 'example', description: 'Example', parameters: {} },
      client,
      { serverName: 'heartbeat_tools' },
    );
    const first = await tool.resolveExecution({ __heartbeat_call: 'forged', value: 'x' });
    const second = await tool.resolveExecution({ value: 'y' });
    if (first.isError === true || second.isError === true) throw new Error('execution unavailable');
    expect(first.heartbeatCall).toMatch(/^[0-9a-f-]{36}$/);
    expect(second.heartbeatCall).not.toBe(first.heartbeatCall);
    const signal = new AbortController().signal;
    await first.execute({ turnId: 1, toolCallId: 'a', signal });
    await second.execute({ turnId: 2, toolCallId: 'b', signal });
    expect(calls).toEqual([
      { __heartbeat_call: first.heartbeatCall, value: 'x' },
      { __heartbeat_call: second.heartbeatCall, value: 'y' },
    ]);
  });
});
