package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Result of a chat intent: the new [chat] (null removes it), at most one [job], one [save] and one [output]. */
internal data class ChatStep(
    val chat: ChatAuto?,
    val job: AutoContinueEffect? = null,
    val save: AutoContinueEffect.SaveMode? = null,
    val output: AutoContinueOutput? = null,
)

/** Pure reaction of [this] to a chat intent; null when the intent is ignored. */
internal fun AutoContinueState.Ready.step(intent: AutoContinueIntent): ChatStep? {
    val id = intent.chat ?: return null
    val chat = chat(id)
    return when (intent) {
        is Public -> publicStep(id, chat, intent)
        is Internal -> internalStep(id, chat, intent)
    }
}

private fun AutoContinueState.Ready.publicStep(id: String, chat: ChatAuto, intent: Public): ChatStep? = when (intent) {
    is Public.SetMode -> setMode(id, chat, intent.mode)

    is Public.TurnStarted -> turnStarted(id, chat, intent.isHuman)

    is Public.TurnSettled -> turnSettled(id, chat, intent.context)

    is Public.TurnAwaitsHuman -> awaitsHuman(id, chat)

    is Public.TurnFailed -> turnFailed(id, chat, intent.isStoppedByUser)

    is Public.DraftTyped -> draftTyped(id, chat)

    is Public.Refresh, is Public.SendNow, is Public.Edit, is Public.Stop, is Public.Forget ->
        controlStep(id, chat, intent)

    Public.Start, is Public.UpdateSettings -> null
}

/** Buttons of the screen. */
private fun AutoContinueState.Ready.controlStep(id: String, chat: ChatAuto, intent: Public): ChatStep? = when (intent) {
    is Public.Refresh -> refresh(id, chat)

    is Public.SendNow -> sendNow(id, chat)

    is Public.Edit -> edit(id, chat)

    is Public.Stop -> stop(id, chat)

    is Public.Forget -> forget(id)

    Public.Start, is Public.UpdateSettings, is Public.SetMode, is Public.TurnStarted, is Public.TurnSettled,
    is Public.TurnAwaitsHuman, is Public.TurnFailed, is Public.DraftTyped,
    -> null
}

private fun AutoContinueState.Ready.internalStep(id: String, chat: ChatAuto, intent: Internal): ChatStep? =
    when (intent) {
        is Internal.Answered -> answered(id, chat, intent)
        is Internal.AskFailed -> askFailed(id, chat, intent)
        is Internal.CountdownElapsed -> countdownElapsed(id, chat, intent.seq)
        is Internal.Delivered -> delivered(chat, intent.seq)
        is Internal.DeliveryFailed -> deliveryFailed(id, chat, intent.seq)
        is Internal.Loaded, Internal.LoadFailed, Internal.SaveFailed -> null
    }

/** A settled turn is answered; with auto-continue off its context is only kept, so switching it on answers at once. */
private fun AutoContinueState.Ready.turnSettled(id: String, chat: ChatAuto, context: ContinuationContext): ChatStep =
    if (chat.mode == AutoContinueMode.Off) ChatStep(chat.copy(context = context)) else ask(id, chat, context)

private fun sendNow(id: String, chat: ChatAuto): ChatStep? =
    (chat.phase as? Phase.Countdown)?.let { deliver(id, chat, it.seq, it.text) }

private fun AutoContinueState.Ready.forget(id: String): ChatStep? =
    if (id in chats) ChatStep(null, AutoContinueEffect.Cancel(id), save(id, null)) else null

private fun delivered(chat: ChatAuto, seq: Long): ChatStep? =
    if (chat.isDelivering(seq)) ChatStep(chat.copy(phase = Phase.Idle)) else null

private fun countdownElapsed(id: String, chat: ChatAuto, seq: Long): ChatStep? =
    (chat.phase as? Phase.Countdown)?.takeIf { it.seq == seq }?.let { deliver(id, chat, it.seq, it.text) }

private fun deliveryFailed(id: String, chat: ChatAuto, seq: Long): ChatStep? =
    if (chat.isDelivering(seq)) halt(id, chat, HaltReason.DeliveryFailed) else null

/** [this] with [step] applied. */
internal fun AutoContinueState.Ready.applied(step: ChatStep?, chat: String?): AutoContinueState.Ready {
    if (step == null || chat == null) return this
    return copy(chats = if (step.chat == null) chats - chat else chats + (chat to step.chat))
}

