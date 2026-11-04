package io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements

/**
 * Researched launch policy of this Claude connector build. The facade assesses candidate evidence against it itself
 * and never treats an inspection report as a permit. Only a desktop host with a published Claude Code release target
 * has an established policy, so every other target reports null instead of inventing a protocol, schema or artifact
 * identity.
 */
internal fun interface ClaudeCompatibility {
    fun requirements(): EngineCompatibilityRequirements?
}

/** Absence of a platform policy is explicit: no admission, and no guessed Claude contract. */
internal object UnsupportedClaudeCompatibility : ClaudeCompatibility {
    override fun requirements(): EngineCompatibilityRequirements? = null
}
