package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** Confirmation levels offered by the profile settings screen. */
internal enum class StudioSecurityLevelUi { Ask, Automatic, Trust }

/** Projection of loading, accepted policy and durable-write status. */
@Immutable
internal data class StudioSecurityScreenState(
    val level: StudioSecurityLevelUi = StudioSecurityLevelUi.Automatic,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isLoadFailed: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState

/** User choices; machine guards decide when a change is admissible. */
internal sealed interface StudioSecurityScreenIntent : MVIIntent {
    /** Selects a level for subsequent ordinary studio turns. */
    data class Select(val level: StudioSecurityLevelUi) : StudioSecurityScreenIntent

    /** Retries reading the profile's saved settings. */
    data object Retry : StudioSecurityScreenIntent
}

/** Reserved one-shot actions; load and save errors remain visible in screen state. */
internal sealed interface StudioSecurityScreenAction : MVIAction

/** Reflects the profile machine without owning it; leaving this screen cannot cancel a pending policy change. */
internal class StudioSecurityModel(
    machine: StudioSecurityMachine,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    val store = factory.create<StudioSecurityScreenState, StudioSecurityScreenIntent, StudioSecurityScreenAction>(
        "StudioSecurity",
        machine.state.value.toScreenState(),
        onError = { copy(isSaveFailed = true) },
    ) {
        reflect(machine) { it.toScreenState() }
        reduce { intent ->
            val request = when (intent) {
                is StudioSecurityScreenIntent.Select -> StudioSecurityIntent.Public.SetLevel(intent.level.toDomain())
                StudioSecurityScreenIntent.Retry -> StudioSecurityIntent.Public.Retry
            }
            sendTo(machine, request) { rejected ->
                if (rejected == SendResult.NotRunning) updateState { copy(isSaveFailed = true) }
            }
        }
    }

    init {
        store.start(scope)
    }
}

private fun StudioSecurityState.toScreenState(): StudioSecurityScreenState = when (this) {
    StudioSecurityState.Idle, StudioSecurityState.Loading -> StudioSecurityScreenState()

    StudioSecurityState.LoadError -> StudioSecurityScreenState(
        level = StudioSecurityLevelUi.Ask,
        isLoadFailed = true,
    )

    is StudioSecurityState.Ready -> StudioSecurityScreenState(
        level = level.toUi(),
        isLoaded = true,
        isSaveFailed = isSaveFailed,
    )

    is StudioSecurityState.Saving -> StudioSecurityScreenState(level = level.toUi(), isLoaded = true, isSaving = true)
}

private fun StudioSecurityLevel.toUi(): StudioSecurityLevelUi = when (this) {
    StudioSecurityLevel.Ask -> StudioSecurityLevelUi.Ask
    StudioSecurityLevel.Automatic -> StudioSecurityLevelUi.Automatic
    StudioSecurityLevel.Trust -> StudioSecurityLevelUi.Trust
}

private fun StudioSecurityLevelUi.toDomain(): StudioSecurityLevel = when (this) {
    StudioSecurityLevelUi.Ask -> StudioSecurityLevel.Ask
    StudioSecurityLevelUi.Automatic -> StudioSecurityLevel.Automatic
    StudioSecurityLevelUi.Trust -> StudioSecurityLevel.Trust
}
