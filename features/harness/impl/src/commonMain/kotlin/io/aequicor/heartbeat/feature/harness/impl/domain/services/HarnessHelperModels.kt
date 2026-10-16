package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent

/** Who asks for an adaptive helper. */
internal enum class HarnessHelperKind { Workflow, Script }

/** One adaptive helper: its [task] (read by the router, never logged), the author's request and the workspace. */
internal data class HarnessHelperModelRequest(
    val task: String,
    val adaptive: AdaptiveAgent,
    val kind: HarnessHelperKind,
    val workspace: WorkspaceRef?,
) {
    override fun toString(): String = "HarnessHelperModelRequest(kind=$kind)"
}

/** The model configuration of an adaptive helper; a null effort keeps the model's default. */
internal data class HarnessHelperModel(val target: EngineTarget, val reasoningEffort: String?)

/**
 * Chooses the model of an adaptive helper once, before its slot is acquired. Null keeps the host default — the
 * parent's model, or the profile's for a helper without a parent. Never throws except cancellation.
 */
internal fun interface HarnessHelperModels {
    suspend fun choose(request: HarnessHelperModelRequest): HarnessHelperModel?

    companion object {
        /** Keeps every helper on its default model. */
        val Inherit: HarnessHelperModels = HarnessHelperModels { null }
    }
}
