package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec

/** Built-in native tools; search belongs to the hosted search group. */
internal fun piNativeCatalog(host: HostPlatform): List<NativeToolSpec> {
    if (host == HostPlatform.Android || host == HostPlatform.Ios) return emptyList()
    return listOf(
        NativeToolSpec("read", AgentToolAction.Read, true, true),
        NativeToolSpec("grep", AgentToolAction.Read, false, true),
        NativeToolSpec("find", AgentToolAction.Read, false, true),
        NativeToolSpec("ls", AgentToolAction.Read, false, true),
        NativeToolSpec("edit", AgentToolAction.Edit, true, true),
        NativeToolSpec("write", AgentToolAction.Edit, true, true),
        NativeToolSpec(if (host == HostPlatform.Windows) "powershell" else "bash", AgentToolAction.Command, true, true),
    ) + PiToolGroups.entries.map { group -> NativeToolSpec(group.policyName, group.action, true, false) }
}

/**
 * Tools of Pi's extensions, which the engine configuration turns on: the MCP, code mode and tool search built-ins and
 * the user's own extensions. Each group is one policy entry, on by default, that the tool policy can switch off;
 * its calls never inherit a trust exemption and always pass the safety gate.
 */
internal enum class PiToolGroups(val policyName: String, val action: AgentToolAction) {
    Mcp("mcp", AgentToolAction.Command),
    Codemode("codemode", AgentToolAction.Command),
    ToolSearch("tool_search", AgentToolAction.Read),
    Extension("extension", AgentToolAction.Command),
}

/** The extension group a tool Pi declared belongs to; null for Pi's built-in tools. */
internal fun piToolGroup(name: String): PiToolGroups? = when {
    piNativeAction(name) != null -> null
    name.startsWith("mcp__") -> PiToolGroups.Mcp
    name == "codemode" -> PiToolGroups.Codemode
    name == "tool_search" -> PiToolGroups.ToolSearch
    else -> PiToolGroups.Extension
}

/** Unknown extension names cannot inherit a native trust exemption. */
internal fun piNativeAction(name: String): AgentToolAction? = when (name) {
    "read", "grep", "find", "ls" -> AgentToolAction.Read
    "edit", "write" -> AgentToolAction.Edit
    "bash", "powershell" -> AgentToolAction.Command
    else -> null
}
