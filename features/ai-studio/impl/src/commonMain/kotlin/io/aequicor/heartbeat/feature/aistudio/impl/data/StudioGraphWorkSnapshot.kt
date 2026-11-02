package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import kotlinx.serialization.Serializable

/** Immutable assignment, distinct from evolving recovery checkpoints and delivery request ids. */
@Serializable
internal data class StudioGraphWorkSnapshot(
    val parent: SessionRef,
    val workspace: WorkspaceRef?,
    val target: EngineTarget,
    val prompt: String,
    val directive: String,
    val settings: RunSettings,
    /** Requested effort captured with the assignment; native capability validation still applies at first entry. */
    val reasoningEffort: String? = null,
) {
    override fun toString(): String = "StudioGraphWorkSnapshot(***)"
}

internal fun SpawnRequest.graphWork(settings: RunSettings): StudioGraphWorkSnapshot = StudioGraphWorkSnapshot(
    parent,
    workspace,
    target,
    prompt.visible,
    prompt.directive,
    settings.copy(modelId = target.studioModelId()),
)

/** The producer may refresh defaults, but cannot replace a retained assignment or its settings. */
internal fun retainGraphWork(
    previous: StudioGraphWorkSnapshot?,
    incoming: StudioGraphWorkSnapshot?,
): StudioGraphWorkSnapshot? {
    if (previous == null) return incoming
    check(
        incoming == null || incoming.copy(
            settings = previous.settings,
            reasoningEffort = previous.reasoningEffort,
        ) == previous,
    ) {
        "Graph assignment changed during recovery"
    }
    return previous
}
