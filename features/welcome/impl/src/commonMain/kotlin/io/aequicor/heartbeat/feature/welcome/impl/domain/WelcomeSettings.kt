package io.aequicor.heartbeat.feature.welcome.impl.domain

/** Settings needed to decide whether a new session plays its intro. */
fun interface WelcomeSettings {
    /** Reads the effective local preference; failures propagate to the machine. */
    suspend fun isIntroEnabled(): Boolean
}
