package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId

/**
 * Reads current host-verified session activation without IO. Adapters resolve source-project/helper provenance
 * before publishing their snapshot; missing or expired proof must deny access. A workspace or SessionRef alone
 * is not authority. Dispatch checks again immediately before entering author code.
 */
internal fun interface HarnessSessionAdmission {
    fun allows(harness: HarnessId, session: SessionRef): Boolean
}
