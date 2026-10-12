package io.aequicor.heartbeat.feature.researchchat.impl.domain

import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction

/** Required profile-owned recovery; disabling browser flags revokes work rather than removing its binding. */
internal interface ResearchSourceRecovery {
    /** Invalidates unfinished native operations restored from persistent research state. */
    suspend fun restore()

    /** Executes an explicit human action and settles only the matching resource attempt. */
    suspend fun recover(sessionId: String, resourceId: String, action: ResearchRecoveryAction)
}
