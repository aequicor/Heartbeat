package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec

/** These legacy tools remain additionally conditional on the native-subagents toggle. */
internal val ClaudeAgentTools: Set<String> = linkedSetOf("Agent", "Task", "TaskOutput", "TaskStop")

/** Additional native tools require the host gate; existing subagent and provider search tools allow Off only. */
internal val ClaudeNativeCatalog: List<NativeToolSpec> = buildList {
    listOf("Read", "Glob", "Grep").forEach { add(NativeToolSpec(it, AgentToolAction.Read, false, true)) }
    listOf("Edit", "Write", "MultiEdit").forEach { add(NativeToolSpec(it, AgentToolAction.Edit, false, true)) }
    add(NativeToolSpec(CLAUDE_MCP_GROUP, AgentToolAction.Command, false, true))
    add(NativeToolSpec("Bash", AgentToolAction.Command, false, true))
    add(NativeToolSpec("WebFetch", AgentToolAction.Command, false, true, isWorkspaceFree = true))
    ClaudeAgentTools.forEach { add(NativeToolSpec(it, AgentToolAction.Command, true, false)) }
    add(NativeToolSpec("WebSearch", AgentToolAction.Read, true, false))
}

/** Policy group for foreign MCP calls, never a built-in CLI tool name. */
internal const val CLAUDE_MCP_GROUP = "mcp"
