package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this Codex connector build. The facade assesses candidate evidence against it itself
 * and never treats an inspection report as a permit. A target without generated build facts has no established
 * policy, so it reports null instead of inventing a protocol, schema or artifact identity.
 */
internal fun interface CodexCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and no guessed Codex contract. */
internal object UnsupportedCodexCompatibility : CodexCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
