package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId

/**
 * Reads current host-verified session activation without IO. Adapters resolve source-project/helper provenance
 * before publishing their snapshot; missing or expired proof must deny access. A workspace or SessionRef alone
 * is not authority. Dispatch checks again immediately before entering author code.
 */
internal fun interface HarnessSessionAdmission {
    /** The harness contributes to the session: it is among the capped active harnesses. */
    fun allows(harness: HarnessId, session: SessionRef): Boolean

    /**
     * The harness covers the session, beyond the active cap: its restricting tool hooks (Deny/Ask) apply there.
     * Overflow-free admissions may keep the default.
     */
    fun restricts(harness: HarnessId, session: SessionRef): Boolean = allows(harness, session)
}
