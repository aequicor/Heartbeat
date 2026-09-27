package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelIntent
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelOutput
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelState
import io.aequicor.heartbeat.feature.togglespanel.impl.di.scope.TogglesPanelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** Immutable presentation derived from the feature machine. */
@Immutable
data class TogglesPanelScreenState(
    val rows: ImmutableList<ToggleRowUi> = persistentListOf(),
    val query: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasLoadError: Boolean = false,
    val hasWriteError: Boolean = false,
) : MVIState

/** User events consumed by the screen store. */
sealed interface TogglesPanelScreenIntent : MVIIntent {
    /** Changes the local search query. */
    data class Search(val query: String) : TogglesPanelScreenIntent

    /** An edit identified by a stable key; UI never receives the domain toggle instance. */
    sealed interface Mutation : TogglesPanelScreenIntent

    /** Changes a boolean flag. */
    data class SetFlag(val key: String, val isEnabled: Boolean) : Mutation

    /** Changes a choice flag. */
    data class SetChoice(val key: String, val value: String) : Mutation

    /** Removes a local override. */
    data class Reset(val key: String) : Mutation

    /** Removes all local overrides. */
    data object ResetAll : Mutation

    /** Retries observation. */
    data object RetryLoad : TogglesPanelScreenIntent

    /** Retries the failed edit. */
    data object RetryWrite : TogglesPanelScreenIntent

    /** Dismisses the last write error. */
    data object DismissError : TogglesPanelScreenIntent
}

/** Reserved contract for one-off screen actions. */
sealed interface TogglesPanelScreenAction : MVIAction

/** Feature-scoped screen store reflecting the machine and forwarding intents. */
@SingleIn(TogglesPanelScope::class)
@Inject
class TogglesPanelModel(
    machine: Machine<TogglesPanelState, TogglesPanelIntent, TogglesPanelOutput>,
    @ForScope(TogglesPanelScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("TogglesPanelModel")

    val store = factory.create<TogglesPanelScreenState, TogglesPanelScreenIntent, TogglesPanelScreenAction>(
        "TogglesPanel",
        TogglesPanelScreenState().reflectState(machine.state.value),
        onError = { copy(hasLoadError = true, isLoading = false, isSaving = false) },
    ) {
        reflect(machine) { reflectState(it) }
        reduce { intent ->
            when (intent) {
                is TogglesPanelScreenIntent.Search -> updateState { copy(query = intent.query) }

                is TogglesPanelScreenIntent.Mutation -> {
                    val operation = intent.toOperation(machine.state.value)
                    if (operation != null) {
                        sendTo(machine, TogglesPanelIntent.Public.Apply(operation))
                    } else {
                        log.w { "ignore edit for an unavailable toggle" }
                    }
                }

                TogglesPanelScreenIntent.RetryLoad -> sendTo(machine, TogglesPanelIntent.Public.RetryLoad)

                TogglesPanelScreenIntent.RetryWrite -> sendTo(machine, TogglesPanelIntent.Public.RetryWrite)

                TogglesPanelScreenIntent.DismissError -> sendTo(machine, TogglesPanelIntent.Public.DismissError)
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch { machine.send(TogglesPanelIntent.Public.Start) }
    }
}

private fun TogglesPanelScreenState.reflectState(state: TogglesPanelState): TogglesPanelScreenState = when (state) {
    TogglesPanelState.Idle -> copy(isLoading = true, hasLoadError = false)

    TogglesPanelState.LoadError -> copy(isLoading = false, hasLoadError = true, isSaving = false)

    is TogglesPanelState.Active -> copy(
        rows = state.rows.orEmpty().map { it.toUi() }.toImmutableList(),
        isLoading = state.rows == null,
        isSaving = state.pending != null,
        hasLoadError = false,
        hasWriteError = state.failed != null,
    )
}
