package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Serializes caller preparation; machine publication and native submission belong to the session scope. */
internal class CodexSubmissions(
    private val session: CodexSession,
    private val scope: CoroutineScope,
    private val prepare: suspend (PromptRequest, CodexSubmission) -> Unit,
    private val publish: suspend (PromptRequest, CodexSubmission) -> Boolean,
    private val abandon: (CodexSubmission) -> Unit,
) {
    private val log = Log.tag("CodexSubmissions")
    private val mutex = Mutex()
    var pending: CodexSubmission? = null
        private set

    suspend fun send(request: PromptRequest): TurnId {
        if (!mutex.tryLock()) fail(EngineFailure.Session(SessionFailureReason.Busy))
        var entry: CodexSubmission? = null
        try {
            // A foreign active turn must be rejected before installing this request's stop identity.
            session.ensureReadyForPolicy()
            val last = (session.machine.state.value as? ActiveSessionState.Ready)?.lastTurn
            if (pending != null && pending?.turn?.id != last?.id) {
                fail(EngineFailure.Session(SessionFailureReason.Busy))
            }
            val turn = Turn(TurnId(Uuid.random().toString()), request.id, session.target)
            val submission = CodexSubmission(turn, session.hostedJobs::revoke)
            entry = submission
            pending = submission
            return session.connectionMutex.withLock {
                prepare(request, submission)
                submission.isHandedOff = true
                scope.launch { publishOwned(request, submission) }
                submission.accepted.await()
            }
        } finally {
            if (entry?.isHandedOff == false) finish(entry)
            mutex.unlock()
        }
    }

    /** Publication cannot be abandoned by cancellation of the caller's acceptance wait. */
    private suspend fun publishOwned(request: PromptRequest, submission: CodexSubmission) {
        var isPublished = false
        try {
            submission.ensureAllowed()
            isPublished = publish(request, submission)
            if (!isPublished) fail(EngineFailure.Session(SessionFailureReason.Busy))
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineException) {
            log.w(error) { "Codex submission publication refused" }
            submission.accepted.completeExceptionally(error)
        } finally {
            if (!isPublished) {
                abandon(submission)
                finish(submission)
            }
        }
    }

    /** Identity check prevents old cleanup from clearing a newer request; revoked entries require a receipt. */
    fun finish(submission: CodexSubmission) {
        submission.settled()
        if (pending === submission && !submission.isStopRequested) pending = null
        session.runtime.release(session)
    }
}
