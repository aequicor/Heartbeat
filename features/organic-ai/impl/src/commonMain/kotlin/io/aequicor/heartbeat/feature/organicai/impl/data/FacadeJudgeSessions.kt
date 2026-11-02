package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.impl.domain.JudgeSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeClaim
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeExecution
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeJournal
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
 * permission request ends it), bounded in time. Only a completed turn gives a verdict. The session is always
 * stopped, closed and archived afterwards.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeJudgeSessions(private val facade: EngineFacade, private val journal: OrganicNativeJournal) :
    JudgeSessions {
    private val log = Log.tag("FacadeJudgeSessions")

    override suspend fun deliberate(
        target: EngineTarget,
        prompt: String,
        logicalSession: LogicalSessionContext?,
        execution: SessionExecutionBinding?,
        onSession: suspend (SessionRef) -> Unit,
    ): String {
        val attempt = attempt(checkNotNull(execution) { "Judge execution authority is missing" }, target, prompt)
        if (attempt.isRestored) {
            attempt.initial.session?.let { onSession(it) }
            return restored(attempt)
        }
        val creates = facade.engines.features(target.engine).resolve(CreatesSessions).orThrow()
        attempt.opening()
        val session = creates.create(
            CreateSessionRequest(
                target,
                workspace = null,
                areDetachedToolsEnabled = false,
                logicalSession = logicalSession,
                execution = execution,
            ),
        )
        log.i { "judge session opened on ${target.engine.value}" }
        try {
            attempt.opened(session.ref)
            onSession(session.ref)
            val turn = session.submit(
                attempt.claim.request,
                attempt.claim.prompt,
                TrustLevel.Ask,
                beforeSend = attempt::prepare,
            )
            attempt.accepted(session.ref, turn)
            val outcome = withTimeoutOrNull(DELIBERATION) {
                session.awaitTurn(turn) { pending -> if (pending.isNotEmpty()) session.cancelQuietly(turn) }
            } ?: throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
            if (outcome != TurnOutcome.Completed) {
                attempt.settled(outcome, null)
                if (outcome is TurnOutcome.Failed) throw EngineException(outcome.failure)
                return ""
            }
            val history = session.features.resolve(SessionHistory).orThrow()
            val answer = answerOf(
                history.page(HistoryPageRequest(limit = ANSWER_ITEMS)).items,
                turn,
                maxChars = OrganismBounds.MAX_RESULT,
            ).orEmpty()
            attempt.settled(outcome, answer)
            return answer
        } finally {
            withContext(NonCancellable) { dismiss(session, attempt) }
        }
    }

    private suspend fun attempt(
        execution: SessionExecutionBinding,
        target: EngineTarget,
        prompt: String,
    ): OrganicNativeExecution {
        val address = OrganicExecutorAddress.parse(execution.instance.id)
        check(address.kind == OrganicExecutorAddress.Kind.Judge) { "Judge has another executor kind" }
        val id = OrganismId(address.organism)
        val previous = journal.attempts(id, address.incarnation)
            .singleOrNull { it.claim.address == execution.instance.id }
        if (previous != null) {
            check(previous.claim.execution == execution) { "Judge authority changed" }
            return OrganicNativeExecution(journal, id, address.incarnation, previous, isRestored = true)
        }
        val request = newRequest()
        val claim = OrganicNativeClaim(request.value, execution.instance.id, execution, target, null, request, prompt)
        val receipt = journal.reserve(id, address.incarnation, claim)
        return OrganicNativeExecution(journal, id, address.incarnation, receipt, isRestored = false)
    }

    /** A case never reruns its dossier after uncertain delivery; the native receipt remains available for Stop. */
    private fun restored(attempt: OrganicNativeExecution): String = when (val outcome = attempt.initial.outcome) {
        TurnOutcome.Completed -> attempt.initial.answer.orEmpty()

        TurnOutcome.Cancelled -> ""

        is TurnOutcome.Failed -> throw EngineException(outcome.failure)

        TurnOutcome.Unknown, null -> throw EngineException(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, attempt.claim.request),
        )
    }

    /** Stops whatever still runs, closes the handle and archives the session; failures are only logged. */
    private suspend fun dismiss(session: ActiveSession, attempt: OrganicNativeExecution) {
        try {
            val isStopped = session.stopTurn(STOP_WAIT)
            if (!isStopped) {
                log.w { "judge stop remains unconfirmed; its native receipt is retained" }
                return
            }
            val ready = session.state.value as? ActiveSessionState.Ready
            if (ready != null) attempt.idleAfterStop(session.ref, ready.lastTurn)
            when (val archives = facade.sessions.get(session.ref).features.resolve(ArchivesSessions)) {
                is FeatureAccess.Available -> archives.feature.setArchived(true)
                is FeatureAccess.Unavailable -> log.w(EngineException(archives.reason)) { "judge session not archived" }
                FeatureAccess.Unsupported -> log.d { "${session.ref.engine.value} sessions cannot be archived" }
            }
            log.i { "judge session on ${session.ref.engine.value} dismissed" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "judge session on ${session.ref.engine.value} was not fully dismissed" }
        } finally {
            withContext(NonCancellable) { closeQuietly(session) }
        }
    }

    private suspend fun closeQuietly(session: ActiveSession) {
        try {
            session.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "judge handle could not be closed" }
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
