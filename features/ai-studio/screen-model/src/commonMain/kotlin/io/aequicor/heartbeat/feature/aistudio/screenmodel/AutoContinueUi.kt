@file:UseSerializers(ImmutableListSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** How a chat continues after the agent's turn. */
@Serializable
@SerialName("AutoContinueModeUi")
enum class AutoContinueModeUi { Off, Suggest, Autopilot }

/** Why there is nothing to offer, or why the autopilot stopped. */
@Serializable
@SerialName("AutoContinueNoticeUi")
enum class AutoContinueNoticeUi {
    Done,
    NeedsUser,
    LimitReached,
    Looping,
    ContinuerFailed,
    ContinuerUnavailable,
    DeliveryFailed,
    TurnFailed,
    UserStopped,
    HumanTookOver,
    Restarted,
}

/** One offered reply; [text] is user data and is hidden in logs. */
@Serializable
@SerialName("AutoContinueOptionUi")
@Immutable
data class AutoContinueOptionUi(val label: String, val text: String) {
    override fun toString(): String = "AutoContinueOptionUi(label=${label.length}, text=${text.length})"
}

/** The autopilot sends [text] in [seconds] unless the human acts; [seq] restarts the visible countdown. */
@Serializable
@SerialName("AutoContinueCountdownUi")
@Immutable
data class AutoContinueCountdownUi(val seq: Long, val text: String, val seconds: Int) {
    override fun toString(): String = "AutoContinueCountdownUi(seq=$seq, seconds=$seconds)"
}

/**
 * Auto-continue of one chat: its [mode], autopilot replies since the last human message ([autoTurns]), whether the
 * continuer is thinking, the offered [options], the autopilot [countdown], a [notice] with the continuer's [reason],
 * and whether the autopilot is paused while the agent waits for the human or is sending its reply.
 */
@Serializable
@SerialName("AutoContinueUi")
@Immutable
data class AutoContinueUi(
    val mode: AutoContinueModeUi = AutoContinueModeUi.Off,
    val autoTurns: Int = 0,
    val isThinking: Boolean = false,
    val options: ImmutableList<AutoContinueOptionUi> = persistentListOf(),
    val countdown: AutoContinueCountdownUi? = null,
    val notice: AutoContinueNoticeUi? = null,
    val reason: String = "",
    val isPaused: Boolean = false,
    val isSending: Boolean = false,
) {
    override fun toString(): String =
        "AutoContinueUi($mode, autoTurns=$autoTurns, options=${options.size}, notice=${notice ?: "none"})"
}

/**
 * Auto-continue of the studio: whether it is offered, the mode of chats without their own [chats] entry and the
 * autopilot's turn limit.
 */
@Serializable
@SerialName("AutoContinueScreenUi")
@Immutable
data class AutoContinueScreenUi(
    val isEnabled: Boolean = false,
    val defaultMode: AutoContinueModeUi = AutoContinueModeUi.Off,
    val autoTurnLimit: Int = 10,
    val chats: ImmutableMap<String, AutoContinueUi> = persistentMapOf(),
) {
    /** Auto-continue of [sessionId]; a chat without an entry is in [defaultMode]. */
    fun chat(sessionId: String?): AutoContinueUi? = sessionId?.let { chats[it] ?: AutoContinueUi(mode = defaultMode) }
}
