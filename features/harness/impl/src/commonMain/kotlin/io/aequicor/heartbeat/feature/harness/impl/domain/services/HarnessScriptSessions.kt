package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHistory
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSession
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.scheduler.api.WakeId

/** Complete instance facade; ancestry is captured synchronously before the first host suspension. */
internal class HarnessScriptSessions(
    private val owner: HarnessInstanceTarget,
    private val origins: HarnessCallOrigins,
    private val reader: HarnessSessionReader,
    private val sender: HarnessSessionSender,
    private val spawner: HarnessSessionSpawner,
) : ScriptSessions {
    override suspend fun list(): List<ScriptSession> = reader.list(owner)

    override suspend fun history(session: SessionRef, page: HistoryPageRequest): ScriptHistory =
        reader.history(owner, session, page)

    override suspend fun send(session: SessionRef, text: String): WakeId {
        val origin = origins.current()
        return sender.send(owner, session, text, origin)
    }

    override suspend fun spawn(parent: SessionRef?, title: String, prompt: String): ScriptHelper {
        val origin = origins.current()
        return spawner.spawn(owner, parent, title, prompt, origin)
    }

    fun close() = spawner.retire(owner)
}

/** Builds a private facade only; construction performs no external IO. */
internal fun interface HarnessScriptSessionsFactory {
    fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptSessions
}
