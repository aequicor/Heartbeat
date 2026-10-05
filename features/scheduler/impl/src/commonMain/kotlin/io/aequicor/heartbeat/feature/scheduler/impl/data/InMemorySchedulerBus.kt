package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Clock

private const val BUFFER = 64

/** The profile bus: a hot stream without replay. Logs key and origin kind of an event, never its payload. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class InMemorySchedulerBus(private val clock: Clock) : SchedulerBus {
    private val log = Log.tag("SchedulerBus")
    private val stream = MutableSharedFlow<BusEvent>(extraBufferCapacity = BUFFER)

    override val events: Flow<BusEvent> = stream.asSharedFlow()

    override suspend fun publish(key: EventKey, origin: EventOrigin, payload: String?): BusEvent {
        val event = BusEvent(key, origin, clock.now(), payload)
        // Session lifecycle fires on every turn and the host already logs turns: only a trace.
        if (key.namespace == EventNamespace.Session) {
            log.v { "publish $key" }
        } else {
            log.v { "publish $key from ${origin::class.simpleName.orEmpty()}" }
        }
        stream.emit(event)
        return event
    }
}
