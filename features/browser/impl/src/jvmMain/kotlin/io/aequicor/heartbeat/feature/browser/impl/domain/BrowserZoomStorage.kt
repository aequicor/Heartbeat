package io.aequicor.heartbeat.feature.browser.impl.domain

/** Profile-owned page zoom presets keyed by canonical hostname, shared across schemes and ports as in Chrome. */
internal interface BrowserZoomStorage {
    suspend fun load(): Map<String, Int>

    /** Setting the default level removes the host's preference. Writes serialize with [clear]. */
    suspend fun save(host: String, percent: Int)

    /** Explicit browser data removal forgets zoom preferences as well. */
    suspend fun clear()
}
