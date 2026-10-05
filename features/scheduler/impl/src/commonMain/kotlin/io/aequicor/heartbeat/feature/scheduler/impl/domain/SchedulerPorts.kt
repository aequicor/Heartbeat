package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState

/** The profile's scheduler machine, including internal intents. */
internal typealias SchedulerMachine = Machine<SchedulerState, SchedulerIntent, SchedulerOutput>

/** Durable pending wakes of the profile. Notes are never logged. */
internal interface WakeStorage {
    /** Stored wakes; throws when they cannot be read (never an empty list instead). */
    suspend fun load(): List<ScheduledWake>

    /** Replaces the stored wakes. */
    suspend fun save(wakes: List<ScheduledWake>)
}

/** No host can resume the session. */
internal class SessionUnavailableException(message: String) : IllegalStateException(message)