private fun AutoContinueState.Ready.setMode(id: String, chat: ChatAuto, mode: AutoContinueMode): ChatStep? {
    if (mode == chat.mode) return null
    val base = chat.copy(
        mode = mode,
        phase = Phase.Idle,
        autoTurns = if (mode == AutoContinueMode.Autopilot) 0 else chat.autoTurns,
        lastAutoText = if (mode == AutoContinueMode.Autopilot) null else chat.lastAutoText,
        context = chat.context,
    )
    val context = base.context
    val step = if (context != null && mode != AutoContinueMode.Off && chat.phase !is Phase.Delivering) {
        ask(
            id,
            base,
            context,
        )
    } else {
        ChatStep(base, if (isResettleNeeded(chat, mode)) AutoContinueEffect.Resettle(id) else cancel(id, chat))
    }
    return step.copy(save = save(id, mode))
}

/** Switched on with nothing to answer and nothing running: the host judges the last turn again. */
private fun isResettleNeeded(chat: ChatAuto, mode: AutoContinueMode): Boolean =
    mode != AutoContinueMode.Off && chat.context == null && chat.phase !is Phase.Delivering && !chat.isBusy

/** When the countdown of an answer that came [at] ends; unknown stays unknown. */
private fun AutoContinueState.Ready.endsAt(at: Instant): Instant =
    if (at == Instant.DISTANT_PAST) at else at + settings.countdownSeconds.seconds

private fun turnStarted(id: String, chat: ChatAuto, isHuman: Boolean): ChatStep? {
    if (chat.mode == AutoContinueMode.Off) return dropContext(chat)
    var base = chat.copy(context = null, phase = Phase.Idle)
    if (isHuman) base = base.copy(autoTurns = 0, lastAutoText = null)
    val job = cancel(id, chat)
    return if (isHuman && chat.mode == AutoContinueMode.Autopilot) {
        halt(id, base, HaltReason.HumanTookOver).copy(job = job)
    } else {
        ChatStep(base, job)
    }
}

private fun awaitsHuman(id: String, chat: ChatAuto): ChatStep? = when (chat.mode) {
    AutoContinueMode.Off -> dropContext(chat)
    AutoContinueMode.Suggest -> ChatStep(chat.copy(context = null, phase = Phase.Idle), cancel(id, chat))
    AutoContinueMode.Autopilot -> ChatStep(chat.copy(context = null, phase = Phase.Paused), cancel(id, chat))
}

private fun turnFailed(id: String, chat: ChatAuto, isStoppedByUser: Boolean): ChatStep? {
    val base = chat.copy(context = null, phase = Phase.Idle)
    return when (chat.mode) {
        AutoContinueMode.Off -> dropContext(chat)

        AutoContinueMode.Suggest -> ChatStep(base, cancel(id, chat))

        AutoContinueMode.Autopilot -> {
            val reason = if (isStoppedByUser) HaltReason.UserStopped else HaltReason.TurnFailed
            halt(id, base, reason).copy(job = cancel(id, chat))
        }
    }
}

private fun draftTyped(id: String, chat: ChatAuto): ChatStep? = when (chat.mode) {
    AutoContinueMode.Off -> null

    AutoContinueMode.Suggest -> when (chat.phase) {
        is Phase.Thinking -> ChatStep(chat.copy(phase = Phase.Idle), AutoContinueEffect.Cancel(id))
        is Phase.Offered, is Phase.Halted -> ChatStep(chat.copy(phase = Phase.Idle))
        Phase.Idle, Phase.Paused, is Phase.Countdown, is Phase.Delivering -> null
    }

    AutoContinueMode.Autopilot -> if (chat.isBusy) {
        halt(id, chat, HaltReason.HumanTookOver).copy(job = AutoContinueEffect.Cancel(id))
    } else {
        null
    }
}

private fun AutoContinueState.Ready.refresh(id: String, chat: ChatAuto): ChatStep? {
    val context = chat.context ?: return null
    if (chat.mode == AutoContinueMode.Off || chat.isBusy || chat.phase is Phase.Delivering) return null
    return ask(id, chat, context)
}

private fun edit(id: String, chat: ChatAuto): ChatStep? {
    val countdown = chat.phase as? Phase.Countdown ?: return null
    return ChatStep(
        chat = chat.copy(mode = AutoContinueMode.Suggest, phase = Phase.Idle),
        job = AutoContinueEffect.Cancel(id),
        save = save(id, AutoContinueMode.Suggest),
        output = AutoContinueOutput.Draft(id, countdown.text),
    )
}

