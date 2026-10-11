package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import kotlinx.serialization.json.JsonObject

/** Captures the invocation's ancestry synchronously; a staged or retired instance cannot launch work. */
internal class HarnessScriptWorkflows(
    private val owner: HarnessInstanceTarget,
    private val origins: HarnessCallOrigins,
    private val launcher: HarnessScriptWorkflowLauncher,
) : ScriptWorkflows {
    override suspend fun start(name: ItemName, input: JsonObject): RunId {
        val origin = origins.current()
        check(!origin.isHookRestricted) { "Hooks cannot start workflows" }
        check(owner.access.isActive) { "Script activation is unavailable" }
        return launcher.start(owner, name, input, origin)
    }
}

/** Host admission owns routing, pinning and profile lifetime; JSON input never conveys session authority. */
internal fun interface HarnessScriptWorkflowLauncher {
    suspend fun start(owner: HarnessInstanceTarget, name: ItemName, input: JsonObject, origin: HarnessCallOrigin): RunId
}

/** Pure candidate-local service construction. */
internal fun interface HarnessScriptWorkflowsFactory {
    fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptWorkflows
}
