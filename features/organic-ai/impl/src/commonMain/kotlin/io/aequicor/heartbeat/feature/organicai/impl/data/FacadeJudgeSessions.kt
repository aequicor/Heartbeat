package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.impl.domain.JudgeSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.answerOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A new judge session per call: no project, no hosted tools, one turn that may not ask the user for anything (a
 * permission request ends it), bounded in time. The session is always stopped, closed and archived afterwards.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeJudgeSessions(private val facade: EngineFacade) : JudgeSessions {
    private val log = Log.tag("FacadeJudgeSessions")

    override suspend fun deliberate(
        target: EngineTarget,
        prompt: String,
        onSession: suspend (SessionRef) -> Unit,
    ): String {
        val creates = facade.engines.features(target.engine).resolve(CreatesSessions).orThrow()
        val session = creates.create(CreateSessionRequest(target, workspace = null, areDetachedToolsEnabled = false))
        log.i { "judge session opened on ${target.engine.value}" }
        try {
            onSession(session.ref)
            val turn = session.submit(newRequest(), prompt, TrustLevel.Ask)
            val outcome = withTimeoutOrNull(DELIBERATION) {
                session.awaitTurn(turn) { pending -> if (pending.isNotEmpty()) session.cancelQuietly(turn) }
            } ?: throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
            if (outcome is TurnOutcome.Failed) throw EngineException(outcome.failure)
            val history = session.features.resolve(SessionHistory).orThrow()
            return answerOf(history.page(HistoryPageRequest(limit = ANSWER_ITEMS)).items, turn).orEmpty()
        } finally {
            withContext(NonCancellable) { dismiss(session) }
        }
    }

    /** Stops whatever still runs, closes the handle and archives the session; failures are only logged. */
    private suspend fun dismiss(session: ActiveSession) {
        try {
            session.stop(STOP_WAIT)
            val archives = facade.sessions.get(session.ref).features.resolve(ArchivesSessions)
            (archives as? FeatureAccess.Available)?.feature?.setArchived(true)
            log.i { "judge session on ${session.ref.engine.value} dismissed" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "judge session on ${session.ref.engine.value} was not fully dismissed" }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newRequest() = RequestId("organic-judge-${Uuid.random()}")

    private companion object {
        val DELIBERATION = 10.minutes
        val STOP_WAIT = 10.seconds
        const val ANSWER_ITEMS = 50
    }
}
