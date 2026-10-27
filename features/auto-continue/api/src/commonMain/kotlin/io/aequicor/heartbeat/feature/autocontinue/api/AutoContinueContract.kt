package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** How a chat continues after the agent's turn. */
@Serializable
public enum class AutoContinueMode {
    /** Nothing is suggested or sent. */
    Off,

    /** The continuer offers replies; the human picks one or writes their own. */
    Suggest,

    /** The continuer replies itself until the task is done, the human is needed or a limit is reached. */
    Autopilot,
}

/**
 * Profile settings of auto-continue. [continuerTarget] null uses the chat's own route. A new chat starts in
 * [defaultMode], which is never [AutoContinueMode.Autopilot]: autopilot is always switched on in the chat.
 */
@Serializable
public data class AutoContinueSettings(
    val defaultMode: AutoContinueMode = AutoContinueMode.Off,
    val continuerTarget: EngineTarget? = null,
    val autoTurnLimit: Int = DEFAULT_AUTO_TURN_LIMIT,
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    val optionCount: Int = MAX_OPTIONS,
) {
    init {
        require(defaultMode != AutoContinueMode.Autopilot) { "Autopilot cannot be a default mode" }
        require(autoTurnLimit in 1..MAX_AUTO_TURN_LIMIT) { "Auto turn limit is out of range" }
        require(countdownSeconds in 0..MAX_COUNTDOWN_SECONDS) { "Countdown is out of range" }
        require(optionCount in 1..MAX_OPTIONS) { "Option count is out of range" }
    }

    /** Limits of the settings. */
    public companion object {
        /** Auto turns allowed after the last human message by default. */
        public const val DEFAULT_AUTO_TURN_LIMIT: Int = 10

        /** Upper bound of [autoTurnLimit]. */
        public const val MAX_AUTO_TURN_LIMIT: Int = 100

        /** Pause before an autopilot reply is sent, by default. */
        public const val DEFAULT_COUNTDOWN_SECONDS: Int = 5

        /** Upper bound of [countdownSeconds]. */
        public const val MAX_COUNTDOWN_SECONDS: Int = 60

        /** Upper bound of [optionCount]. */
        public const val MAX_OPTIONS: Int = 3
    }
}

/** Author of one entry of the dialog the continuer reads. */
@Serializable
public enum class DialogAuthor {
    /** A message the human wrote or picked; only these define the task. */
    Human,

    /** A message sent by the autopilot. */
    Autopilot,

    /** The final text of an agent turn, without tools and reasoning. */
    Agent,
}

/** One entry of the dialog; [text] is user data and never logged. */
@Serializable
public data class DialogEntry(val author: DialogAuthor, val text: String) {
    override fun toString(): String = "DialogEntry($author, length=${text.length})"
}

/**
 * What the continuer reads after a turn: the chat's [route], its [dialog] (oldest first) and an optional
 * [planNote] describing the plan of the planning mode. Text is user data and never logged.
 */
@Serializable
public data class ContinuationContext(
    val route: EngineTarget,
    val dialog: List<DialogEntry>,
    val planNote: String? = null,
) {
    override fun toString(): String = "ContinuationContext(route=$route, entries=${dialog.size})"
}

/** What the continuer decided about the dialog. */
@Serializable
public enum class ContinuationStatus {
    /** The agent should go on; options carry the replies. */
    Continue,

    /** The task of the human looks finished. */
    Done,

    /** A decision only the human can make is open. */
    NeedsUser,
}

/** One reply to offer: short [label] for the button and the full [text] to send. */
@Serializable
public data class ContinuationOption(val label: String, val text: String) {
    init {
        require(label.isNotBlank() && text.isNotBlank()) { "Empty continuation option" }
    }

    override fun toString(): String = "ContinuationOption(label=${label.length}, text=${text.length})"
}

/** The continuer's answer. [Continue][ContinuationStatus.Continue] always has at least one option. */
@Serializable
public data class ContinuationVerdict(
    val status: ContinuationStatus,
    val reason: String,
    val options: List<ContinuationOption> = emptyList(),
) {
    init {
        require(options.size <= AutoContinueSettings.MAX_OPTIONS) { "Too many continuation options" }
        require(status != ContinuationStatus.Continue || options.isNotEmpty()) { "Continue needs an option" }
    }

    override fun toString(): String = "ContinuationVerdict($status, options=${options.size})"
}

/** Why the continuer gave no verdict; safe to log. */
@Serializable
public enum class ContinuerFailure {
    /** No route, or the engine cannot answer without tools. */
    Unavailable,

    /** The engine had no free capacity. */
    Busy,

    /** The answer did not come in time. */
    Timeout,

    /** The answer was not a valid verdict, even after a retry. */
    InvalidAnswer,

    /** Any other failure. */
    Failed,
}

