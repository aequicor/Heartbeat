package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget

/** Host checks captured script authority before admitting a profile-owned helper. */
internal interface HarnessSessionSpawner {
    suspend fun spawn(
        owner: HarnessInstanceTarget,
        parent: SessionRef?,
        title: String,
        prompt: String,
        origin: HarnessCallOrigin,
    ): ScriptHelper

    /** Non-blocking retirement schedules host cleanup; it never calls author code. */
    fun retire(owner: HarnessInstanceTarget)
}
