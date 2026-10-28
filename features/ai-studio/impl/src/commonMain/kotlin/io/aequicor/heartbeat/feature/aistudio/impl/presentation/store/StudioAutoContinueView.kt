package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAutoContinuePort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueCountdownUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueScreenUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraftCaret
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationStatus
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationVerdict
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuerFailure
import io.aequicor.heartbeat.feature.autocontinue.api.HaltReason
import io.aequicor.heartbeat.feature.autocontinue.api.Phase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Projects auto-continue into the screen and forwards the human's choices. A picked reply is the human's own message:
 * it goes through the pane's draft and the ordinary submission. Typing over offered replies or a countdown tells the
 * machine that the human took over; edited autopilot replies land in the composer of the chat's pane.
 */
internal class StudioAutoContinueView(private val port: StudioAutoContinuePort) {
    suspend fun observe(pipeline: StudioPipeline) = coroutineScope {
        with(pipeline) {
            launch {
                combine(port.availability, port.state) { isEnabled, state -> state.toUi(isEnabled) }
                    .collect { updateState { copy(autoContinue = it) } }
            }
            launch {
                port.drafts.collect { draft -> updateState { withAutopilotDraft(draft.chat, draft.text) } }
            }
        }
    }

    /** Handles [intent]; [submit] sends the draft of a pane as the human's message. */
    suspend fun handle(
        pipeline: StudioPipeline,
        intent: AiStudioScreenIntent.AutoContinue,
        submit: suspend (paneId: Int) -> Unit,
    ) {
        val chat = intent.sessionId
        when (intent) {
            is AiStudioScreenIntent.SetAutoContinue -> port.send(Public.SetMode(chat, intent.mode.toDomain()))

            is AiStudioScreenIntent.RefreshAutoContinue -> port.send(Public.Refresh(chat))

            is AiStudioScreenIntent.SendAutoContinueNow -> port.send(Public.SendNow(chat))

            is AiStudioScreenIntent.EditAutoContinue -> port.send(Public.Edit(chat))

            is AiStudioScreenIntent.StopAutoContinue -> port.send(Public.Stop(chat))

            is AiStudioScreenIntent.PickAutoContinue -> {
                // Checked and applied in one update, so a draft typed meanwhile is never overwritten.
                var isLone = false
                pipeline.updateState {
                    val picked = pickedDraft(intent) ?: return@updateState this
                    isLone = picked == intent.text
                    withDraft(intent.paneId, picked).withDraftCaret(intent.paneId, picked.length)
                }
                // A reply joins the human's own draft instead of replacing it; only a lone reply is sent at once.
                if (intent.isSend && isLone) submit(intent.paneId)
            }
        }
    }

    /** Screen entries of [messages] of [chat], with the prompts the autopilot sent marked. */
    fun transcript(chat: String, messages: Flow<List<StudioMessage>>): Flow<List<MessageUi>> =
        messages.combine(port.autopilotTurns(chat)) { list, turns -> list.toTranscriptUi(turns) }

    /** The human typed in a pane: replies on offer or a countdown give way to the human. */
    suspend fun typed(pipeline: StudioPipeline, intent: AiStudioScreenIntent) {
        if (intent !is AiStudioScreenIntent.Suggestions.DraftChanged || intent.text.isBlank()) return
        var chat: String? = null
        pipeline.withState {
            val id = panes.firstOrNull { it.id == intent.paneId }?.sessionId
            if (autoContinue.isEnabled && autoContinue.chat(id)?.hasOffer == true) chat = id
        }
        chat?.let { port.send(Public.DraftTyped(it)) }
    }
}

/**
 * The draft of the pick's pane after [intent]: the reply the human saw, after their own draft if they typed one;
 * null when the pane shows another chat or the offer changed since the human saw it.
 */
private fun AiStudioScreenState.pickedDraft(intent: AiStudioScreenIntent.PickAutoContinue): String? {
    if (panes.none { it.id == intent.paneId && it.sessionId == intent.sessionId }) return null
    val offered = autoContinue.chat(intent.sessionId)?.options?.getOrNull(intent.index)?.text
    if (offered != intent.text) return null
    val own = draft(intent.paneId)
    return if (own.isBlank()) offered else "$own\n\n$offered"
}

