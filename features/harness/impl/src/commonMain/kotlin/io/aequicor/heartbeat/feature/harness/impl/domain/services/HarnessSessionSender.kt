package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.scheduler.api.WakeId

/** Visible sends share wake ownership and quotas; ancestry is captured before any suspending lookup. */
internal fun interface HarnessSessionSender {
    suspend fun send(owner: HarnessInstanceTarget, session: SessionRef, text: String, origin: HarnessCallOrigin): WakeId
}
