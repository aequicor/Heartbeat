package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopDocumentTeardown
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Document views of every profile, from creation until their browsers have closed natively. */
internal interface DesktopDocumentViews {
    /** [panel] exists until [closed] confirms its browsers are gone. */
    fun register(panel: DesktopDocumentPanel)

    /** [panel]'s browsers have closed. */
    fun closed(panel: DesktopDocumentPanel)
}

/**
 * [DesktopDocumentViews] of the app, so the runtime's teardown can close every document browser before CEF is
 * disposed. Registration happens on EDT; closing waits off it.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<DesktopDocumentTeardown>())
@ContributesBinding(AppScope::class, binding = binding<DesktopDocumentViews>())
@Inject
internal class DesktopDocumentBrowsers(private val dispatchers: DispatcherProvider) :
    DesktopDocumentTeardown,
    DesktopDocumentViews {
    private val log = Log.tag("DesktopDocumentBrowsers")
    private val monitor = Any()
    private val panels = mutableSetOf<DesktopDocumentPanel>()

    override fun register(panel: DesktopDocumentPanel) {
        synchronized(monitor) { panels += panel }
    }

    override fun closed(panel: DesktopDocumentPanel) {
        synchronized(monitor) { panels -= panel }
    }

    override suspend fun closeDocuments() {
        val open = synchronized(monitor) { panels.toList() }
        if (open.isEmpty()) return
        log.i { "Closing ${open.size} document browsers" }
        withContext(dispatchers.main) { open.forEach(DesktopDocumentPanel::release) }
        val isClosed = withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { open.forEach { it.awaitClosed() } } != null
        check(isClosed) { "Document browsers are still closing" }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 60_000L
    }
}