/** An autopilot reply the human chose to edit, in the first pane of [chat], after a draft already typed there. */
private fun AiStudioScreenState.withAutopilotDraft(chat: String, text: String): AiStudioScreenState {
    val pane = panes.firstOrNull { it.sessionId == chat } ?: return this
    val own = draft(pane.id)
    val draft = if (own.isBlank()) text else "$own\n\n$text"
    return withDraft(pane.id, draft).withDraftCaret(pane.id, draft.length)
}

/** Something is on offer that typing replaces, as the machine sees it for the mode. */
private val AutoContinueUi.hasOffer: Boolean
    get() = when (mode) {
        AutoContinueModeUi.Off -> false
        AutoContinueModeUi.Suggest -> isThinking || notice != null || options.isNotEmpty()
        AutoContinueModeUi.Autopilot -> isThinking || countdown != null
    }

/** Screen projection of the auto-continue machine. */
internal fun AutoContinueState.toUi(isEnabled: Boolean): AutoContinueScreenUi {
    val ready = this as? AutoContinueState.Ready ?: return AutoContinueScreenUi(isEnabled = false)
    return AutoContinueScreenUi(
        isEnabled = isEnabled,
        defaultMode = ready.settings.defaultMode.toUi(),
        autoTurnLimit = ready.settings.autoTurnLimit,
        chats = ready.chats.mapValues { (_, chat) -> chat.toUi() }.toImmutableMap(),
    )
}

internal fun ChatAuto.toUi(): AutoContinueUi {
    val base = AutoContinueUi(mode = mode.toUi(), autoTurns = autoTurns)
    return when (val phase = phase) {
        Phase.Idle -> base
        is Phase.Thinking -> base.copy(isThinking = true)
        is Phase.Offered -> base.withVerdict(phase.verdict, notice = phase.verdict.notice())
        is Phase.Countdown -> base.copy(countdown = phase.toUi())
        is Phase.Delivering -> base.copy(isSending = true)
        Phase.Paused -> base.copy(isPaused = true)
        is Phase.Halted -> base.withVerdict(phase.verdict, notice = phase.reason.toUi(phase.failure))
    }
}

private fun Phase.Countdown.toUi() = AutoContinueCountdownUi(seq, text, seconds, endsAt)

private fun AutoContinueUi.withVerdict(verdict: ContinuationVerdict?, notice: AutoContinueNoticeUi?) = copy(
    options = verdict?.options.orEmpty().map { AutoContinueOptionUi(it.label, it.text) }.toImmutableList(),
    notice = notice,
    reason = verdict?.reason.orEmpty(),
)

private fun ContinuationVerdict.notice(): AutoContinueNoticeUi? = when (status) {
    ContinuationStatus.Continue -> null
    ContinuationStatus.Done -> AutoContinueNoticeUi.Done
    ContinuationStatus.NeedsUser -> AutoContinueNoticeUi.NeedsUser
}

private fun HaltReason.toUi(failure: ContinuerFailure?): AutoContinueNoticeUi = when (this) {
    HaltReason.Done -> AutoContinueNoticeUi.Done

    HaltReason.NeedsUser -> AutoContinueNoticeUi.NeedsUser

    HaltReason.LimitReached -> AutoContinueNoticeUi.LimitReached

    HaltReason.Looping -> AutoContinueNoticeUi.Looping

    HaltReason.ContinuerFailed -> if (failure == ContinuerFailure.Unavailable) {
        AutoContinueNoticeUi.ContinuerUnavailable
    } else {
        AutoContinueNoticeUi.ContinuerFailed
    }

    HaltReason.DeliveryFailed -> AutoContinueNoticeUi.DeliveryFailed

    HaltReason.TurnFailed -> AutoContinueNoticeUi.TurnFailed

    HaltReason.UserStopped -> AutoContinueNoticeUi.UserStopped

    HaltReason.HumanTookOver -> AutoContinueNoticeUi.HumanTookOver

    HaltReason.Restarted -> AutoContinueNoticeUi.Restarted
}

private fun AutoContinueMode.toUi(): AutoContinueModeUi = when (this) {
    AutoContinueMode.Off -> AutoContinueModeUi.Off
    AutoContinueMode.Suggest -> AutoContinueModeUi.Suggest
    AutoContinueMode.Autopilot -> AutoContinueModeUi.Autopilot
}

private fun AutoContinueModeUi.toDomain(): AutoContinueMode = when (this) {
    AutoContinueModeUi.Off -> AutoContinueMode.Off
    AutoContinueModeUi.Suggest -> AutoContinueMode.Suggest
    AutoContinueModeUi.Autopilot -> AutoContinueMode.Autopilot
}
