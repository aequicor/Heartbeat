package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import io.aequicor.heartbeat.feature.scheduler.api.spi.SourceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** Whether the device has a usable network connection; implemented per platform. */
internal interface NetworkStatus {
    /** The connection state now and on every change; may repeat values. */
    fun observe(): Flow<Boolean>

    /** The connection state now, or null when it cannot be determined. */
    suspend fun current(): Boolean?
}

/** Publishes `system.network.available` / `system.network.lost` on transitions, never for the initial state. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class NetworkEventSource(private val status: NetworkStatus) : SchedulerEventSource {
    override fun events(): Flow<SourceEvent> = status.observe()
        .distinctUntilChanged()
        .drop(1)
        .map { isConnected -> SourceEvent(if (isConnected) EventKeys.NetworkAvailable else EventKeys.NetworkLost) }
}
