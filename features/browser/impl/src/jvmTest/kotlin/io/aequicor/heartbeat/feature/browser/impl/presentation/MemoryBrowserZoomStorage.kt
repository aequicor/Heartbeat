package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserZoomStorage

internal class MemoryBrowserZoomStorage : BrowserZoomStorage {
    private var levels = emptyMap<String, Int>()
    override suspend fun load() = levels
    override suspend fun save(host: String, percent: Int) {
        levels = if (percent == 100) levels - host else levels + (host to percent)
    }
    override suspend fun clear() {
        levels = emptyMap()
    }
}
