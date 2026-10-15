package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewLoad
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The profile's review sessions: at most [capacity] are open at once, including stopped ones still awaiting their
 * termination; at most [queueLimit] callers wait up to [queueWait] for a slot. Every change is reflected in [load].
 */
@SingleIn(ProfileScope::class)
@Inject
internal class AgentSafetyReviewSlots(
    capacity: Int = MAX_PARALLEL_REVIEWS,
    private val queueLimit: Int = MAX_QUEUED_REVIEWS,
    private val queueWait: Duration = QUEUE_WAIT,
) {
    private val log = Log.tag("AgentSafetyReviewer")
    private val permits = Semaphore(capacity)
    private val state = MutableStateFlow(AgentSafetyReviewLoad(capacity))

    val load: StateFlow<AgentSafetyReviewLoad> = state.asStateFlow()

    /** Waits in the bounded queue; false when it is full or no slot frees up in time. Cancellation propagates. */
    suspend fun acquire(): Boolean {
        val before = state.getAndUpdate { if (it.queued >= queueLimit) it else it.copy(queued = it.queued + 1) }
        if (before.queued >= queueLimit) {
            log.w { "Safety review queue is full: queued=${before.queued} running=${before.running}" }
            return false
        }
        var isAcquired = false
        try {
            isAcquired = withTimeoutOrNull(queueWait) { permits.acquire() } != null
        } finally {
            state.update {
                it.copy(queued = it.queued - 1, running = if (isAcquired) it.running + 1 else it.running)
            }
        }
        if (!isAcquired) log.w { "Safety review waited too long for a slot: ${state.value.summary()}" }
        return isAcquired
    }

    /** A dismissed review keeps its slot while its session has not confirmed termination. */
    fun startFinishing() {
        state.update { it.copy(running = it.running - 1, finishing = it.finishing + 1) }
        log.i { "Safety review awaits its session termination: ${state.value.summary()}" }
    }

    /** Releases a running slot. */
    fun releaseRunning() = release { copy(running = running - 1) }

    /** Releases a finishing slot; [isAbandoned] when termination was never confirmed. */
    fun releaseFinishing(isAbandoned: Boolean) = release {
        copy(finishing = finishing - 1, abandoned = if (isAbandoned) abandoned + 1 else abandoned)
    }

    /** A user-facing summary for refusals; numbers only, no review content. */
    fun describe(): String = state.value.let {
        "Выполняется ${it.running + it.finishing} из ${it.capacity}, в очереди ${it.queued}."
    }

    private fun release(change: AgentSafetyReviewLoad.() -> AgentSafetyReviewLoad) {
        state.update(change)
        permits.release()
        log.v { "Safety review slot released: ${state.value.summary()}" }
    }
}

/** Exposes the profile's review load to other features. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class AgentSafetyReviewLoadMonitor(slots: AgentSafetyReviewSlots) : AgentSafetyReviewMonitor {
    override val load: StateFlow<AgentSafetyReviewLoad> = slots.load
}

private fun AgentSafetyReviewLoad.summary() =
    "running=$running queued=$queued finishing=$finishing abandoned=$abandoned capacity=$capacity"

private const val MAX_PARALLEL_REVIEWS = 3
private const val MAX_QUEUED_REVIEWS = 16
private val QUEUE_WAIT = 2.minutes
