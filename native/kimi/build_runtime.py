"""Build the pinned host-controlled Kimi runtime using upstream's Node SEA pipeline.

Requires the exact Node/pnpm versions in gradle/libs.versions.toml. Example (from repository root):
python native/kimi/build_runtime.py --node <node> --pnpm-js <pnpm.cjs>
The worktree stays under build/; native/kimi/out/<platform>-<arch> contains the executable, license and hash manifest.
No user's Kimi home, config or credentials are read by this builder. No release or CI workflow is triggered.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "native/kimi"
PATCHED_PATHS = {
    "apps/kimi-code/package.json", "apps/kimi-code/src/cli/sub/acp.ts",
    "packages/acp-server/src/server.ts", "packages/acp-server/src/approval.ts",
    "packages/acp-server/src/session.ts", "packages/acp-server/src/interaction-bridge.ts",
    "packages/agent-core-v2/src/features/plan/planService.ts",
    "packages/agent-core-v2/src/features/externalHooks/externalHooksFeature.ts",
    "packages/agent-core-v2/src/workspace/workspaceMcpConfig/workspaceMcpConfigService.ts",
    "packages/agent-core-v2/src/agent/interaction/approval.ts",
    "packages/agent-core-v2/src/agent/toolApproval/toolApprovalService.ts",
    "packages/agent-core-v2/src/agent/mcp/tools/mcp.ts",
    "packages/agent-core-v2/src/mcpCore/client-http.ts",
    "packages/agent-core-v2/src/mcpCore/types.ts",
    "packages/acp-server/test/_helpers/scriptedProvider.ts",
    "packages/agent-core-v2/test/features/tower/tools/spawnTool.test.ts",
    "packages/agent-core-v2/src/tool/toolContract.ts",
    "packages/agent-core-v2/src/agent/tools/agent/agentTool.ts",
    "packages/agent-core-v2/src/features/swarm/tools/agent-swarm/agentSwarmTool.ts",
    "packages/agent-core-v2/src/features/swarm/session/sessionSwarmService.ts",
    "packages/agent-core-v2/src/features/tower/tools/spawn/spawnTool.ts",
}
ADDED_PATHS = {
    "apps/kimi-code/src/cli/sub/heartbeat-acp.ts", "packages/acp-server/src/heartbeat-acp.ts",
    "packages/acp-server/test/heartbeat-permissions.test.ts",
    "packages/agent-core-v2/test/features/plan/heartbeat-plan.test.ts",
    "packages/agent-core-v2/test/agent/mcp/heartbeat-binding.test.ts",
    "packages/agent-core-v2/src/heartbeat/origins.ts",
    "packages/agent-core-v2/test/heartbeat/heartbeat-origins.test.ts",
}


def run(argv, cwd, env=None, capture=False):
    return subprocess.run([str(arg) for arg in argv], cwd=cwd, env=env, check=True,
                          text=True, encoding="utf-8", stdout=subprocess.PIPE if capture else None).stdout


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise RuntimeError("Pinned Kimi patch context changed")
    return text.replace(before, after, 1)


def prepare(source, lock, version):
    if not source.exists():
        source.mkdir(parents=True)
        run(["git", "init"], source)
        run(["git", "config", "core.longpaths", "true"], source)
        run(["git", "remote", "add", "origin", lock["repository"]], source)
        run(["git", "fetch", "--depth", "1", "origin", lock["commit"]], source)
        run(["git", "checkout", "--detach", lock["commit"]], source)
    if run(["git", "rev-parse", "HEAD"], source, capture=True).strip() != lock["commit"]:
        raise RuntimeError("Kimi source checkout is not the locked commit")
    changed = run(["git", "diff", "--name-only", "HEAD"], source, capture=True).splitlines()
    added = run(["git", "ls-files", "--others", "--exclude-standard"], source, capture=True).splitlines()
    if set(changed) - PATCHED_PATHS or set(added) - ADDED_PATHS:
        raise RuntimeError("Unexpected changes in pinned Kimi source; use a clean build checkout")

    def original(path):
        return run(["git", "show", f'{lock["commit"]}:{path}'], source, capture=True)

    entry = "apps/kimi-code/src/cli/sub/acp.ts"
    (source / entry).write_text(replace_once(original(entry),
        "await import('@moonshot-ai/acp-server')", "await import('./heartbeat-acp')"), encoding="utf-8")
    shutil.copyfile(NATIVE / "heartbeat-acp.ts", source / "apps/kimi-code/src/cli/sub/heartbeat-acp.ts")
    plan = "packages/agent-core-v2/src/features/plan/planService.ts"
    (source / plan).write_text(replace_once(original(plan),
        "        event.allow();", "        event.pass();"), encoding="utf-8")
    hooks = "packages/agent-core-v2/src/features/externalHooks/externalHooksFeature.ts"
    (source / hooks).write_text(replace_once(original(hooks),
        "registerFeature(ExternalHooksFeature);",
        "// Heartbeat: saved executable hooks are disabled outside the mandatory host gate."), encoding="utf-8")
    mcp = "packages/agent-core-v2/src/workspace/workspaceMcpConfig/workspaceMcpConfigService.ts"
    mcp_source = replace_once(original(mcp),
        "return { ...Object.fromEntries(this.pluginServers), ...Object.fromEntries(this.fileServers) };",
        "return {}; // Heartbeat: only explicitly host-provided session MCP servers may start.")
    (source / mcp).write_text(replace_once(mcp_source, "    void this.watchConfigFiles();",
        "    // Heartbeat: saved MCP configuration is inert and needs no filesystem watchers."), encoding="utf-8")
    approval_type = "packages/agent-core-v2/src/agent/interaction/approval.ts"
    (source / approval_type).write_text(replace_once(original(approval_type),
        "  readonly toolName: string;", "  readonly toolName: string;\n  readonly toolInput?: unknown;\n  readonly heartbeatCall?: string;"), encoding="utf-8")
    approval_service = "packages/agent-core-v2/src/agent/toolApproval/toolApprovalService.ts"
    approval_code = replace_once(original(approval_service),
        "      toolName: name,\n      action,\n      display,",
        "      toolName: name,\n      toolInput: context.args,\n      heartbeatCall: context.execution.heartbeatCall,\n      action,\n      display,")
    approval_code = replace_once(approval_code, "      ...approvalRequest,\n      toolInput: context.args,",
        "      id: approvalRequest.id, sessionId: approvalRequest.sessionId, agentId: approvalRequest.agentId,\n"
        "      turnId: approvalRequest.turnId, toolCallId: approvalRequest.toolCallId, toolName: name,\n"
        "      action, display, toolInput: context.args,")
    (source / approval_service).write_text(approval_code, encoding="utf-8")
    approval_map = "packages/acp-server/src/approval.ts"
    mapped = replace_once(original(approval_map), "import { acpToolCallId } from './events-map';",
        "import { acpToolCallId, inferToolKind } from './events-map';")
    (source / approval_map).write_text(replace_once(mapped,
        "    title: req.toolName,", "    title: req.toolName,\n    kind: inferToolKind(req.toolName),\n    rawInput: req.toolInput,\n    _meta: req.heartbeatCall ? { heartbeatCall: req.heartbeatCall } : undefined,"), encoding="utf-8")
    contract = "packages/agent-core-v2/src/tool/toolContract.ts"
    (source / contract).write_text(replace_once(original(contract),
        "  readonly approvalRule: string;", "  readonly heartbeatCall?: string;\n  readonly approvalRule: string;"),
        encoding="utf-8")
    http_client = "packages/agent-core-v2/src/mcpCore/client-http.ts"
    (source / http_client).write_text(replace_once(original(http_client),
        "  async listTools(): Promise<MCPToolDefinition[]> {",
        "  getInstructions(): string | undefined { return this.client.getInstructions(); }\n\n"
        "  async listTools(): Promise<MCPToolDefinition[]> {"), encoding="utf-8")
    mcp_types = "packages/agent-core-v2/src/mcpCore/types.ts"
    (source / mcp_types).write_text(replace_once(original(mcp_types),
        "export interface MCPClient {", "export interface MCPClient {\n  getInstructions?(): string | undefined;"), encoding="utf-8")
    mcp_tool = "packages/agent-core-v2/src/agent/mcp/tools/mcp.ts"
    mcp_code = replace_once(original(mcp_tool),
        "import type { ToolDescription as KosongTool }", "import { randomUUID } from 'node:crypto';\nimport type { ToolDescription as KosongTool }")
    mcp_code = replace_once(mcp_code,
        "  const callTool = (activeClient: MCPClient, args: unknown, signal: AbortSignal) =>\n    activeClient.callTool(tool.name, (args ?? {}) as Record<string, unknown>, signal);\n",
        "")
    mcp_code = replace_once(mcp_code,
        "    description: tool.description,",
        "    description: options.serverName === 'heartbeat_tools' && client.getInstructions?.()?.trim()\n"
        "      ? `${tool.description}\\n\\n${client.getInstructions!()}` : tool.description,")
    mcp_code = replace_once(mcp_code,
        "    resolveExecution: (args) => ({\n      approvalRule: qualifiedName,",
        "    resolveExecution: (args) => {\n      const heartbeatCall = options.serverName === 'heartbeat_tools' ? randomUUID() : undefined;\n      const callTool = (activeClient: MCPClient, input: unknown, signal: AbortSignal) =>\n        activeClient.callTool(tool.name, heartbeatCall === undefined ? (input ?? {}) as Record<string, unknown> :\n          { ...((input ?? {}) as Record<string, unknown>), __heartbeat_call: heartbeatCall }, signal);\n      return {\n      heartbeatCall,\n      approvalRule: qualifiedName,")
    mcp_code = replace_once(mcp_code, "      },\n    }),\n  };\n}", "      },\n    };\n    },\n  };\n}")
    (source / mcp_tool).write_text(mcp_code, encoding="utf-8")
    origin_path = source / "packages/agent-core-v2/src/heartbeat/origins.ts"
    origin_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(NATIVE / "heartbeat-origins.ts", origin_path)
    agent_tool = "packages/agent-core-v2/src/agent/tools/agent/agentTool.ts"
    agent_code = replace_once(original(agent_tool),
        "import { type CollectionView }", "import { heartbeatOrigins } from '#/heartbeat/origins';\nimport { type CollectionView }")
    agent_code = replace_once(agent_code, "    toolCallId: string,\n    controller: AbortController,\n  ): Promise<SubagentHandle>",
        "    toolCallId: string,\n    controller: AbortController,\n    parentTurn: number,\n  ): Promise<SubagentHandle>")
    agent_code = replace_once(agent_code, "    { toolCallId, signal }: ExecutableToolContext,",
        "    { toolCallId, signal, turnId }: ExecutableToolContext,")
    agent_code = replace_once(agent_code, "this.launch(args, toolCallId, controller)",
        "this.launch(args, toolCallId, controller, turnId)")
    agent_code = replace_once(agent_code,
        "    const run = await this.subagents.run(\n      target.accessor.get(IAgentScopeContext).agentContext,\n      { kind: 'prompt', prompt: promptText },\n      { signal: controller.signal },\n    );",
        "    const sessionId = (await this.sessionMetadata.read()).id;\n"
        "    const starting = Promise.resolve().then(() => this.subagents.run(\n"
        "      target.accessor.get(IAgentScopeContext).agentContext,\n"
        "      { kind: 'prompt', prompt: promptText },\n      { signal: controller.signal },\n    ));\n"
        "    heartbeatOrigins(sessionId)?.register(agentId, this.callerAgentId, parentTurn, starting, controller.signal);\n"
        "    const run = await starting;")
    (source / agent_tool).write_text(agent_code, encoding="utf-8")
    swarm_tool = "packages/agent-core-v2/src/features/swarm/tools/agent-swarm/agentSwarmTool.ts"
    swarm_tool_code = "import { withHeartbeatParent } from '#/heartbeat/origins';\n" + original(swarm_tool)
    swarm_tool_code = replace_once(swarm_tool_code,
        "const result = await this.runSwarm(args, context.signal, context.toolCallId);",
        "const result = await withHeartbeatParent(this.callerAgentId, context.turnId, () =>\n"
        "        this.runSwarm(args, context.signal, context.toolCallId));")
    (source / swarm_tool).write_text(swarm_tool_code, encoding="utf-8")
    swarm_service = "packages/agent-core-v2/src/features/swarm/session/sessionSwarmService.ts"
    swarm_code = "import { heartbeatOrigins, heartbeatParent } from '#/heartbeat/origins';\n" + original(swarm_service)
    swarm_code = replace_once(swarm_code,
        "      run = await this.subagents.run(agentContextOf(child), request, {\n        signal: options.signal,\n        onReady: options.onReady,\n      });",
        "      const parent = heartbeatParent();\n"
        "      const sessionId = (await this.metadata.read()).id;\n"
        "      const starting = Promise.resolve().then(() => this.subagents.run(agentContextOf(child), request, {\n"
        "        signal: options.signal,\n        onReady: options.onReady,\n      }));\n"
        "      if (parent !== undefined && parent.agent === caller.id) {\n"
        "        heartbeatOrigins(sessionId)?.register(agentId, parent.agent, parent.turn, starting, options.signal);\n      }\n"
        "      run = await starting;")
    (source / swarm_service).write_text(swarm_code, encoding="utf-8")
    tower_tool = "packages/agent-core-v2/src/features/tower/tools/spawn/spawnTool.ts"
    tower_code = "import { heartbeatOrigins } from '#/heartbeat/origins';\n" + original(tower_tool)
    tower_code = replace_once(tower_code, "    { toolCallId }: ExecutableToolContext,", "    { toolCallId, turnId }: ExecutableToolContext,")
    tower_code = replace_once(tower_code, "this.launch(prompt, description, toolCallId, controller, binding)",
        "this.launch(prompt, description, toolCallId, controller, binding, turnId)")
    tower_code = replace_once(tower_code,
        "    binding: SubagentBinding | undefined,\n  ): Promise<SubagentHandle>",
        "    binding: SubagentBinding | undefined,\n    parentTurn: number,\n  ): Promise<SubagentHandle>")
    tower_code = replace_once(tower_code,
        "    const run = await this.subagents.run(\n      createdContext,\n      { kind: 'prompt', prompt },\n      { signal: controller.signal },\n    );",
        "    const starting = Promise.resolve().then(() => this.subagents.run(\n"
        "      createdContext,\n      { kind: 'prompt', prompt },\n      { signal: controller.signal },\n    ));\n"
        "    heartbeatOrigins(this.sessionContext.sessionId)?.register(agentId, this.callerAgentId, parentTurn, starting, controller.signal);\n"
        "    const run = await starting;")
    (source / tower_tool).write_text(tower_code, encoding="utf-8")
    acp_session = "packages/acp-server/src/session.ts"
    session_code = replace_once(original(acp_session),
        "interface TurnDriver {", "interface TurnDriver {\n  readonly launch: Promise<PromptLaunchResult>;\n  readonly heartbeatTurn?: string;")
    session_code = replace_once(session_code,
        "import { AcpInteractionBridge } from './interaction-bridge';",
        "import { AcpInteractionBridge } from './interaction-bridge';\n"
        "import { HeartbeatOrigins, installHeartbeatOrigins, removeHeartbeatOrigins } from '@moonshot-ai/agent-core-v2/heartbeat/origins';")
    session_code = replace_once(session_code,
        "  private readonly interactionBridge: AcpInteractionBridge;",
        "  private readonly heartbeatOrigins: HeartbeatOrigins;\n  private readonly interactionBridge: AcpInteractionBridge;")
    session_code = replace_once(session_code, "    this.interactionBridge = new AcpInteractionBridge",
        "    this.heartbeatOrigins = new HeartbeatOrigins((turn) => this.acceptsMainApproval(turn));\n"
        "    installHeartbeatOrigins(sessionId, this.heartbeatOrigins);\n"
        "    this.interactionBridge = new AcpInteractionBridge")
    session_code = replace_once(session_code, "    this.interactionBridge.dispose();",
        "    removeHeartbeatOrigins(this.sessionId, this.heartbeatOrigins);\n    this.interactionBridge.dispose();")
    session_code = replace_once(session_code,
        "new AcpInteractionBridge(conn, this.session, sessionId, elicitationForm)",
        "new AcpInteractionBridge(conn, this.session, sessionId, elicitationForm, (agent, turn) => agent === undefined || turn === undefined ? Promise.resolve(undefined) : this.heartbeatOrigins.resolve(agent, turn))")
    session_code = replace_once(session_code,
        "const driver: TurnDriver = { resolve, reject, settled: false, early: [] };",
        "const driver: TurnDriver = { resolve, reject, settled: false, early: [], launch, heartbeatTurn };")
    session_code = replace_once(session_code,
        "  async prompt(blocks: readonly ContentBlock[]): Promise<PromptResponse> {",
        "  async prompt(blocks: readonly ContentBlock[], heartbeatTurn?: string): Promise<PromptResponse> {")
    session_code = replace_once(session_code, "this.driveSkillActivation(intent.skillName, intent.args)",
        "this.driveSkillActivation(intent.skillName, intent.args, heartbeatTurn)")
    session_code = replace_once(session_code, "return this.driveTurn(content);", "return this.driveTurn(content, heartbeatTurn);")
    session_code = replace_once(session_code,
        "private driveSkillActivation(skillName: string, args: string): Promise<PromptResponse>",
        "private driveSkillActivation(skillName: string, args: string, heartbeatTurn?: string): Promise<PromptResponse>")
    session_code = replace_once(session_code,
        "this.agent.activateSkill({ name: skillName, args: args.length > 0 ? args : undefined }),",
        "() => this.agent.activateSkill({ name: skillName, args: args.length > 0 ? args : undefined }),\n      heartbeatTurn,")
    session_code = replace_once(session_code, "private driveLaunch(launch: Promise<PromptLaunchResult>): Promise<PromptResponse>",
        "private driveLaunch(start: () => Promise<PromptLaunchResult>, heartbeatTurn?: string): Promise<PromptResponse>")
    session_code = replace_once(session_code,
        "    return new Promise<PromptResponse>((resolve, reject) => {\n      const driver: TurnDriver",
        "    const launch = Promise.resolve().then(start);\n    return new Promise<PromptResponse>((resolve, reject) => {\n      const driver: TurnDriver")
    session_code = replace_once(session_code, "this.driveLaunch(this.agent.prompt({ input }))",
        "this.driveLaunch(() => this.agent.prompt({ input }), heartbeatTurn)")
    session_code = replace_once(session_code,
        "  private driveTurn(input: readonly ContentPart[]): Promise<PromptResponse> {",
        "  private async acceptsMainApproval(turn: number): Promise<string | undefined> {\n"
        "    const driver = this.driver;\n"
        "    if (driver === undefined) return undefined;\n"
        "    try {\n"
        "      const launched = await driver.launch;\n"
        "      return this.driver === driver && !driver.settled && launched?.turn_id === turn ? driver.heartbeatTurn : undefined;\n"
        "    } catch {\n"
        "      return undefined;\n"
        "    }\n"
        "  }\n\n  private driveTurn(input: readonly ContentPart[], heartbeatTurn?: string): Promise<PromptResponse> {")
    (source / acp_session).write_text(session_code, encoding="utf-8")
    interaction = "packages/acp-server/src/interaction-bridge.ts"
    interaction_code = replace_once(original(interaction),
        "    private readonly elicitationForm = false,\n", "    private readonly elicitationForm = false,\n"
        "    private readonly acceptsHostedApproval: (agent: string | undefined, turn: number | undefined) => Promise<string | undefined> = async () => undefined,\n")
    interaction_code = replace_once(interaction_code,
        "    const toolCall = buildPermissionToolCallUpdate(req);",
        "    const heartbeatTurn = await this.acceptsHostedApproval(req.agentId, req.turnId);\n"
        "    if (heartbeatTurn === undefined) return { decision: 'rejected' };\n"
        "    const original = buildPermissionToolCallUpdate(req);\n"
        "    const toolCall = { ...original, _meta: { ...original._meta, heartbeatTurn, heartbeatAgent: req.agentId } };")
    interaction_code = replace_once(interaction_code,
        "        const result = await this.handleQuestion(interaction.payload as QuestionRequest);",
        "        const question = interaction.payload as QuestionRequest;\n"
        "        const agent = interaction.tags?.['agentId'];\n"
        "        const heartbeatTurn = await this.acceptsHostedApproval(typeof agent === 'string' ? agent : undefined, question.turnId);\n"
        "        const result = heartbeatTurn === undefined ? null : await this.handleQuestion(question, heartbeatTurn, agent as string);")
    interaction_code = replace_once(interaction_code,
        "private async handleQuestion(req: QuestionRequest): Promise<QuestionAnswers | null>",
        "private async handleQuestion(req: QuestionRequest, heartbeatTurn: string, agent: string): Promise<QuestionAnswers | null>")
    interaction_code = replace_once(interaction_code,
        "          title: 'AskUserQuestion',\n", "          title: 'AskUserQuestion',\n          _meta: { heartbeatTurn, heartbeatQuestion: true, heartbeatAgent: agent },\n")
    (source / interaction).write_text(interaction_code, encoding="utf-8")
    server = "packages/acp-server/src/server.ts"
    server_code = replace_once(original(server),
        "const agentCapabilities: AgentCapabilities = {",
        'const agentCapabilities: AgentCapabilities = {\n      _meta: { heartbeat: { permissionGate: 1, hostedCallBinding: 1 } },')
    server_code = replace_once(server_code,
        "    if (signal === undefined) {\n      return acpSession.prompt(params.prompt);",
        "    const heartbeatTurn = typeof params._meta?.['heartbeatTurn'] === 'string' ? params._meta['heartbeatTurn'] : undefined;\n"
        "    if (signal === undefined) {\n      return acpSession.prompt(params.prompt, heartbeatTurn);")
    server_code = replace_once(server_code, "return await acpSession.prompt(params.prompt);",
        "return await acpSession.prompt(params.prompt, heartbeatTurn);")
    (source / server).write_text(server_code, encoding="utf-8")
    package = "apps/kimi-code/package.json"
    metadata = json.loads(original(package))
    metadata["version"] = version
    (source / package).write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tower_test = "packages/agent-core-v2/test/features/tower/tools/spawnTool.test.ts"
    tower_test_code = "import { HeartbeatOrigins, installHeartbeatOrigins, removeHeartbeatOrigins } from '#/heartbeat/origins';\n" + original(tower_test)
    tower_test_code = replace_once(tower_test_code, "  function argsFor(kind: 'worker' | 'reviewer', model?: string): TowerSpawnToolInput {",
        (NATIVE / "heartbeat-tower-test.txt").read_text(encoding="utf-8") + "\n  function argsFor(kind: 'worker' | 'reviewer', model?: string): TowerSpawnToolInput {")
    (source / tower_test).write_text(tower_test_code, encoding="utf-8")
    helper = "packages/acp-server/test/_helpers/scriptedProvider.ts"
    helper_code = replace_once(original(helper), "    private readonly calls: Array<readonly Message[]>,",
        "    private readonly calls: Array<readonly Message[]>,\n    private readonly toolCalls: Array<readonly Tool[]>,")
    helper_code = replace_once(helper_code, "    this.calls.push(history);", "    this.calls.push(history);\n    this.toolCalls.push(_tools);")
    helper_code = replace_once(helper_code, "  callHistory(): ReadonlyArray<readonly Message[]>;",
        "  callHistory(): ReadonlyArray<readonly Message[]>;\n  toolHistory(): ReadonlyArray<readonly Tool[]>;")
    helper_code = replace_once(helper_code, "  const provider = new ScriptedChatProvider(queue, calls);",
        "  const toolCalls: Array<readonly Tool[]> = [];\n  const provider = new ScriptedChatProvider(queue, calls, toolCalls);")
    helper_code = replace_once(helper_code, "    callHistory: () => calls,", "    callHistory: () => calls,\n    toolHistory: () => toolCalls,")
    (source / helper).write_text(helper_code, encoding="utf-8")
    shutil.copyfile(NATIVE / "heartbeat-permissions.test.ts",
                    source / "packages/acp-server/test/heartbeat-permissions.test.ts")
    shutil.copyfile(NATIVE / "heartbeat-acp.ts", source / "packages/acp-server/src/heartbeat-acp.ts")
    shutil.copyfile(NATIVE / "heartbeat-binding.test.ts",
                    source / "packages/agent-core-v2/test/agent/mcp/heartbeat-binding.test.ts")
    origin_test = source / "packages/agent-core-v2/test/heartbeat/heartbeat-origins.test.ts"
    origin_test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(NATIVE / "heartbeat-origins.test.ts", origin_test)
    plan_test = "packages/agent-core-v2/test/features/plan/planGuard.test.ts"
    (source / "packages/agent-core-v2/test/features/plan/heartbeat-plan.test.ts").write_text(
        replace_once(original(plan_test), "  describe('guard', () => {",
            (NATIVE / "heartbeat-plan.test.fragment.ts").read_text(encoding="utf-8") + "  describe('guard', () => {"),
        encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", default=shutil.which("node"))
    parser.add_argument("--pnpm-js", required=True)
    parser.add_argument("--work-dir", type=Path, default=ROOT / "build/kimi-upstream")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    source = args.work_dir.resolve()
    if not source.is_relative_to(ROOT / "build"):
        raise RuntimeError("Kimi build checkout must stay inside this repository's build directory")
    lock = json.loads((NATIVE / "source-lock.json").read_text(encoding="utf-8"))
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    if not args.node:
        raise RuntimeError("The pinned Node build tool is required")
    node = Path(args.node).resolve()
    pnpm = Path(args.pnpm_js).resolve()
    if run([node, "--version"], ROOT, capture=True).strip() != "v" + versions["kimi-build-node"]:
        raise RuntimeError("Node version does not match the version catalog")
    if run([node, pnpm, "--version"], ROOT, capture=True).strip() != versions["kimi-build-pnpm"]:
        raise RuntimeError("pnpm version does not match the version catalog")
    prepare(source, lock, versions["kimi-runtime"])
    if args.prepare_only:
        return
    env = dict(os.environ, HUSKY="0", KIMI_CODE_COMMIT=lock["commit"])
    env["PATH"] = str(node.parent) + os.pathsep + env.get("PATH", "")
    run([node, pnpm, "install", "--frozen-lockfile"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "acp-server",
         "test/heartbeat-permissions.test.ts"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "agent-core-v2",
         "test/features/plan/heartbeat-plan.test.ts", "-t", "Heartbeat mandatory"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "agent-core-v2",
         "test/agent/mcp/heartbeat-binding.test.ts", "test/heartbeat/heartbeat-origins.test.ts"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "agent-core-v2",
         "test/features/tower/tools/spawnTool.test.ts", "-t", "binds a Tower worker"], source, env)
    run([node, pnpm, "-C", "apps/kimi-code", "run", "build:native:sea"], source, env)
    os_name = {"Windows": "win32", "Darwin": "darwin", "Linux": "linux"}[platform.system()]
    arch = {"AMD64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}[platform.machine()]
    target = f"{os_name}-{arch}"
    executable = "kimi.exe" if os_name == "win32" else "kimi"
    built = source / f"apps/kimi-code/dist-native/bin/{target}/{executable}"
    if run([built, "--version"], source, env, capture=True).strip() != versions["kimi-runtime"]:
        raise RuntimeError("Built Kimi runtime reports an unexpected version")
    output = NATIVE / "out" / target
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built, output / executable)
    shutil.copy2(source / "LICENSE", output / "LICENSE")
    digest = hashlib.sha256(built.read_bytes()).hexdigest()
    (output / "runtime.properties").write_text(
        f'version={versions["kimi-runtime"]}\nsourceCommit={lock["commit"]}\npermissionGate={lock["permissionGate"]}\nsha256={digest}\n',
        encoding="utf-8")
    print(f"Verified Heartbeat Kimi runtime: {output}")


if __name__ == "__main__":
    main()
