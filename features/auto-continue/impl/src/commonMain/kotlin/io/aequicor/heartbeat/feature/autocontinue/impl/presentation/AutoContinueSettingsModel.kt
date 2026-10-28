package io.aequicor.heartbeat.feature.autocontinue.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/** Mode of new chats the screen offers. */
internal enum class DefaultModeUi { Off, Suggest }

/** Which model the continuer uses: the chat's own, the profile default or another chosen earlier. */
internal enum class ContinuerUi { Chat, ProfileDefault, Custom }

/**
 * The settings as the screen shows them. [profileDefault] is the profile's default model, if any, once the model
 * selection is read ([isProfileDefaultKnown]); [profileDefaultModel] and [customModel] are the names the screen shows.
 */
@Immutable
internal data class AutoContinueSettingsScreenState(
    val settings: AutoContinueSettings = AutoContinueSettings(),
    val profileDefault: EngineTarget? = null,
    val isProfileDefaultKnown: Boolean = false,
    val isLoaded: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState {
    val defaultMode: DefaultModeUi
        get() = if (settings.defaultMode == AutoContinueMode.Suggest) DefaultModeUi.Suggest else DefaultModeUi.Off

    val continuer: ContinuerUi
        get() = when (settings.continuerTarget) {
            null -> ContinuerUi.Chat
            profileDefault -> ContinuerUi.ProfileDefault
            else -> ContinuerUi.Custom
        }

    val profileDefaultModel: String? get() = profileDefault?.model?.value

    /** A model chosen earlier that is not the profile default; unknown until the selection is read. */
    val customModel: String?
        get() = settings.continuerTarget?.takeIf { isProfileDefaultKnown && it != profileDefault }?.model?.value

    val autoTurnLimit: Int get() = settings.autoTurnLimit

    val countdownSeconds: Int get() = settings.countdownSeconds

    val optionCount: Int get() = settings.optionCount
}

/** Changes offered by the auto-continue settings. */
internal sealed interface AutoContinueSettingsIntent : MVIIntent {
    /** Mode of new chats: off or suggestions. */
    data class SetDefaultMode(val mode: DefaultModeUi) : AutoContinueSettingsIntent

    /** The continuer model: the chat's own or the profile default; a custom one is only kept. */
    data class SetContinuer(val continuer: ContinuerUi) : AutoContinueSettingsIntent

    /** Autopilot replies allowed after a human message. */
    data class SetAutoTurnLimit(val limit: Int) : AutoContinueSettingsIntent

    /** Pause before an autopilot reply is sent. */
    data class SetCountdown(val seconds: Int) : AutoContinueSettingsIntent

    /** Replies offered after a turn. */
    data class SetOptionCount(val count: Int) : AutoContinueSettingsIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface AutoContinueSettingsAction : MVIAction

/** Limits, pauses and counts the screen offers. */
internal val AutoTurnLimitChoices = listOf(5, 10, 20, 50)
internal val CountdownChoices = listOf(0, 3, 5, 10)
internal val OptionCountChoices = listOf(1, 2, 3)

/**
 * Settings store: it reflects the machine's settings and the profile's default model and sends every change as a
 * whole new settings value. The machine persists it; until it is loaded the controls stay disabled.
 */
internal class AutoContinueSettingsModel(
    machine: AutoContinueMachine,
    selections: ModelSelections,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("AutoContinueSettingsModel")

    private val screen = factory.createScreen<
        AutoContinueSettingsScreenState,
        AutoContinueSettingsIntent,
        AutoContinueSettingsAction,
    >(
        "AutoContinueSettings",
        AutoContinueSettingsScreenState().reflecting(machine.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            if (output == AutoContinueOutput.SaveFailed) updateState { copy(isSaveFailed = true) }
        }) { reflecting(it) }
        whileSubscribed {
            launch {
                selections.observe().collect {
                    updateState { copy(profileDefault = it.defaultTarget, isProfileDefaultKnown = true) }
                }
            }
        }
        reduce { intent ->
            val current = (machine.state.value as? AutoContinueState.Ready)?.settings ?: return@reduce
            var profileDefault: EngineTarget? = null
            withState { profileDefault = this.profileDefault }
            val changed = current.changed(intent, profileDefault)
            if (changed == current) return@reduce
            log.i { "change auto-continue settings: ${intent::class.simpleName.orEmpty()}" }
            val result = sendTo(machine, AutoContinueIntent.Public.UpdateSettings(changed)) { rejected ->
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

private fun AutoContinueSettingsScreenState.reflecting(state: AutoContinueState) = when (state) {
    is AutoContinueState.Ready -> copy(settings = state.settings, isLoaded = true)
    AutoContinueState.Idle, AutoContinueState.Loading -> copy(isLoaded = false)
}

/** [this] with [intent] applied; values outside the settings' limits leave it unchanged. */
internal fun AutoContinueSettings.changed(intent: AutoContinueSettingsIntent, profileDefault: EngineTarget?) =
    when (intent) {
        is AutoContinueSettingsIntent.SetDefaultMode -> copy(
            defaultMode = if (intent.mode == DefaultModeUi.Suggest) AutoContinueMode.Suggest else AutoContinueMode.Off,
        )

        is AutoContinueSettingsIntent.SetContinuer -> when (intent.continuer) {
            ContinuerUi.Chat -> copy(continuerTarget = null)
            ContinuerUi.ProfileDefault -> profileDefault?.let { copy(continuerTarget = it) } ?: this
            ContinuerUi.Custom -> this
        }

        is AutoContinueSettingsIntent.SetAutoTurnLimit,
        is AutoContinueSettingsIntent.SetCountdown,
        is AutoContinueSettingsIntent.SetOptionCount,
        -> withNumber(intent)
    }

/** A numeric setting of [intent]; a value outside the settings' limits leaves [this] unchanged. */
private fun AutoContinueSettings.withNumber(intent: AutoContinueSettingsIntent): AutoContinueSettings = when (intent) {
    is AutoContinueSettingsIntent.SetAutoTurnLimit ->
        if (intent.limit in AUTO_TURN_LIMITS) copy(autoTurnLimit = intent.limit) else this

    is AutoContinueSettingsIntent.SetCountdown ->
        if (intent.seconds in COUNTDOWNS) copy(countdownSeconds = intent.seconds) else this

    is AutoContinueSettingsIntent.SetOptionCount ->
        if (intent.count in OPTION_COUNTS) copy(optionCount = intent.count) else this

    is AutoContinueSettingsIntent.SetDefaultMode, is AutoContinueSettingsIntent.SetContinuer -> this
}

private val AUTO_TURN_LIMITS = 1..AutoContinueSettings.MAX_AUTO_TURN_LIMIT
private val COUNTDOWNS = 0..AutoContinueSettings.MAX_COUNTDOWN_SECONDS
private val OPTION_COUNTS = 1..AutoContinueSettings.MAX_OPTIONS
