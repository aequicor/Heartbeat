package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.organismPermissionCounts
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The library screen: safety information and harnesses in name order. */
@Immutable
internal data class HarnessLibraryState(
    val phase: PhaseUi = PhaseUi.Loading,
    val harnesses: ImmutableList<HarnessRowUi> = persistentListOf(),
    val isCodeSupported: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState

/** Controls of the library screen. */
internal sealed interface HarnessLibraryIntent : MVIIntent {
    /** Turns a whole harness on or off. */
    data class SetEnabled(val id: String, val isEnabled: Boolean) : HarnessLibraryIntent

    /** Opens the details of a harness. */
    data class Open(val id: String) : HarnessLibraryIntent

    /** Reads the library again after a failed load. */
    data object Reload : HarnessLibraryIntent

    /** Hides a save problem the user has seen. */
    data object DismissError : HarnessLibraryIntent
}

/** One-off navigation the screen's component performs within its own lifecycle. */
internal sealed interface HarnessLibraryAction : MVIAction {
    /** Opens the details of a harness. */
    data class OpenDetail(val id: String) : HarnessLibraryAction
}

private typealias LibraryPipeline = PipelineContext<HarnessLibraryState, HarnessLibraryIntent, HarnessLibraryAction>

/** Mirrors the profile library; every change is a machine command, so the list outlives the screen. */
internal class HarnessLibraryModel(
    private val machine: HarnessMachine,
    runs: HarnessRunsMachine,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /** Requests of this screen; only their failures are shown here, agent writes report to the agent. */
    private val issued = MutableStateFlow<Set<RequestId>>(emptySet())

    private val screen = factory.createScreen<HarnessLibraryState, HarnessLibraryIntent, HarnessLibraryAction>(
        "HarnessLibrary",
        HarnessLibraryState().reflectLibrary(machine.state.value, runs.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            if (output is HarnessOutput.StorageFailed && output.requestId in issued.value) {
                updateState { copy(isSaveFailed = true) }
            }
        }) { reflectLibrary(it, runs.state.value) }
        reflect(runs) { reflectLibrary(machine.state.value, it) }
        reduce { intent -> handle(intent) }
    }
    val store = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(scope)
    }

    private fun issue(): RequestId = request().also { id ->
        issued.update { (it + id).toList().takeLast(ISSUED_KEPT).toSet() }
        log.v { "Harness library command issued" }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun LibraryPipeline.handle(intent: HarnessLibraryIntent) {
        val ready = machine.state.value as? HarnessState.Ready
        when (intent) {
            is HarnessLibraryIntent.SetEnabled -> ready?.harnesses?.firstOrNull { it.harness.id.value == intent.id }
                ?.let { entry ->
                    val revision = entry.harness.revision
                    val command = HarnessIntent.Internal.SetEnabled(
                        issue(),
                        HarnessId(intent.id),
                        intent.isEnabled,
                        revision,
                        clock.now(),
                    )
                    sendTo(machine, command)
                }

            is HarnessLibraryIntent.Open -> action(HarnessLibraryAction.OpenDetail(intent.id))

            HarnessLibraryIntent.Reload -> {
                updateState { copy(isSaveFailed = false) }
                // Reload retries a failed load and refreshes a loaded library; a disabled feature rejects it.
                sendTo(machine, HarnessIntent.Public.Reload(issue()))
            }

            HarnessLibraryIntent.DismissError -> updateState { copy(isSaveFailed = false) }
        }
    }
}

private fun HarnessLibraryState.reflectLibrary(state: HarnessState, runs: HarnessRunsState): HarnessLibraryState {
    val ready = state as? HarnessState.Ready ?: return copy(phase = state.phase())
    val counts = if (ready.isSuspended) emptyMap() else runs.organismPermissionCounts()
    return copy(
        phase = PhaseUi.Ready,
        harnesses = ready.harnesses.sortedBy { it.harness.name.value }.map {
            it.toRow().copy(awaitingPermissions = counts[it.harness.id] ?: 0)
        }.toImmutableList(),
        isCodeSupported = ready.isRuntimeAvailable,
    )
}

private val log = Log.tag("HarnessHost")

internal fun request(): RequestId = RequestId(Uuid.random().toHexString())

/** Own request ids a screen remembers to recognise the outputs answering it. */
internal const val ISSUED_KEPT = 32
