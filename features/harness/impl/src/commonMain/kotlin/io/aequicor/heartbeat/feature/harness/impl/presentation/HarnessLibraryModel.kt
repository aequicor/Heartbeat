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
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
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

/** The library screen: approval level and harnesses in name order. */
@Immutable
internal data class HarnessLibraryState(
    val phase: PhaseUi = PhaseUi.Loading,
    val approval: ApprovalUi = ApprovalUi.Ask,
    val harnesses: ImmutableList<HarnessRowUi> = persistentListOf(),
    val isCodeSupported: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState

/** Controls of the library screen. */
internal sealed interface HarnessLibraryIntent : MVIIntent {
    /** Changes how agent edits are accepted. */
    data class SelectApproval(val approval: ApprovalUi) : HarnessLibraryIntent

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
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /** Requests of this screen; only their failures are shown here, agent writes report to the agent. */
    private val issued = MutableStateFlow<Set<RequestId>>(emptySet())

    val store = factory.create<HarnessLibraryState, HarnessLibraryIntent, HarnessLibraryAction>(
        "HarnessLibrary",
        HarnessLibraryState().reflectLibrary(machine.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            if (output is HarnessOutput.StorageFailed && output.requestId in issued.value) {
                updateState { copy(isSaveFailed = true) }
            }
        }) { reflectLibrary(it) }
        reduce { intent -> handle(intent) }
    }

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
            is HarnessLibraryIntent.SelectApproval -> if (ready != null) {
                val level = intent.approval.toDomain()
                sendTo(machine, HarnessIntent.Public.SetApproval(issue(), level, ready.approvalRevision))
            }

            is HarnessLibraryIntent.SetEnabled -> ready?.harnesses?.firstOrNull { it.harness.id.value == intent.id }
                ?.let { entry ->
                    val revision = entry.harness.revision
                    val command = HarnessIntent.Public.SetEnabled(
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

private fun HarnessLibraryState.reflectLibrary(state: HarnessState): HarnessLibraryState {
    val ready = state as? HarnessState.Ready ?: return copy(phase = state.phase())
    return copy(
        phase = PhaseUi.Ready,
        approval = (ready.approvalWrite?.level ?: ready.approval).toUi(),
        harnesses = ready.harnesses.sortedBy { it.harness.name.value }.map { it.toRow() }.toImmutableList(),
        isCodeSupported = ready.isRuntimeAvailable,
    )
}

private val log = Log.tag("HarnessHost")

internal fun request(): RequestId = RequestId(Uuid.random().toHexString())
