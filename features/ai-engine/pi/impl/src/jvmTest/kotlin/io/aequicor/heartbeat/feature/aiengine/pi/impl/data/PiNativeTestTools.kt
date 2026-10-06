package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier

/** Adapter fixture only; policy and real filesystem classification are tested in facade. */
internal object TestNativeTools : ProfileAgentTools by NoAgentTools {
    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        if (call.covered(context.trust) || context.permissions.request(AgentToolApproval(call.name, call.name))) {
            NativeVerdict.Allow
        } else {
            NativeVerdict.Deny("Declined")
        }
}

internal object TestNativeClassifier : NativeCallClassifier {
    override fun terminatesHost(command: String): Boolean = command.contains("--stop") || command.startsWith("taskkill")
    override suspend fun isWorkspaceEdit(path: String, workspace: String): Boolean =
        path.startsWith("$workspace/") && !path.contains("/.git/")
}
