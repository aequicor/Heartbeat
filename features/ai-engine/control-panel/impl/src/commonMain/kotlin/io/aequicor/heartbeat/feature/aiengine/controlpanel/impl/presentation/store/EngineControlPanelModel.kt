package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
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
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** The engine a panel graph shows; null in the graph of the engine list. */
data class EngineControlTarget(val engine: EngineId?)

/** A configuration page of the engine. */
@Immutable
data class PageItemUi(val id: String, val title: TextUi)

/**
 * One engine's panel. [pages] are the engine's configuration pages; [isConfigurationOff] while engine configuration
 * is off, [isMissing] when the engine is not registered (any more), [failure] — the last failed operation until
 * dismissed.
 */
@Immutable
data class EngineControlPanelState(
    val engine: String,
    val title: String = "",
    val status: EngineStatusUi = EngineStatusUi.On,
    val version: String? = null,
    val pages: ImmutableList<PageItemUi> = persistentListOf(),
    val failure: FailureUi? = null,
    val isConfigurationOff: Boolean = false,
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val hasLoadFailure: Boolean = false,
) : MVIState

/** User events of the panel. */
sealed interface EngineControlPanelIntent : MVIIntent {
    /** Restarts a failed observation. */
    data object RetryLoad : EngineControlPanelIntent

    /** Hides the failure of the last operation. */
    data object DismissError : EngineControlPanelIntent
}

/** The panel has no one-off actions yet. */
sealed interface EngineControlPanelAction : MVIAction

/** Panel store of one engine: reflects the panel machine. */
@SingleIn(EngineControlScope::class)
@Inject
class EngineControlPanelModel(
    machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    target: EngineControlTarget,
    @ForScope(EngineControlScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val engineId = requireNotNull(target.engine) { "A panel graph is created for one engine" }

    val store = factory.create<EngineControlPanelState, EngineControlPanelIntent, EngineControlPanelAction>(
        "EngineControlPanel",
        EngineControlPanelState(engineId.value).reflect(engineId, machine.state.value),
        onError = { copy(isLoading = false, hasLoadFailure = true) },
    ) {
        init { sendTo(machine, EngineControlIntent.Public.Start) }
        reflect(machine) { reflect(engineId, it) }
        reduce { intent ->
            when (intent) {
                EngineControlPanelIntent.RetryLoad -> sendTo(machine, EngineControlIntent.Public.RetryLoad)
                EngineControlPanelIntent.DismissError -> sendTo(machine, EngineControlIntent.Public.DismissError)
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
    }
}

/** The panel of [engine] with the machine [state]. */
internal fun EngineControlPanelState.reflect(engine: EngineId, state: EngineControlState): EngineControlPanelState {
    val active = state as? EngineControlState.Active ?: return copy(isLoading = true)
    val snapshot = active.snapshot
    val base = copy(
        isLoading = snapshot == null && active.loadFailure == null,
        hasLoadFailure = active.loadFailure != null,
        failure = active.failed?.failure?.toUi(),
    )
    if (snapshot == null) return base
    val managed = snapshot.management.engines.firstOrNull { it.descriptor.id == engine }
    val config = snapshot.configuration.engine(engine)
    return base.copy(
        title = managed?.descriptor?.title.orEmpty(),
        status = managed?.statusUi() ?: EngineStatusUi.On,
        version = managed?.versionUi(),
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
