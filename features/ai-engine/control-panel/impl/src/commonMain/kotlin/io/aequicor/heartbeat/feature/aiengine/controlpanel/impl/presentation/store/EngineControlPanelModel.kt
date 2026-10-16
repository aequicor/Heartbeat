package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.di.scope.EngineControlScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** The engine a panel graph shows; null in the graph of the engine list. */
data class EngineControlTarget(val engine: EngineId?)

/** A configuration page of the engine. */
@Immutable
data class PageItemUi(val id: String, val title: TextUi)

/**
 * One engine's panel: its management ([panel], null while engine management is off), the local input of the
 * management block and the pages of its configuration. [isConfigurationOff] while engine configuration is off,
 * [isMissing] when the engine is not registered (any more).
 */
@Immutable
data class EngineControlPanelState(
    val engine: String,
    val title: String = "",
    val panel: EnginePanelUi? = null,
    /** Launch settings being edited; null shows the saved ones. */
    val launchDraft: LaunchDraftUi? = null,
    /** A code pasted from a sign-in page, kept until sent or its sign-in prompt ends. Never logged. */
    val loginCode: String = "",
    /** An action waiting for confirmation (uninstall, an unverified update, sign-out). */
    val confirmAction: EngineActionUi? = null,
    val isSaving: Boolean = false,
    /** The failure of the last operation, until dismissed. */
    val failure: FailureUi? = null,
    val pages: ImmutableList<PageItemUi> = persistentListOf(),
    val isConfigurationOff: Boolean = false,
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val hasLoadFailure: Boolean = false,
) : MVIState {
    override fun toString(): String = "EngineControlPanelState(engine=$engine)"
}

/** User events of the panel. */
sealed interface EngineControlPanelIntent : MVIIntent {
    /** Restarts a failed observation. */
    data object RetryLoad : EngineControlPanelIntent

    /** Hides the failure of the last operation. */
    data object DismissError : EngineControlPanelIntent
}

/** The panel has no one-off actions yet. */
sealed interface EngineControlPanelAction : MVIAction

private typealias PanelContext =
    PipelineContext<EngineControlPanelState, EngineControlPanelIntent, EngineControlPanelAction>

/** Panel store of one engine: reflects the panel machine and keeps only local input. */
@SingleIn(EngineControlScope::class)
@Inject
class EngineControlPanelModel(
    private val machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    target: EngineControlTarget,
    @ForScope(EngineControlScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("EngineControlPanelModel")
    private val engineId = requireNotNull(target.engine) { "A panel graph is created for one engine" }

    val store = factory.create<EngineControlPanelState, EngineControlPanelIntent, EngineControlPanelAction>(
        "EngineControlPanel",
        EngineControlPanelState(engineId.value).reflect(engineId, machine.state.value),
        onError = { copy(isLoading = false, isSaving = false, failure = FailureUi.Unknown) },
    ) {
        init { sendTo(machine, EngineControlIntent.Public.Start) }
        reflect(machine) { reflect(engineId, it) }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope.coroutineScope)
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.handle(intent: EngineControlPanelIntent) {
        when (intent) {
            EngineControlPanelIntent.RetryLoad -> sendTo(machine, EngineControlIntent.Public.RetryLoad)

            EngineControlPanelIntent.DismissError -> sendTo(machine, EngineControlIntent.Public.DismissError)

            // Only a launch draft changes the panel (its errors); other input is local text or a flag.
            is EngineManagementIntent.EngineInput -> updateState {
                if (intent.isLaunchEdit) edit(intent).reflect(engineId, machine.state.value) else edit(intent)
            }

            is EngineManagementIntent.EngineRequest -> request(intent)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.request(intent: EngineManagementIntent.EngineRequest) {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        if (intent is EngineManagementIntent.RequestEngineAction && screen.needsConfirmation(intent.action)) {
            updateState { copy(confirmAction = intent.action) }
            return
        }
        val command = screen.commandFor(intent)
        if (command == null) {
            log.w { "ignore ${intent::class.simpleName.orEmpty()} without an applicable engine command" }
            return
        }
        val result = sendTo(machine, EngineControlIntent.Public.Apply(ControlOperation.Manage(engineId, command)))
        // Local input is cleared only once the machine took it; a rejected one stays to be sent again.
        if (result == SendResult.Accepted) updateState { sent(intent) }
    }
}

/** The panel of [engine] with the machine [state], keeping local input while it still applies. */
internal fun EngineControlPanelState.reflect(engine: EngineId, state: EngineControlState): EngineControlPanelState {
    val active = state as? EngineControlState.Active ?: return copy(isLoading = true, loginCode = "")
    val snapshot = active.snapshot
    val base = copy(
        isLoading = snapshot == null && active.loadFailure == null,
        hasLoadFailure = active.loadFailure != null,
        isSaving = active.pending != null,
        failure = active.failed?.failure?.toUi(),
    )
    if (snapshot == null) return base.copy(panel = null, loginCode = "", confirmAction = null)
    val managed = snapshot.management.engines.firstOrNull { it.descriptor.id == engine }
    val config = snapshot.configuration.engine(engine)
    val panel = managed?.takeIf { snapshot.management.isEnabled }
        ?.toPanel(launchDraft, snapshot.management.platform)
    return base.copy(
        title = managed?.descriptor?.title.orEmpty(),
        panel = panel,
        launchDraft = launchDraft.takeIf { panel != null },
        loginCode = loginCode.takeIf { panel?.job?.phase is JobPhaseUi.AwaitingCode }.orEmpty(),
        confirmAction = confirmAction.takeIf { panel != null && panel.job?.isRunning != true },
        pages = config?.schema?.pages.orEmpty().map { PageItemUi(it.id, it.title.toUi()) }.toImmutableList(),
        isConfigurationOff = !snapshot.configuration.isEnabled,
        isMissing = managed == null,
    )
}

/** The panel's failure text of an operation failure. */
internal fun ControlFailure.toUi(): FailureUi = when (this) {
    is ControlFailure.Engine -> failure.toUi()
    is ControlFailure.Config -> FailureUi.Unknown
}
