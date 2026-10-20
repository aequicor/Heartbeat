package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHarnesses
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Connects an organism's harnesses to its cell sessions through the harness machine, and lets them go when the cell
 * ends. Only a committed connection (or the exact receipt of the write) counts: a pending write, an unanswered one or
 * a refused one is a failure, so a cell never turns in a smaller environment than the user chose. A connection
 * already in the wanted state is not written again, which makes a restart or a repeated turn harmless. A library that
 * is still loading (after a restart it starts beside the organisms) or busy with another change is awaited; each
 * harness gets fifteen seconds in all. The library takes one attachment write at a time, so the cells of the profile
 * queue behind one lock held from the last look at the library until the write's receipt; a connection found in the
 * wanted state is trusted only while no write is in flight, since that write may be the removal of it. Harness ids
 * and titles are never logged.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class MachineCellHarnesses(private val machines: MachineRegistry, private val toggles: FeatureToggles) :
    CellHarnesses {
    private val log = Log.tag("CellHarnesses")
    private val writes = Mutex()

    override suspend fun connect(session: SessionRef, harnesses: Set<String>) {
        if (harnesses.isEmpty()) return
        check(toggles.get(HarnessEnabled)) { "Harnesses are turned off" }
        for (harness in harnesses.sorted()) {
            val change = Change(session, HarnessId(harness), isAttached = true)
            val outcome = withTimeoutOrNull(CONFIRMATION) { settle(change) }
                ?: Outcome.Failed("no confirmation within $CONFIRMATION")
            check(outcome == Outcome.Done) { "A harness chosen for the organism could not be connected: $outcome" }
        }
        log.d { "cell session carries the harnesses of its organism count=${harnesses.size}" }
    }

    override suspend fun disconnect(session: SessionRef, harnesses: Set<String>, isReleased: suspend () -> Boolean) {
        if (harnesses.isEmpty()) return
        if (!toggles.get(HarnessEnabled)) {
            log.d { "harnesses are turned off; a cell session keeps its stored connections" }
            return
        }
        val kept = mutableListOf<String>()
        var skipped = 0
        val isSettled = withTimeoutOrNull(CONFIRMATION * harnesses.size) {
            for (harness in harnesses.sorted()) {
                when (val outcome = settle(Change(session, HarnessId(harness), isAttached = false), isReleased)) {
                    Outcome.Done -> Unit
                    Outcome.Skipped -> skipped++
                    is Outcome.Failed -> kept += outcome.reason
                }
            }
        } != null
        if (!isSettled) kept += "no confirmation within ${CONFIRMATION * harnesses.size}"
        check(kept.isEmpty()) { "Harness connections of a cell session were kept: ${kept.distinct().joinToString()}" }
        log.d { "cell session settled its harness connections count=${harnesses.size} skipped=$skipped" }
    }

    /** Waits for the library, then settles [change] in it while [isWanted]; the caller bounds the time. */
    private suspend fun settle(change: Change, isWanted: suspend () -> Boolean = { true }): Outcome {
        val library = machines.observe(HarnessMachineKey).filterNotNull().first()
        var busy = 0
        var outcome: Outcome? = null
        while (outcome == null && busy < BUSY_ATTEMPTS) {
            when (val step = next(library, change, isWanted)) {
                is Step.Final -> outcome = step.outcome
                Step.Retry -> Unit
                Step.Busy -> busy++
            }
        }
        return outcome ?: Outcome.Failed("the library stayed busy")
    }

    /** One look at the library: the end of the change, a wait for the library to move, or one write. */
    private suspend fun next(library: Library, change: Change, isWanted: suspend () -> Boolean): Step {
        val state = library.state.value
        val refusal = refusal(library, state)
        val ready = state as? HarnessState.Ready
        return when {
            // A removal for a session a newer turn has reopened must not happen.
            !isWanted() -> Step.Final(Outcome.Skipped)

            refusal != null -> Step.Final(Outcome.Failed(refusal))

            // It starts beside the organisms after a restart: not a refusal, only not yet.
            ready == null -> awaiting(library, state)

            change.isAttached && !isUsable(ready, change.harness) ->
                Step.Final(Outcome.Failed("the harness is gone, off or removed"))

            // The connection as committed is about to change: judge it only once that write is done.
            ready.isWriting(change) -> awaiting(library, ready)

            ready.isConnected(change) == change.isAttached -> settled(change)

            isBusy(ready, change.harness) -> awaiting(library, ready)

            else -> write(library, ready, change, isWanted)
        }
    }

    /** Why a library in [state] can never take the change, or null while it can, now or once it has loaded. */
    private suspend fun refusal(library: Library, state: HarnessState): String? = when {
        !toggles.get(HarnessEnabled) -> "harnesses are turned off"
        machines.find(HarnessMachineKey) !== library -> "the library was replaced"
        state.isSuspended -> "the library is suspended"
        state is HarnessState.Failed -> "the library could not be loaded"
        else -> null
    }

    /**
     * The connection is as wanted. A connection that is about to be removed by a write that has not changed the state
     * yet would look the same, so a holder of the write lock is waited for and the library looked at again.
     */
    private suspend fun settled(change: Change): Step {
        if (!change.isAttached || !writes.isLocked) return Step.Final(Outcome.Done)
        writes.withLock { Unit }
        return Step.Retry
    }

    private suspend fun awaiting(library: Library, seen: HarnessState): Step {
        log.v { "harness library is not ready for a connection change; waiting for it" }
        library.state.first { it != seen }
        return Step.Retry
    }

    private fun isUsable(ready: HarnessState.Ready, harness: HarnessId): Boolean =
        ready.pending[harness] !is HarnessMutation.Remove &&
            ready.harnesses.any { entry -> entry.harness.id == harness && entry.harness.isEnabled }

    /** Any change in flight that the machine would answer with [HarnessRejection.Busy]. */
    private fun isBusy(ready: HarnessState.Ready, harness: HarnessId): Boolean =
        ready.attachmentWrite != null || ready.isReloading || harness in ready.pending ||
            ready.pending.values.any { it is HarnessMutation.Remove }

    /** Sends one write; a library that moved on while the write waited its turn is looked at again. */
    private suspend fun write(
        library: Library,
        ready: HarnessState.Ready,
        change: Change,
        isWanted: suspend () -> Boolean,
    ): Step = when (val receipt = sendOnce(library, ready, change, isWanted)) {
        Receipt.Saved -> Step.Final(confirmed(library, change))
        is Receipt.Refused -> Step.Final(Outcome.Failed(receipt.reason))
        Receipt.Busy -> Step.Busy
        Receipt.Skipped -> Step.Final(Outcome.Skipped)
        null -> Step.Retry
    }

    private fun confirmed(library: Library, change: Change): Outcome =
        if (library.state.value.isConnected(change) == change.isAttached) {
            log.v { "harness ${if (change.isAttached) "connected to" else "disconnected from"} a cell session" }
            Outcome.Done
        } else {
            Outcome.Failed("the connection changed right after its write")
        }

    /** Sends the write under the lock, unless the library changed since [ready] was read (null) or it is unwanted. */
    private suspend fun sendOnce(
        library: Library,
        ready: HarnessState.Ready,
        change: Change,
        isWanted: suspend () -> Boolean,
    ): Receipt? = writes.withLock {
        when {
            library.state.value !== ready -> null
            !isWanted() -> Receipt.Skipped
            else -> submit(library, change)
        }
    }

    /** Subscribes before sending, so a receipt that arrives at once is never missed. */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun submit(library: Library, change: Change): Receipt = coroutineScope {
        val request = RequestId(Uuid.random().toHexString())
        val receipt = CompletableDeferred<Receipt>()
        val outputs = launch(start = CoroutineStart.UNDISPATCHED) {
            library.outputs.collect { output -> output.receipt(request, change)?.let(receipt::complete) }
        }
        val states = launch(start = CoroutineStart.UNDISPATCHED) {
            library.state.collect { state ->
                if (state.isConnected(change) == change.isAttached) receipt.complete(Receipt.Saved)
            }
        }
        try {
            val intent = if (change.isAttached) {
                HarnessIntent.Public.Attach(request, change.harness, change.session)
            } else {
                HarnessIntent.Public.Detach(request, change.harness, change.session)
            }
            val result = library.send(intent)
            if (result == SendResult.Accepted) receipt.await() else Receipt.Refused("the request was $result")
        } finally {
            outputs.cancel()
            states.cancel()
        }
    }
}

