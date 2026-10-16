package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationOutput
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextOptimizationMachine
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** How a compaction is preferably done, as the screen offers it. */
internal enum class StrategyUi { Native, Rollover, Auto }

/** The profile's context settings as the screen shows them. */
@Immutable
internal data class ContextSettingsUi(
    val isAutoCompactionEnabled: Boolean = true,
    val thresholdPercent: Int = ContextOptimizationSettings.DEFAULT_THRESHOLD_PERCENT,
    val strategy: StrategyUi = StrategyUi.Auto,
    val isReasoningCollapsed: Boolean = false,
    val isToolCallsCollapsed: Boolean = false,
)

/** The settings and whether they are known yet; editing waits until they are loaded. */
@Immutable
internal data class ContextSettingsScreenState(
    val settings: ContextSettingsUi = ContextSettingsUi(),
    val isLoaded: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState

/** Changes offered by the context settings. */
internal sealed interface ContextSettingsScreenIntent : MVIIntent {
    /** Turns automatic compaction on or off. */
    data class SetAutoCompaction(val isEnabled: Boolean) : ContextSettingsScreenIntent

    /** Occupancy, in percent, that triggers automatic compaction. */
    data class SetThreshold(val percent: Int) : ContextSettingsScreenIntent

    /** Preferred way to compact. */
    data class SetStrategy(val strategy: StrategyUi) : ContextSettingsScreenIntent

    /** Collapses reasoning blocks in the chat feed. */
    data class SetReasoningCollapsed(val isCollapsed: Boolean) : ContextSettingsScreenIntent

    /** Collapses tool calls in the chat feed. */
    data class SetToolCallsCollapsed(val isCollapsed: Boolean) : ContextSettingsScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface ContextSettingsScreenAction : MVIAction

/** Thresholds the screen offers, within [ContextOptimizationSettings.THRESHOLD_RANGE]. */
internal val ThresholdChoices = listOf(60, 70, 80, 90)

/**
 * Settings store: it reflects the machine's settings and sends every change as a whole new settings value. The
 * machine persists it; until it is loaded the controls stay disabled. Only a real rejection shows the failure: the
 * machine is not running or reports that the settings were not saved.
 */
internal class ContextSettingsModel(
    machine: ContextOptimizationMachine,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("ContextSettingsModel")

    /** The settings store, retained for the lifetime of this screen. */
    private val screen = factory.createScreen<
        ContextSettingsScreenState,
        ContextSettingsScreenIntent,
        ContextSettingsScreenAction,
    >(
        "ContextSettings",
        ContextSettingsScreenState().reflecting(machine.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            when (output) {
                ContextOptimizationOutput.SaveFailed -> updateState { copy(isSaveFailed = true) }
            }
        }) { reflecting(it) }
        reduce { intent ->
            val current = (machine.state.value as? ContextOptimizationState.Ready)?.settings ?: return@reduce
            val changed = current.changed(intent)
            if (changed == current) return@reduce
            log.i { "change context settings: ${intent::class.simpleName.orEmpty()}" }
            val result = sendTo(machine, ContextOptimizationIntent.Public.UpdateSettings(changed)) { rejected ->
                if (rejected == SendResult.NotRunning) updateState { copy(isSaveFailed = true) }
            }
            if (result == SendResult.Accepted) updateState { copy(isSaveFailed = false) }
        }
    }
    val store = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(scope)
    }
}

private fun ContextSettingsScreenState.reflecting(state: ContextOptimizationState): ContextSettingsScreenState =
    when (state) {
        is ContextOptimizationState.Ready -> copy(settings = state.settings.toUi(), isLoaded = true)
        ContextOptimizationState.Idle, ContextOptimizationState.Loading -> copy(isLoaded = false)
    }

private fun ContextOptimizationSettings.toUi() = ContextSettingsUi(
    isAutoCompactionEnabled = isAutoCompactionEnabled,
    thresholdPercent = thresholdPercent,
    strategy = StrategyUi.valueOf(strategy.name),
    isReasoningCollapsed = isReasoningCollapsed,
    isToolCallsCollapsed = isToolCallsCollapsed,
)

private fun ContextOptimizationSettings.changed(intent: ContextSettingsScreenIntent): ContextOptimizationSettings =
    when (intent) {
        is ContextSettingsScreenIntent.SetAutoCompaction -> copy(isAutoCompactionEnabled = intent.isEnabled)
        is ContextSettingsScreenIntent.SetThreshold -> copy(thresholdPercent = intent.percent)
        is ContextSettingsScreenIntent.SetStrategy -> copy(strategy = CompactionStrategy.valueOf(intent.strategy.name))
        is ContextSettingsScreenIntent.SetReasoningCollapsed -> copy(isReasoningCollapsed = intent.isCollapsed)
        is ContextSettingsScreenIntent.SetToolCallsCollapsed -> copy(isToolCallsCollapsed = intent.isCollapsed)
    }