private fun stop(id: String, chat: ChatAuto): ChatStep? {
    if (chat.mode != AutoContinueMode.Autopilot) return null
    return halt(id, chat, HaltReason.UserStopped).copy(job = cancel(id, chat))
}

private fun AutoContinueState.Ready.answered(id: String, chat: ChatAuto, intent: Internal.Answered): ChatStep? {
    if (!chat.isThinking(intent.seq)) return null
    val verdict = intent.verdict
    if (chat.mode != AutoContinueMode.Autopilot) return ChatStep(chat.copy(phase = Phase.Offered(verdict)))
    val text = verdict.options.firstOrNull()?.text
    return when {
        verdict.status == ContinuationStatus.Done -> halt(id, chat, HaltReason.Done, verdict)

        verdict.status == ContinuationStatus.NeedsUser || text == null -> halt(id, chat, HaltReason.NeedsUser, verdict)

        chat.autoTurns >= settings.autoTurnLimit -> halt(id, chat, HaltReason.LimitReached, verdict)

        isNearRepeat(chat.lastAutoText, text) -> halt(id, chat, HaltReason.Looping, verdict)

        settings.countdownSeconds == 0 -> deliver(id, chat, intent.seq, text)

        else -> ChatStep(
            chat.copy(phase = Phase.Countdown(intent.seq, text, settings.countdownSeconds, endsAt(intent.at))),
            AutoContinueEffect.Countdown(id, intent.seq, settings.countdownSeconds),
        )
    }
}

private fun askFailed(id: String, chat: ChatAuto, intent: Internal.AskFailed): ChatStep? {
    if (!chat.isThinking(intent.seq)) return null
    return if (chat.mode == AutoContinueMode.Autopilot) {
        halt(id, chat, HaltReason.ContinuerFailed, failure = intent.failure)
    } else {
        ChatStep(chat.copy(phase = Phase.Halted(HaltReason.ContinuerFailed, failure = intent.failure)))
    }
}

private fun AutoContinueState.Ready.ask(id: String, chat: ChatAuto, context: ContinuationContext): ChatStep {
    val seq = chat.seq + 1
    return ChatStep(
        chat = chat.copy(phase = Phase.Thinking(seq), seq = seq, context = context),
        job = AutoContinueEffect.Ask(
            chat = id,
            seq = seq,
            target = settings.continuerTarget ?: context.route,
            context = context,
            mode = chat.mode,
            optionCount = settings.optionCount,
        ),
    )
}

/** Sends [text]; the answered context is dropped, so nothing asks about the same turn again. */
private fun deliver(id: String, chat: ChatAuto, seq: Long, text: String): ChatStep = ChatStep(
    chat = chat.copy(
        phase = Phase.Delivering(seq, text),
        autoTurns = chat.autoTurns + 1,
        lastAutoText = text,
        context = null,
    ),
    job = AutoContinueEffect.Deliver(id, seq, text),
)

/** An ended turn of a chat with auto-continue off: the kept context is no longer the latest. */
private fun dropContext(chat: ChatAuto): ChatStep? =
    chat.takeIf { it.context != null }?.let { ChatStep(it.copy(context = null)) }

/** Stops the autopilot: suggest mode keeps the [verdict] options offered. */
private fun halt(
    id: String,
    chat: ChatAuto,
    reason: HaltReason,
    verdict: ContinuationVerdict? = null,
    failure: ContinuerFailure? = null,
): ChatStep = ChatStep(
    chat = chat.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(reason, verdict, failure)),
    save = save(id, AutoContinueMode.Suggest).takeIf { chat.mode != AutoContinueMode.Suggest },
)

private fun cancel(id: String, chat: ChatAuto): AutoContinueEffect.Cancel? =
    AutoContinueEffect.Cancel(id).takeIf { chat.isBusy }

private fun save(id: String, mode: AutoContinueMode?): AutoContinueEffect.SaveMode = AutoContinueEffect.SaveMode(
    id,
    mode,
)

private val ChatAuto.isBusy: Boolean get() = phase is Phase.Thinking || phase is Phase.Countdown

private fun ChatAuto.isThinking(seq: Long): Boolean = (phase as? Phase.Thinking)?.seq == seq

private fun ChatAuto.isDelivering(seq: Long): Boolean = (phase as? Phase.Delivering)?.seq == seq
