package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHistory
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSessions
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionReader
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionSpawner

internal fun unusedScriptSessions(owner: HarnessInstanceTarget, origins: HarnessCallOrigins): HarnessScriptSessions =
    HarnessScriptSessions(
        owner,
        origins,
        UnusedSessionReader,
        { _, _, _, _ -> error("Not used") },
        UnusedSessionSpawner,
    )

private object UnusedSessionReader : HarnessSessionReader {
    override suspend fun list(owner: HarnessInstanceTarget): List<ScriptSession> = error("Not used")
    override suspend fun history(
        owner: HarnessInstanceTarget,
        session: SessionRef,
        page: HistoryPageRequest,
    ): ScriptHistory = error("Not used")
}

private object UnusedSessionSpawner : HarnessSessionSpawner {
    override suspend fun spawn(
        owner: HarnessInstanceTarget,
        parent: SessionRef?,
        title: String,
        prompt: String,
        origin: HarnessCallOrigin,
    ): ScriptHelper = error("Not used")
    override fun retire(owner: HarnessInstanceTarget) = Unit
}
