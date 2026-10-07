package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/**
 * Profile-owned durable restrictions for an exact accepted or prospective request. Restrict returns only after
 * an atomic monotone merge is durable; callers must await it before native submission or delivery admission.
 * Missing is neutral. IO/corruption must throw, never masquerade as missing. No terminal observation can erase
 * restrictions: persisted events and queued callbacks may still reference the request after its turn finishes.
 */
internal interface HarnessRequestAncestry {
    suspend fun restrict(session: SessionRef, request: RequestId, origin: HarnessCallOrigin)
    suspend fun lookup(session: SessionRef, request: RequestId): HarnessCallOrigin?
}
