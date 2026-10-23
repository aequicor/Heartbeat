package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec

/** These legacy tools remain additionally conditional on the native-subagents toggle. */
internal val ClaudeAgentTools: Set<String> = linkedSetOf("Agent", "Task", "TaskOutput", "TaskStop")

/** Additional native tools require the host gate; existing subagent and provider search tools allow Off only. */
internal val ClaudeNativeCatalog: List<NativeToolSpec> = buildList {
    listOf("Read", "Glob", "Grep").forEach { add(NativeToolSpec(it, AgentToolAction.Read, false, true)) }
    listOf("Edit", "Write", "MultiEdit").forEach { add(NativeToolSpec(it, AgentToolAction.Edit, false, true)) }
    listOf("Bash", "WebFetch").forEach { add(NativeToolSpec(it, AgentToolAction.Command, false, true)) }
    ClaudeAgentTools.forEach { add(NativeToolSpec(it, AgentToolAction.Command, true, false)) }
    add(NativeToolSpec("WebSearch", AgentToolAction.Read, true, false))
}
