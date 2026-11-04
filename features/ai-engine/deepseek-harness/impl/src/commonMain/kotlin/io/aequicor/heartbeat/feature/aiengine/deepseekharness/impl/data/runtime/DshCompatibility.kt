package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this DSH connector build. DSH is an external runtime Heartbeat neither installs nor
 * pins, so admission is based on an explicitly marked external fingerprint rather than a sealed artifact digest;
 * the facade assesses candidate evidence against this policy itself and never treats an inspection report as a
 * permit. Only a desktop target with generated build facts has an established policy, so every other target
 * reports null instead of inventing a protocol, schema or provenance.
 */
internal fun interface DshCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and no guessed DSH contract. */
internal object UnsupportedDshCompatibility : DshCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
