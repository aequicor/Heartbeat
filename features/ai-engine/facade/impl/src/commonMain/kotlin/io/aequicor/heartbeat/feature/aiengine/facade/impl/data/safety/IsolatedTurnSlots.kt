package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.logging.Log
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
 * The profile's service sessions: at most [capacity] are open at once, including stopped ones still awaiting their
 * termination; at most [queueLimit] callers wait up to [queueWait] for a slot. Every change is reflected in [load].
 * Other kinds of isolated service turns keep their own instance, named by [label] in logs.
 */
internal class IsolatedTurnSlots(
    capacity: Int,
    private val queueLimit: Int = MAX_QUEUED_TURNS,
    private val queueWait: Duration = QUEUE_WAIT,
    private val label: String = "Isolated turn",
    tag: String = "IsolatedTurns",
) : ServiceTurnSlots {

    private val log = Log.tag(tag)
    private val permits = Semaphore(capacity)
    private val state = MutableStateFlow(IsolatedTurnLoad(capacity))

    val load: StateFlow<IsolatedTurnLoad> = state.asStateFlow()

    /**
     * Waits in the bounded queue; false when it is full or no slot frees up in time. Cancellation propagates. The
     * permit is recorded inside the timeout block: a timeout firing after the semaphore handed it over keeps it
     * owned, and a cancellation arriving then returns it, so no slot is ever lost.
     */
    override suspend fun acquire(): Boolean {
        val before = state.getAndUpdate { if (it.queued >= queueLimit) it else it.copy(queued = it.queued + 1) }
        if (before.queued >= queueLimit) {
            log.w { "$label queue is full: queued=${before.queued} running=${before.running}" }
            return false
        }
        var isAcquired = false
        var isReturned = false
        try {
            withTimeoutOrNull(queueWait) {
                permits.acquire()
                isAcquired = true
            }
            isReturned = true
        } finally {
            if (isAcquired && !isReturned) {
                permits.release()
                isAcquired = false
            }
            state.update {
                it.copy(queued = it.queued - 1, running = if (isAcquired) it.running + 1 else it.running)
            }
        }
        if (!isAcquired) log.w { "$label waited too long for a slot: ${state.value.summary()}" }
        return isAcquired
    }

    /** A dismissed turn keeps its slot while its session has not confirmed termination. */
    override fun startFinishing() {
        state.update { it.copy(running = it.running - 1, finishing = it.finishing + 1) }
        log.i { "$label awaits its session termination: ${state.value.summary()}" }
    }

    /** Releases a running slot. */
    override fun releaseRunning() = release { copy(running = running - 1) }

    /** Releases a finishing slot; [isAbandoned] when termination was never confirmed. */
    override fun releaseFinishing(isAbandoned: Boolean) = release {
        copy(finishing = finishing - 1, abandoned = if (isAbandoned) abandoned + 1 else abandoned)
    }

    /** A user-facing summary for refusals; numbers only, no supplied content. */
    fun describe(): String = state.value.let {
        "Выполняется ${it.running + it.finishing} из ${it.capacity}, в очереди ${it.queued}."
    }

    private fun release(change: IsolatedTurnLoad.() -> IsolatedTurnLoad) {
        state.update(change)
        permits.release()
        log.v { "$label slot released: ${state.value.summary()}" }
    }
}

private fun IsolatedTurnLoad.summary() =
    "running=$running queued=$queued finishing=$finishing abandoned=$abandoned capacity=$capacity"

private const val MAX_QUEUED_TURNS = 16
private val QUEUE_WAIT = 2.minutes

/** Capacity accounting includes sessions whose cleanup is still running. */
internal data class IsolatedTurnLoad(
    val capacity: Int,
    val running: Int = 0,
    val queued: Int = 0,
    val finishing: Int = 0,
    val abandoned: Int = 0,
)
