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
    )
}

/** Unknown extension names cannot inherit a native trust exemption. */
internal fun piNativeAction(name: String): AgentToolAction? = when (name) {
    "read", "grep", "find", "ls" -> AgentToolAction.Read
    "edit", "write" -> AgentToolAction.Edit
    "bash", "powershell" -> AgentToolAction.Command
    else -> null
}
