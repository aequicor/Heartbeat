package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources

/** Keeps the host process alive until profile flushes and CEF termination have completed. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopBrowserShutdown(private val resources: DesktopBrowserResources) : AppShutdownParticipant {
    override suspend fun shutdown() = resources.shutdown()
}
