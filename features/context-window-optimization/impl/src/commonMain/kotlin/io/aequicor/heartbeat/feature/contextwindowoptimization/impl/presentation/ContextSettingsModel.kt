package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationMachineKey
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationOutput
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

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
 * Settings store: it shows the machine's settings and sends every change as a whole new settings value. The
 * machine persists it; until it is loaded the controls stay disabled.
 */
internal class ContextSettingsModel(
    private val view: ContextOptimizationView,
    private val machines: MachineRegistry,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("ContextSettingsModel")

    /** The settings store, retained for the lifetime of this screen. */
    val store = factory.create<ContextSettingsScreenState, ContextSettingsScreenIntent, ContextSettingsScreenAction>(
        "ContextSettings",
        ContextSettingsScreenState().reflecting(view.state.value),
        onError = { this },
    ) {
        whileSubscribed {
            launch { view.state.collect { updateState { reflecting(it) } } }
            // A persist failure is an output of the machine, not a send result.
            machines.observe(ContextOptimizationMachineKey).collectLatest { machine ->
                machine?.outputs?.collect { output ->
                    when (output) {
                        ContextOptimizationOutput.SaveFailed -> updateState { copy(isSaveFailed = true) }
                    }
                }
            }
        }
        reduce { intent ->
            val current = (view.state.value as? ContextOptimizationState.Ready)?.settings ?: return@reduce
            log.i { "change context settings: ${intent::class.simpleName.orEmpty()}" }
            val update = ContextOptimizationIntent.Public.UpdateSettings(current.changed(intent))
            val result = machines.send(ContextOptimizationMachineKey, update)
            updateState { copy(isSaveFailed = result != SendResult.Accepted) }
        }
    }

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
