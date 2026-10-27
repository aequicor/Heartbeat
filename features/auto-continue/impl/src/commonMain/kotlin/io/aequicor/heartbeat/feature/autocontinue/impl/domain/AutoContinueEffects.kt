package io.aequicor.heartbeat.feature.autocontinue.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEffect
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueHost
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuerFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/** Storage of auto-continue settings and chat modes owned by the profile. */
internal interface AutoContinueStore {
    /** Stored settings; defaults when nothing was saved. */
    suspend fun settings(): AutoContinueSettings

    /** Stored modes by chat id. */
    suspend fun modes(): Map<String, AutoContinueMode>

    /** Replaces the settings. */
    suspend fun saveSettings(settings: AutoContinueSettings)

    /** Stores the [mode] of [chat]; null removes it. */
    suspend fun saveMode(chat: String, mode: AutoContinueMode?)
}

/**
 * Executes auto-continue effects. Asking, counting down and delivering run as the single job of their chat: a new
 * job or [AutoContinueEffect.Cancel] cancels the previous one, so a stale request never spends tokens or sends text.
 * The continuer gets one retry after an answer that is not a verdict. Delivery goes to the first [hosts] entry; without
 * a host (the studio is not running) it fails. Texts are user data and are never logged.
 */
internal class AutoContinueEffects(
    private val store: AutoContinueStore,
    private val prompts: IsolatedPrompts,
    private val hosts: () -> Set<AutoContinueHost>,
    private val isEnabled: suspend () -> Boolean,
) : EffectHandler<AutoContinueEffect, AutoContinueIntent> {

    private val log = Log.tag("AutoContinueEffects")
    private val saving = Mutex()
    private val jobsLock = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    override suspend fun handle(effect: AutoContinueEffect, machine: EffectScope<AutoContinueIntent>) {
        when (effect) {
            AutoContinueEffect.Load -> {
                val settings = store.settings()
                val modes = store.modes()
                log.i { "auto-continue loaded: ${modes.size} chat mode(s)" }
                machine.send(Internal.Loaded(settings, modes))
            }

            is AutoContinueEffect.SaveSettings -> saving.withLock { store.saveSettings(effect.settings) }

            is AutoContinueEffect.SaveMode -> saving.withLock { store.saveMode(effect.chat, effect.mode) }

            is AutoContinueEffect.Ask -> job(effect.chat) { machine.send(ask(effect)) }

            is AutoContinueEffect.Countdown -> job(effect.chat) {
                delay(effect.seconds.seconds)
                machine.send(Internal.CountdownElapsed(effect.chat, effect.seq))
            }

            is AutoContinueEffect.Deliver -> job(effect.chat) { machine.send(deliver(effect)) }

            is AutoContinueEffect.Cancel -> jobsLock.withLock { jobs.remove(effect.chat) }?.let {
                it.cancel()
                log.d { "auto-continue job of ${effect.chat} cancelled" }
            }
        }
    }

    private suspend fun ask(effect: AutoContinueEffect.Ask): AutoContinueIntent {
        if (!isEnabled()) return Internal.AskFailed(effect.chat, effect.seq, ContinuerFailure.Unavailable)
        val prompt = continuerPrompt(effect.context, effect.mode, effect.optionCount)
        var result: AutoContinueIntent = Internal.AskFailed(effect.chat, effect.seq, ContinuerFailure.InvalidAnswer)
        var attempt = 0
        while (attempt < ATTEMPTS && result is Internal.AskFailed && result.failure == ContinuerFailure.InvalidAnswer) {
            val text = if (attempt == 0) prompt else retryPrompt(prompt)
            result = answer(
                effect,
                prompts.ask(IsolatedPrompt(effect.target, text, ASK_TIMEOUT, ContinuerBudget.ANSWER_CHARS)),
            )
            attempt++
        }
        return result
    }

    private fun answer(effect: AutoContinueEffect.Ask, answer: IsolatedAnswer): AutoContinueIntent = when (answer) {
        is IsolatedAnswer.Unavailable -> {
            log.w { "continuer of ${effect.chat} unavailable: ${answer.reason}" }
            Internal.AskFailed(effect.chat, effect.seq, answer.reason.toFailure())
        }

        is IsolatedAnswer.Answered -> {
            val verdict = parseVerdict(answer.text, effect.optionCount)
            if (verdict == null) {
                log.w { "continuer of ${effect.chat} gave no verdict" }
                Internal.AskFailed(effect.chat, effect.seq, ContinuerFailure.InvalidAnswer)
            } else {
                log.i { "continuer of ${effect.chat} answered ${verdict.status}, options ${verdict.options.size}" }
                Internal.Answered(effect.chat, effect.seq, verdict)
            }
        }
    }

    private suspend fun deliver(effect: AutoContinueEffect.Deliver): AutoContinueIntent {
        val host = hosts().firstOrNull()
        val isSent = host?.deliver(effect.chat, effect.text) == true
        return if (isSent) {
            log.i { "autopilot message delivered to ${effect.chat}" }
            Internal.Delivered(effect.chat, effect.seq)
        } else {
            log.w {
                "autopilot message not delivered to ${effect.chat}: host ${if (host == null) "missing" else "refused"}"
            }
            Internal.DeliveryFailed(effect.chat, effect.seq)
        }
    }

    /** Runs [work] as the only job of [chat], replacing (cancelling) the previous one; returns when it ends. */
    private suspend fun job(chat: String, work: suspend () -> Unit) = coroutineScope {
        // Registered before it starts, so a Cancel that arrives right after the effect always finds it.
        val job = launch(start = CoroutineStart.LAZY) { work() }
        jobsLock.withLock { jobs.put(chat, job) }?.cancel()
        job.join()
        jobsLock.withLock { if (jobs[chat] === job) jobs.remove(chat) }
    }

    private companion object {
        const val ATTEMPTS = 2
        val ASK_TIMEOUT = 90.seconds
    }
}

private fun IsolatedPromptFailure.toFailure(): ContinuerFailure = when (this) {
    IsolatedPromptFailure.Unsupported, IsolatedPromptFailure.Closing -> ContinuerFailure.Unavailable
    IsolatedPromptFailure.Busy -> ContinuerFailure.Busy
    IsolatedPromptFailure.Timeout -> ContinuerFailure.Timeout
    IsolatedPromptFailure.Incomplete, IsolatedPromptFailure.Failed -> ContinuerFailure.Failed
}
