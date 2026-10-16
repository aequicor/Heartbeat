package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget

/** What a script asks a helper to do; [adaptive] lets the host choose its model. Texts are never rendered. */
internal data class HarnessSpawnTask(val title: String, val prompt: String, val adaptive: AdaptiveAgent? = null) {
    override fun toString(): String = "HarnessSpawnTask(adaptive=${adaptive != null})"
}

/** Host checks captured script authority before admitting a profile-owned helper. */
internal interface HarnessSessionSpawner {
    suspend fun spawn(
        owner: HarnessInstanceTarget,
        parent: SessionRef?,
        task: HarnessSpawnTask,
        origin: HarnessCallOrigin,
    ): ScriptHelper

    /** Non-blocking retirement schedules host cleanup; it never calls author code. */
    fun retire(owner: HarnessInstanceTarget)
}