/** Why the autopilot stopped, or (in suggest mode) why there is nothing to offer. */
@Serializable
public enum class HaltReason {
    /** The continuer judged the task done. */
    Done,

    /** The continuer found a decision for the human. */
    NeedsUser,

    /** [AutoContinueSettings.autoTurnLimit] auto turns since the last human message. */
    LimitReached,

    /** The next reply nearly repeats the previous autopilot reply. */
    Looping,

    /** The continuer failed; see [Phase.Halted.failure]. */
    ContinuerFailed,

    /** The autopilot reply could not be sent. */
    DeliveryFailed,

    /** The agent's turn failed. */
    TurnFailed,

    /** The human stopped the turn or the autopilot. */
    UserStopped,

    /** The human wrote in the chat. */
    HumanTookOver,

    /** The application restarted while the autopilot was on. */
    Restarted,
}

/** Runtime state of one chat. */
@Serializable
public sealed interface Phase {
    /** Nothing to show. */
    @Serializable
    public data object Idle : Phase

    /** The continuer is answering request [seq]. */
    @Serializable
    public data class Thinking(val seq: Long) : Phase

    /** Suggest mode: [verdict] is shown (options, or a done / needs-user notice). */
    @Serializable
    public data class Offered(val verdict: ContinuationVerdict) : Phase

    /** Autopilot will send [text] when the countdown of request [seq] elapses. */
    @Serializable
    public data class Countdown(val seq: Long, val text: String, val seconds: Int) : Phase {
        override fun toString(): String = "Countdown(seq=$seq, seconds=$seconds)"
    }

    /** Autopilot is sending [text] of request [seq]. */
    @Serializable
    public data class Delivering(val seq: Long, val text: String) : Phase {
        override fun toString(): String = "Delivering(seq=$seq)"
    }

    /** Autopilot waits while the agent waits for the human (a permission, a form, a plan proposal). */
    @Serializable
    public data object Paused : Phase

    /** The autopilot stopped, or the continuer failed in suggest mode; [verdict] options stay offered. */
    @Serializable
    public data class Halted(
        val reason: HaltReason,
        val verdict: ContinuationVerdict? = null,
        val failure: ContinuerFailure? = null,
    ) : Phase
}

/**
 * Auto-continue of one chat. [autoTurns] counts autopilot replies since the last human message, [lastAutoText] is the
 * previous autopilot reply (loop detection), [context] the last settled turn the continuer can read again.
 */
@Serializable
public data class ChatAuto(
    val mode: AutoContinueMode,
    val phase: Phase = Phase.Idle,
    val autoTurns: Int = 0,
    val lastAutoText: String? = null,
    val context: ContinuationContext? = null,
    val seq: Long = 0,
) {
    override fun toString(): String = "ChatAuto($mode, $phase, autoTurns=$autoTurns, seq=$seq)"
}

/** Profile-owned auto-continue state. */
public sealed interface AutoContinueState : MachineState {
    /** Not started yet. */
    public data object Idle : AutoContinueState

    /** Reading settings and chat modes. */
    public data object Loading : AutoContinueState

    /** Settings and every chat that has a mode; a chat without an entry is in [AutoContinueSettings.defaultMode]. */
    public data class Ready(
        val settings: AutoContinueSettings = AutoContinueSettings(),
        val chats: Map<String, ChatAuto> = emptyMap(),
    ) : AutoContinueState {
        /** Auto-continue of [chat]. */
        public fun chat(chat: String): ChatAuto = chats[chat] ?: ChatAuto(settings.defaultMode)
    }
}

/** Commands of the studio and the screen, and results of effects. */
public sealed interface AutoContinueIntent : MachineIntent {
    /** The chat the intent is about, or null for profile-wide intents. */
    public val chat: String? get() = null

    /** Commands sent through [AutoContinueMachineKey]. */
    public sealed interface Public : AutoContinueIntent {
        /** Loads settings and chat modes; sent once by the owner when the machine is created. */
        public data object Start : Public

        /** Replaces the profile settings. */
        public data class UpdateSettings(val settings: AutoContinueSettings) : Public

        /** The human switched [chat] to [mode]; a settled chat is answered at once. */
        public data class SetMode(override val chat: String, val mode: AutoContinueMode) : Public

        /** A turn started in [chat]; [isHuman] when the human sent the message (resets the counter). */
        public data class TurnStarted(override val chat: String, val isHuman: Boolean) : Public

        /**
         * A turn of [chat] ended normally and nothing waits for the human: no open permission, form, checklist or
         * plan proposal, no draft, no compaction and no wait of the agent itself.
         */
        public data class TurnSettled(override val chat: String, val context: ContinuationContext) : Public

        /** A turn of [chat] ended and the agent waits for the human. */
        public data class TurnAwaitsHuman(override val chat: String) : Public

