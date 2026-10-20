package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Isolated prompts share the safety review's isolation (native Off before the prompt, tool calls denied by
 * [AgentSafetyHook]) and bounded capacity.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class FacadeIsolatedPrompts(
    facade: Lazy<EngineFacade>,
    isolation: AgentSafetyIsolation,
    scope: CoroutineScope,
    slots: ServiceTurnSlots,
    settleGrace: Duration,
) : IsolatedPrompts {
    @Inject
    constructor(
        facade: Lazy<EngineFacade>,
        isolation: AgentSafetyIsolation,
        @ForScope(ProfileScope::class) scope: CoroutineScope,
    ) : this(
        facade,
        isolation,
        scope,
        IsolatedTurnSlots(MAX_PARALLEL_PROMPTS, MAX_QUEUED_PROMPTS, QUEUE_WAIT, "Isolated prompt", LOG_TAG),
        SETTLE_GRACE,
    )

    private val log = Log.tag(LOG_TAG)
    private val turns = IsolatedServiceTurns(facade, isolation, slots, scope, settleGrace, log)

    override suspend fun ask(prompt: IsolatedPrompt): IsolatedAnswer = try {
        val turn = ServiceTurn(
            prompt.target,
            RequestId(ISOLATED_REQUEST_PREFIX + Uuid.random()),
            prompt.text,
            prompt.timeout,
            prompt.maxAnswerChars,
        )
        when (val outcome = turns.run(turn)) {
            is ServiceTurnOutcome.Answered -> IsolatedAnswer.Answered(outcome.text.orEmpty())
            is ServiceTurnOutcome.Refused -> IsolatedAnswer.Unavailable(outcome.reason.toFailure())
        }.also { log.d { "Isolated prompt finished: ${it.summary()}" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Provider messages may echo the prompt: only the type is logged.
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Isolated prompt failed" }
        IsolatedAnswer.Unavailable(IsolatedPromptFailure.Failed)
    }
}

private fun ServiceTurnRefusal.toFailure(): IsolatedPromptFailure = when (this) {
    ServiceTurnRefusal.NoToolCatalog, ServiceTurnRefusal.Unsupported, ServiceTurnRefusal.NotSendable ->
        IsolatedPromptFailure.Unsupported

    ServiceTurnRefusal.Busy -> IsolatedPromptFailure.Busy

    ServiceTurnRefusal.Closing -> IsolatedPromptFailure.Closing

    ServiceTurnRefusal.Timeout -> IsolatedPromptFailure.Timeout

    ServiceTurnRefusal.Incomplete -> IsolatedPromptFailure.Incomplete

    ServiceTurnRefusal.NoHistory -> IsolatedPromptFailure.Failed
}

private fun IsolatedAnswer.summary(): String = when (this) {
    is IsolatedAnswer.Answered -> "answered length=${text.length}"
    is IsolatedAnswer.Unavailable -> "unavailable reason=$reason"
}

/** Request ids of isolated prompts; [AgentSafetyHook] denies their tool calls even after isolation is released. */
internal const val ISOLATED_REQUEST_PREFIX = "isolated-prompt-"
private const val LOG_TAG = "IsolatedPrompts"
private const val MAX_PARALLEL_PROMPTS = 2
private const val MAX_QUEUED_PROMPTS = 8
private val QUEUE_WAIT = 1.minutes

internal val SETTLE_GRACE = 1.minutes
