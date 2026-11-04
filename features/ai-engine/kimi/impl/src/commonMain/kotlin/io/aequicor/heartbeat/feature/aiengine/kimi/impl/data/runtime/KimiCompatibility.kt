package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this Kimi connector build. The facade assesses candidate evidence against it itself
 * and never treats an inspection report as a permit. Only a desktop target with generated build facts and the
 * pinned Heartbeat Kimi runtime has an established policy, so every other target reports null instead of inventing
 * a protocol, schema or artifact identity.
 */
internal fun interface KimiCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and no guessed Kimi contract. */
internal object UnsupportedKimiCompatibility : KimiCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
