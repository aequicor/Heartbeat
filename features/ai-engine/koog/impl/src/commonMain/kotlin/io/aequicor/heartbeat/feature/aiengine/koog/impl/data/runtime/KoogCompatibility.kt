package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this embedded build. The facade assesses candidate evidence against it itself and
 * never treats an inspection report as a permit. A target without generated build facts has no established policy,
 * so it reports null instead of inventing a version, protocol or schema.
 */
internal fun interface KoogCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and the legacy path keeps its own result. */
internal object UnsupportedKoogCompatibility : KoogCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
