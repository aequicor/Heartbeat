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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Connects an organism's harnesses to its cell sessions through the harness machine. Only a committed connection (or
 * the exact receipt of the write) counts: a pending write, an unanswered one or a refused one is a failure, so a
 * cell never turns in a smaller environment than the user chose. A connection the session already has is not
 * written again, which makes a restart or a repeated turn harmless. The library takes one attachment write at a
 * time, so the cells of the profile queue behind one lock; a busy library is awaited for at most fifteen seconds per
 * harness. Harness ids and titles are never logged.
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
        val library = checkNotNull(machines.find(HarnessMachineKey)) { "The harness library is not running" }
        for (harness in harnesses.sorted()) {
            val isConnected = writes.withLock {
                withTimeoutOrNull(CONFIRMATION) { attach(library, session, HarnessId(harness)) }
            } == true
            check(isConnected) { "A harness chosen for the organism could not be connected" }
        }
        log.i { "cell session carries the harnesses of its organism count=${harnesses.size}" }
    }

    private suspend fun attach(library: Library, session: SessionRef, harness: HarnessId): Boolean {
        var busy = 0
        while (busy < BUSY_ATTEMPTS) {
            val ready = usable(library, harness) ?: return false
            if (harness in ready.attachments[session].orEmpty()) return true
            val isBusy = ready.attachmentWrite != null || ready.isReloading ||
                ready.pending.values.any { it is HarnessMutation.Remove }
            if (isBusy) {
                log.d { "harness library is busy with another change; waiting for it" }
                library.state.first { it != ready }
                continue
            }
            when (submit(library, session, harness)) {
                Receipt.Saved -> return harness in usable(library, harness)?.attachments?.get(session).orEmpty()
                Receipt.Refused -> return false
                Receipt.Busy -> busy++
            }
        }
        return false
    }

    /** The settled library while the harness still exists and is enabled; null when it cannot be connected now. */
    private suspend fun usable(library: Library, harness: HarnessId): HarnessState.Ready? {
        if (!toggles.get(HarnessEnabled) || machines.find(HarnessMachineKey) !== library) return null
        val ready = library.state.value as? HarnessState.Ready ?: return null
        return ready.takeIf {
            !it.isSuspended && it.pending[harness] !is HarnessMutation.Remove &&
                it.harnesses.any { entry -> entry.harness.id == harness && entry.harness.isEnabled }
        }
    }

    /** Subscribes before sending, so a receipt that arrives at once is never missed. */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun submit(library: Library, session: SessionRef, harness: HarnessId): Receipt = coroutineScope {
        val request = RequestId(Uuid.random().toHexString())
        val receipt = CompletableDeferred<Receipt>()
        val outputs = launch(start = CoroutineStart.UNDISPATCHED) {
            library.outputs.collect { output -> output.receipt(request, harness, session)?.let(receipt::complete) }
        }
        val states = launch(start = CoroutineStart.UNDISPATCHED) {
            library.state.collect { state ->
                if (state is HarnessState.Ready && harness in state.attachments[session].orEmpty()) {
                    receipt.complete(Receipt.Saved)
                }
            }
        }
        try {
            val result = library.send(HarnessIntent.Public.Attach(request, harness, session))
            if (result == SendResult.Accepted) receipt.await() else Receipt.Refused
        } finally {
            outputs.cancel()
            states.cancel()
        }
    }
}

private typealias Library = MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>

/** How one attachment write ended. */
private enum class Receipt { Saved, Busy, Refused }

private fun HarnessOutput.receipt(request: RequestId, harness: HarnessId, session: SessionRef): Receipt? = when {
    this is HarnessOutput.Attached && requestId == request && id == harness && this.session == session -> Receipt.Saved

    this is HarnessOutput.Rejected && requestId == request ->
        if (reason == HarnessRejection.Busy) Receipt.Busy else Receipt.Refused

    this is HarnessOutput.StorageFailed && requestId == request -> Receipt.Refused

    else -> null
}

private val CONFIRMATION = 15.seconds
private const val BUSY_ATTEMPTS = 3
