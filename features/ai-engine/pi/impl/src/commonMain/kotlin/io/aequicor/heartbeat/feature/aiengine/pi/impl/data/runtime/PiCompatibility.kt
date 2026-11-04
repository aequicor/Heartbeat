package io.aequicor.heartbeat.feature.aiengine.pi.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this Pi connector build. The facade assesses candidate evidence against it itself
 * and never treats an inspection report as a permit. A target without generated build facts has no established
 * policy, so it reports null instead of inventing a protocol, schema or artifact identity.
 */
internal fun interface PiCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and no guessed Pi contract. */
internal object UnsupportedPiCompatibility : PiCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
