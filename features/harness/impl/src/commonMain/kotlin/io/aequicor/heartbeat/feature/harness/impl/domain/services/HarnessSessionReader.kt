package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHistory
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget

/** Host reads filtered by live activation, with no implicit discovery or native session opening. */
internal interface HarnessSessionReader {
    suspend fun list(owner: HarnessInstanceTarget): List<ScriptSession>
    suspend fun history(owner: HarnessInstanceTarget, session: SessionRef, page: HistoryPageRequest): ScriptHistory
}
