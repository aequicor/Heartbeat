package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias SettingsPipeline =
    PipelineContext<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

/** Host blockers presented as localized help beside the tool switch. */
internal enum class BlockerUi {
    UnsupportedPlatform,
    ScreenRecordingPermission,
    AccessibilityPermission,
    ElevationRequired,
    SessionLocked,
    Headless,
}

/** The persisted tool switch and relevant host availability; capture choices belong to the agent. */
@Immutable
internal data class ComputerUseScreenState(
    val isEnabled: Boolean = false,
    val isLoaded: Boolean = false,
    val blockers: ImmutableList<BlockerUi> = persistentListOf(),
    val hasError: Boolean = false,
) : MVIState

/** The only control offered by computer use settings. */
internal sealed interface ComputerUseScreenIntent : MVIIntent {
    /** Enables the tool, or revokes the active capture and disables further agent use. */
    data class SetEnabled(val isEnabled: Boolean) : ComputerUseScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface ComputerUseScreenAction : MVIAction

/**
 * Settings store. It observes the profile preference and the machine's permission blockers, without holding
 * captured frames or choosing a capture target. Disabling revokes input and capture before saving the switch.
 */
internal class ComputerUseModel(
    private val machine: Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>,
    private val preferences: ComputerUsePreferences,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    /** The settings store, retained for the lifetime of this screen. */
    val store = factory.create<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>(
        "ComputerUse",
        ComputerUseScreenState().reflectState(machine.state.value),
        onError = { copy(isLoaded = true, hasError = true) },
    ) {
        reflect(machine) { reflectState(it) }
        whileSubscribed {
            preferences.observe().collect { settings ->
                updateState { copy(isEnabled = settings.isEnabled, isLoaded = true) }
            }
        }
        reduce { intent ->
            when (intent) {
                is ComputerUseScreenIntent.SetEnabled -> setEnabled(intent.isEnabled)
            }
        }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SettingsPipeline.setEnabled(isEnabled: Boolean) {
        if (!isEnabled) sendTo(machine, ComputerUseIntent.Public.Revoke)
        preferences.setEnabled(isEnabled)
        updateState { copy(isEnabled = isEnabled, isLoaded = true, hasError = false) }
    }
}

/** Permission availability is reflected without duplicating capture targets or the active session. */
private fun ComputerUseScreenState.reflectState(state: ComputerUseState): ComputerUseScreenState = copy(
    blockers = when (state) {
        is ComputerUseState.Unavailable -> state.blockers.map { it.toUi() }.toImmutableList()

        ComputerUseState.Idle, ComputerUseState.Checking, is ComputerUseState.Ready,
        is ComputerUseState.Capturing, is ComputerUseState.Failed,
        -> persistentListOf()
    },
)

private fun ComputerUseBlocker.toUi(): BlockerUi = when (this) {
    ComputerUseBlocker.UnsupportedPlatform -> BlockerUi.UnsupportedPlatform
    ComputerUseBlocker.ScreenRecordingPermission -> BlockerUi.ScreenRecordingPermission
    ComputerUseBlocker.AccessibilityPermission -> BlockerUi.AccessibilityPermission
    ComputerUseBlocker.ElevationRequired -> BlockerUi.ElevationRequired
    ComputerUseBlocker.SessionLocked -> BlockerUi.SessionLocked
    ComputerUseBlocker.Headless -> BlockerUi.Headless
}
