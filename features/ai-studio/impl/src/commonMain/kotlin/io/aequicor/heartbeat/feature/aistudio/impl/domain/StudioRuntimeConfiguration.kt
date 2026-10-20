package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange

/** Profile-owned configuration operations shared by every studio screen. */
interface StudioRuntimeConfiguration {
    /** Switches an idle chat to a new route; the profile retains the operation beyond the screen waiter. */
    suspend fun switchEngine(sessionId: String, change: io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch) {
        error("Engine replacement is unavailable")
    }

    /**
     * Applies a parameter in profile-owned work; a screen waiter may detach without cancelling the change. Returns
     * `true` for an acknowledged change or an already applied value, `false` for refusal or a concurrent change.
     */
    suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean {
        error("Session configuration is unavailable")
    }
}
