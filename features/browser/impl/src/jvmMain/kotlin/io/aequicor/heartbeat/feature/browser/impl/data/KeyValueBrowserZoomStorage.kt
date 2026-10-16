package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BROWSER_DEFAULT_ZOOM
import io.aequicor.heartbeat.feature.browser.api.BrowserZoomLevels
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserZoomStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val ZoomSpec = KeyValueSpec("browser_zoom")
private val ZoomKey = jsonKey("hosts", MapSerializer(String.serializer(), Int.serializer()))

/** Keeps browsing addresses out of logs; the datastore owns persistence, retention and profile cleanup. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueBrowserZoomStorage(
    @ForScope(ProfileScope::class) stores: DataStores,
) : BrowserZoomStorage {
    private val log = Log.tag("BrowserZoomStorage")
    private val store = stores.keyValue(ZoomSpec)
    private val lock = Mutex()

    override suspend fun load(): Map<String, Int> = lock.withLock {
        log.v { "Loading page zoom preferences" }
        read()
    }

    override suspend fun save(host: String, percent: Int): Unit = lock.withLock {
        log.v { "Saving page zoom preference" }
        require(isBrowserUrlAllowed("https://$host")) { "Expected a web host" }
        require(percent in BrowserZoomLevels) { "Invalid page zoom preset" }
        val current = read()
        val next = if (percent == BROWSER_DEFAULT_ZOOM) current - host else current + (host to percent)
        if (next != current) store.set(ZoomKey, next)
    }

    override suspend fun clear(): Unit = lock.withLock {
        log.v { "Clearing page zoom preferences" }
        store.remove(ZoomKey)
    }

    private suspend fun read(): Map<String, Int> = store.get(ZoomKey).orEmpty().filter { (host, percent) ->
        isBrowserUrlAllowed("https://$host") && percent in BrowserZoomLevels && percent != BROWSER_DEFAULT_ZOOM
    }
}