        /** A turn of [chat] failed, or [isStoppedByUser]. */
        public data class TurnFailed(override val chat: String, val isStoppedByUser: Boolean) : Public

        /** The human typed in the composer of [chat]. */
        public data class DraftTyped(override val chat: String) : Public

        /** Asks the continuer again for the last settled turn of [chat]. */
        public data class Refresh(override val chat: String) : Public

        /** Sends the counted-down autopilot reply of [chat] now. */
        public data class SendNow(override val chat: String) : Public

        /** Moves the counted-down reply of [chat] into the composer and switches to suggest mode. */
        public data class Edit(override val chat: String) : Public

        /** Stops the autopilot of [chat]. */
        public data class Stop(override val chat: String) : Public

        /** [chat] was deleted. */
        public data class Forget(override val chat: String) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : AutoContinueIntent {
        /** Stored settings and chat modes were read. */
        public data class Loaded(val settings: AutoContinueSettings, val modes: Map<String, AutoContinueMode>) :
            Internal

        /** Storage could not be read; defaults apply for this run. */
        public data object LoadFailed : Internal

        /** The continuer answered request [seq]. */
        public data class Answered(override val chat: String, val seq: Long, val verdict: ContinuationVerdict) :
            Internal

        /** The continuer failed request [seq]. */
        public data class AskFailed(override val chat: String, val seq: Long, val failure: ContinuerFailure) :
            Internal

        /** The countdown of request [seq] elapsed. */
        public data class CountdownElapsed(override val chat: String, val seq: Long) : Internal

        /** The autopilot reply of request [seq] was accepted by the chat. */
        public data class Delivered(override val chat: String, val seq: Long) : Internal

        /** The autopilot reply of request [seq] could not be sent. */
        public data class DeliveryFailed(override val chat: String, val seq: Long) : Internal

        /** Settings or a mode could not be written. */
        public data object SaveFailed : Internal
    }
}

/** IO commands executed in the feature impl. One job (ask, countdown, delivery) runs per chat at a time. */
public sealed interface AutoContinueEffect : MachineEffect {
    /** Reads settings and chat modes. */
    public data object Load : AutoContinueEffect

    /** Writes [settings]. */
    public data class SaveSettings(val settings: AutoContinueSettings) : AutoContinueEffect

    /** Writes the [mode] of [chat]; null removes it. */
    public data class SaveMode(val chat: String, val mode: AutoContinueMode?) : AutoContinueEffect

    /** Asks the continuer through [target] about [context] for at most [optionCount] options; replaces the job. */
    public data class Ask(
        val chat: String,
        val seq: Long,
        val target: EngineTarget,
        val context: ContinuationContext,
        val mode: AutoContinueMode,
        val optionCount: Int,
    ) : AutoContinueEffect

    /** Waits [seconds] and reports [AutoContinueIntent.Internal.CountdownElapsed]; replaces the job. */
    public data class Countdown(val chat: String, val seq: Long, val seconds: Int) : AutoContinueEffect

    /** Sends [text] to [chat] as an autopilot message through [AutoContinueHost]; replaces the job. */
    public data class Deliver(val chat: String, val seq: Long, val text: String) : AutoContinueEffect {
        override fun toString(): String = "Deliver(chat=$chat, seq=$seq)"
    }

    /** Cancels the running job of [chat]. */
    public data class Cancel(val chat: String) : AutoContinueEffect
}

/** One-shot notifications. */
public sealed interface AutoContinueOutput : MachineOutput {
    /** Put [text] into the composer of [chat] for editing. */
    public data class Draft(val chat: String, val text: String) : AutoContinueOutput {
        override fun toString(): String = "Draft(chat=$chat)"
    }

    /** Settings or a mode were not persisted. */
    public data object SaveFailed : AutoContinueOutput
}

/** Profile-scoped auto-continue machine, created by the first injection of [AutoContinueView]. */
public object AutoContinueMachineKey :
    MachineKey<
        AutoContinueState,
        AutoContinueIntent,
        AutoContinueIntent.Public,
        AutoContinueEffect,
        AutoContinueOutput,
    > {
    override val name: String = "auto-continue"
}

/** Read-only view for other features; injecting it starts the machine. */
public interface AutoContinueView {
    /** Current machine state. */
    public val state: StateFlow<AutoContinueState>
}

/**
 * The chat host that sends autopilot replies (the studio). A reply is a chat message marked as autopilot; the host
 * reports [AutoContinueIntent.Public.TurnStarted] with `isHuman = false` for it.
 */
public fun interface AutoContinueHost {
    /** Sends [text] to [chat]; false when the chat cannot take a message now. */
    public suspend fun deliver(chat: String, text: String): Boolean
}

/** Auto-continue of studio chats: suggestions and autopilot. */
public val AutoContinueEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "auto_continue.enabled",
    "Автопродолжение диалога",
    default = true,
)
