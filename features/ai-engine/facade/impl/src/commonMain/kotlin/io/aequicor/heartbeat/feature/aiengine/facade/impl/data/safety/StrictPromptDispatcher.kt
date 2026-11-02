package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark

/** No bind/runtime/session side effects: both route checks use every field of the expected source context. */
@Inject
internal class StrictPromptDispatcher(private val routes: RouteResolver, private val dispatchers: DispatcherProvider) :
    StrictPromptExecution {
    override suspend fun answer(prompt: IsolatedPrompt, deadline: TimeMark): IsolatedAnswer = withContext(
        dispatchers.io,
    ) {
        val constraints = requireNotNull(prompt.constraints)
        try {
            if (deadline.hasPassedNow()) return@withContext IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout)
            val resolved = routes.resolveIsolated(prompt.target, constraints.expectedSource)
            currentCoroutineContext().ensureActive()
            if (deadline.hasPassedNow()) return@withContext IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout)
            val answer = resolved.registration.factory.value.answerIsolated(
                prompt,
                resolved.source,
                resolved.context,
                deadline,
            ) { requireCurrent(prompt, deadline) }
            currentCoroutineContext().ensureActive()
            routes.resolveIsolated(prompt.target, constraints.expectedSource)
            when {
                deadline.hasPassedNow() -> IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout)

                answer is IsolatedAnswer.Answered && answer.text.length > prompt.maxAnswerChars ->
                    IsolatedAnswer.Unavailable(IsolatedPromptFailure.ResponseTooLarge)

                else -> answer
            }
        } catch (e: EngineException) {
            IsolatedAnswer.Unavailable(e.isolatedFailure() ?: throw e)
        }
    }

    private suspend fun requireCurrent(prompt: IsolatedPrompt, deadline: TimeMark) {
        currentCoroutineContext().ensureActive()
        if (deadline.hasPassedNow()) throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        routes.resolveIsolated(prompt.target, requireNotNull(prompt.constraints).expectedSource)
        currentCoroutineContext().ensureActive()
        if (deadline.hasPassedNow()) throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
    }
}

private fun EngineException.isolatedFailure(): IsolatedPromptFailure? = when {
    (failure as? EngineFailure.Authentication)?.reason?.reason == AuthFailureReason.SourceChanged ->
        IsolatedPromptFailure.SourceChanged

    (failure as? EngineFailure.Transport)?.reason == TransportFailureReason.Timeout -> IsolatedPromptFailure.Timeout

    else -> null
}
