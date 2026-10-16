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
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.di.scope.EngineControlScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** One engine of the list. */
@Immutable
data class EngineItemUi(
    val id: String,
    val title: String,
    val status: EngineStatusUi,
    val version: String?,
    val isConfigurable: Boolean,
)

/** The engine list. [isOff] while engine management is off; the section is hidden then, but may still be open. */
@Immutable
data class EngineControlListState(
    val engines: ImmutableList<EngineItemUi> = persistentListOf(),
    val isLoading: Boolean = true,
    val isOff: Boolean = false,
    val hasLoadFailure: Boolean = false,
) : MVIState

/** User events of the list; opening an engine is navigation and belongs to the component. */
sealed interface EngineControlListIntent : MVIIntent {
    /** Restarts a failed observation. */
    data object RetryLoad : EngineControlListIntent
}

/** The list has no one-off actions. */
sealed interface EngineControlListAction : MVIAction

/** Engine list store: a projection of the panel machine. */
@SingleIn(EngineControlScope::class)
@Inject
class EngineControlListModel(
    machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    @ForScope(EngineControlScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    val store = factory.create<EngineControlListState, EngineControlListIntent, EngineControlListAction>(
        "EngineControlList",
        listState(machine.state.value),
        onError = { copy(isLoading = false, hasLoadFailure = true) },
    ) {
        init { sendTo(machine, EngineControlIntent.Public.Start) }
        reflect(machine) { listState(it) }
        reduce { intent ->
            when (intent) {
                EngineControlListIntent.RetryLoad -> sendTo(machine, EngineControlIntent.Public.RetryLoad)
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
    }
}

/** The list projected from the machine [state]. */
internal fun listState(state: EngineControlState): EngineControlListState {
    val active = state as? EngineControlState.Active ?: return EngineControlListState()
    val snapshot = active.snapshot
        ?: return EngineControlListState(
            isLoading = active.loadFailure == null,
            hasLoadFailure = active.loadFailure != null,
        )
    val configurable = snapshot.configuration.engines.map { it.engine }.toSet()
    return EngineControlListState(
        engines = snapshot.management.engines.map { engine ->
            val id = engine.descriptor.id
            EngineItemUi(id.value, engine.descriptor.title, engine.statusUi(), engine.versionUi(), id in configurable)
        }.toImmutableList(),
        isLoading = false,
        isOff = !snapshot.management.isEnabled,
    )
}
