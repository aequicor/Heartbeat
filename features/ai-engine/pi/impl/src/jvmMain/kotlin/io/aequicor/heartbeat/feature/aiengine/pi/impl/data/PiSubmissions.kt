package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Registers exact identity synchronously before preflight, then hands preparation to the profile. */
internal class PiSubmissions(private val scope: CoroutineScope) {
    private val log = Log.tag("PiSubmissions")
    var pending: PiSubmission? = null
        private set

    suspend fun send(request: PromptRequest, turn: Turn, prepare: suspend (PiSubmission) -> Unit): TurnId {
        if (pending != null) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
        val entry = PiSubmission(turn, trust = request.trust ?: TrustLevel.Ask)
        pending = entry
        val job = scope.launch {
            try {
                entry.ensureAllowed()
                prepare(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (error: EngineException) {
                log.w(error) { "Pi prompt preparation failed" }
                entry.accepted.completeExceptionally(error)
            } catch (error: Exception) {
                log.w(error.withoutDetails()) { "Pi prompt preparation failed" }
                entry.accepted.completeExceptionally(EngineException(EngineFailure.Unknown()))
            } finally {
                if (!entry.isHandedOff) finish(entry)
            }
        }
        job.invokeOnCompletion { error ->
            // A closed profile can reject launch before its body/finally ever runs.
            if (error != null) entry.accepted.completeExceptionally(error)
        }
        return entry.accepted.await()
    }

    fun finish(entry: PiSubmission) {
        entry.settled()
        if (pending === entry && !entry.isStopRequested) pending = null
    }

    fun release(entry: PiSubmission) {
        check(entry.isStopRequested)
        if (pending === entry) pending = null
    }
}
