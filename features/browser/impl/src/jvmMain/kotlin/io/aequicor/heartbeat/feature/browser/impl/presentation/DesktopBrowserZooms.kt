package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BROWSER_DEFAULT_ZOOM
import io.aequicor.heartbeat.feature.browser.api.BrowserZoom
import io.aequicor.heartbeat.feature.browser.api.browserZoomStep
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserZoomStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saved page zoom of the session's [entries], per host. Every change runs under one lock against the latest saved
 * level, so commands issued before an earlier one finished still step from its result. Main-dispatcher confined,
 * like the session; [project] republishes its tabs.
 */
internal class DesktopBrowserZooms(
    private val storage: BrowserZoomStorage,
    private val entries: Map<String, DesktopBrowserEntry>,
    private val isActive: () -> Boolean,
    private val project: () -> Unit,
) {
    private val log = Log.tag("BrowserZooms")
    private val lock = Mutex()
    private var levels: Map<String, Int> = emptyMap()

    var isEnabled: Boolean = false
        private set

    suspend fun setEnabled(isEnabled: Boolean) = lock.withLock {
        if (isEnabled) levels = load()
        this.isEnabled = isEnabled
        refresh()
    }

    /**
     * Steps the host of [url] (or of [entry]'s page). The tab and host are captured first and checked again under
     * the lock; bounds and no-op resets change nothing.
     */
    suspend fun zoom(entry: DesktopBrowserEntry?, direction: BrowserZoom, url: String?) {
        entry ?: return
        val host = browserZoomHost(url ?: entry.surface.pages.value.url) ?: return
        lock.withLock {
            val isTarget = entries[entry.tab.id] === entry && browserZoomHost(entry.surface.pages.value.url) == host
            if (!isActive() || !isEnabled || !isTarget) return@withLock
            val current = levels[host] ?: BROWSER_DEFAULT_ZOOM
            val percent = browserZoomStep(current, direction)
            if (percent == current) return@withLock
            storage.save(host, percent)
            levels = if (percent == BROWSER_DEFAULT_ZOOM) levels - host else levels + (host to percent)
            refresh()
        }
    }

    /** Re-applies levels to each tab's projected page: a raw surface page would drop its certificate decision. */
    private fun refresh() {
        entries.values.forEach { it.update(it.tab.page) }
        project()
    }

    suspend fun clear() = lock.withLock {
        storage.clear()
        levels = emptyMap()
    }

    fun percent(url: String): Int =
        if (isEnabled) levels[browserZoomHost(url)] ?: BROWSER_DEFAULT_ZOOM else BROWSER_DEFAULT_ZOOM

    /** Saved levels are a preference: an unreadable store leaves pages at the default and keeps the browser running. */
    private suspend fun load(): Map<String, Int> = try {
        storage.load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Saved page zoom levels could not be read" }
        emptyMap()
    }
}