private typealias Library = MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>

/** The connection of [harness] to [session] to be made ([isAttached]) or removed. */
private data class Change(val session: SessionRef, val harness: HarnessId, val isAttached: Boolean)

/** Whether the one attachment write in flight is for the connection of [change]. */
private fun HarnessState.Ready.isWriting(change: Change): Boolean =
    attachmentWrite?.let { it.id == change.harness && it.session == change.session } == true

/** Whether a settled library holds the connection; a library that is not settled holds none. */
private fun HarnessState.isConnected(change: Change): Boolean =
    this is HarnessState.Ready && change.harness in attachments[change.session].orEmpty()

/** How one connection change ended: done, or why it was not (never an id or a title). */
private sealed interface Outcome {
    data object Done : Outcome

    /** A removal no longer wanted: a newer turn uses the session again. */
    data object Skipped : Outcome

    data class Failed(val reason: String) : Outcome {
        override fun toString(): String = reason
    }
}

/** What one look at the library led to. */
private sealed interface Step {
    data class Final(val outcome: Outcome) : Step

    data object Retry : Step

    data object Busy : Step
}

/** How one attachment write ended. */
private sealed interface Receipt {
    data object Saved : Receipt

    data object Busy : Receipt

    /** The change was no longer wanted when its turn came; nothing was written. */
    data object Skipped : Receipt

    data class Refused(val reason: String) : Receipt
}

private fun HarnessOutput.receipt(request: RequestId, change: Change): Receipt? = when (this) {
    is HarnessOutput.Attached ->
        Receipt.Saved.takeIf { change.isAttached && requestId == request && isOf(change, id, session) }

    is HarnessOutput.Detached ->
        Receipt.Saved.takeIf { !change.isAttached && requestId == request && isOf(change, id, session) }

    is HarnessOutput.Rejected -> if (requestId == request) refusal(reason) else null

    is HarnessOutput.StorageFailed ->
        Receipt.Refused("the library could not store it").takeIf { requestId == request }

    is HarnessOutput.Created,
    is HarnessOutput.Updated,
    is HarnessOutput.Deleted,
    is HarnessOutput.ItemActivated,
    is HarnessOutput.ItemFailed,
    is HarnessOutput.RuntimeFailed,
    -> null
}

private fun isOf(change: Change, harness: HarnessId, session: SessionRef): Boolean =
    change.harness == harness && change.session == session

private fun refusal(reason: HarnessRejection): Receipt =
    if (reason == HarnessRejection.Busy) Receipt.Busy else Receipt.Refused("the library refused it ($reason)")

private val CONFIRMATION = 15.seconds
private const val BUSY_ATTEMPTS = 3
