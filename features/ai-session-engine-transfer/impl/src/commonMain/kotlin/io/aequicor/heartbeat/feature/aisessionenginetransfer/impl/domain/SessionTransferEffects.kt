package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationSegment
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.Handoff
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.HandoffStatus
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.LogicalConversation
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferEffect
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferIntent
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferFailure
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes transfer effects through domain ports. The target segment is journaled as Pending before the
 * handoff is submitted, so a crash mid-send is later read as an unknown delivery instead of a lost session.
 * A rejected handoff rolls the journal back; the created native session stays in the engine catalog.
 */
internal class SessionTransferEffects(
    private val gate: TransferGate,
    private val transcripts: SessionTranscripts,
    private val sessions: EngineSessions,
    private val journal: ConversationJournal,
    private val stamps: TransferStamps,
) : EffectHandler<SessionTransferEffect, SessionTransferIntent> {

    private val log = Log.tag("SessionTransferEffects")

    override suspend fun handle(effect: SessionTransferEffect, machine: EffectScope<SessionTransferIntent>) {
        when (effect) {
            is SessionTransferEffect.Prepare -> prepare(effect.request, machine)
            is SessionTransferEffect.Seed -> seed(effect, machine)
        }
    }

    private suspend fun prepare(request: TransferRequest, machine: EffectScope<SessionTransferIntent>) {
        machine.send(if (gate.isEnabled()) preparation(request) else request.failed(TransferFailure.Disabled))
    }

    private suspend fun preparation(request: TransferRequest): SessionTransferIntent.Internal {
        val conversation = resolveConversation(request) ?: return request.failed(TransferFailure.ConversationNotFound)
        if (conversation.current.ref != request.source) return request.failed(TransferFailure.NotLatestSegment)
        val transcript = transcripts.read(request.source, HANDOFF_BUDGET_CHARS)
        val prompt = composeHandoff(transcript, stamps.request())
        log.i {
            "handoff composed: ${transcript.items.size} items, coverage=${transcript.coverage}, " +
                "truncated=${transcript.isTruncated}, segments=${conversation.segments.size}"
        }
        return SessionTransferIntent.Internal.Prepared(request.transfer, conversation, prompt)
    }

    private suspend fun resolveConversation(request: TransferRequest): LogicalConversation? =
        when (val id = request.conversation) {
            null -> {
                val first = ConversationSegment(request.source, stamps.now())
                LogicalConversation(stamps.conversation(), listOf(first))
            }

            else -> journal.get(id)
        }

    private suspend fun seed(effect: SessionTransferEffect.Seed, machine: EffectScope<SessionTransferIntent>) {
        val request = effect.request
        val session = sessions.create(request.target, request.workspace)
        log.i { "target session created on ${session.ref.engine.value}" }
        val segment = try {
            record(effect, session)
        } finally {
            withContext(NonCancellable) { release(session) }
        }
        machine.send(SessionTransferIntent.Internal.Seeded(request.transfer, effect.conversation.id, segment))
    }

    private suspend fun record(effect: SessionTransferEffect.Seed, session: SeedSession): ConversationSegment {
        val request = effect.request
        val pending = ConversationSegment(
            ref = session.ref,
            startedAt = stamps.now(),
            target = request.target,
            workspace = request.workspace,
            handoff = Handoff(request.source, effect.prompt.id, HandoffStatus.Pending),
        )
        journal.put(effect.conversation + pending)
        val status = deliver(effect, session)
        val settled = pending.copy(handoff = Handoff(request.source, effect.prompt.id, status))
        settle(effect.conversation + settled)
        return settled
    }

    /**
     * The handoff has already been delivered or may have been: failing now would invite a second target session
     * and a resend. A journaled Pending segment already reads as an unknown delivery.
     */
    private suspend fun settle(conversation: LogicalConversation) {
        try {
            journal.put(conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "settled handoff status not journaled; the pending segment reads as unknown delivery" }
        }
    }

    /** Never resends: an ambiguous delivery is recorded as Unknown, only a proven rejection rolls back. */
    private suspend fun deliver(effect: SessionTransferEffect.Seed, session: SeedSession): HandoffStatus = try {
        session.send(effect.prompt)
        HandoffStatus.Accepted
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        if (e.failure.isRejection()) {
            rollback(effect)
            throw e
        }
        log.w(e) { "handoff delivery is unknown: ${e.failure.code}" }
        HandoffStatus.Unknown
    } catch (e: Exception) {
        // Like the active session machine: a non-domain submit failure cannot prove non-delivery.
        log.e(e) { "handoff delivery failed unexpectedly, recorded as unknown" }
        HandoffStatus.Unknown
    }

    private suspend fun rollback(effect: SessionTransferEffect.Seed) {
        try {
            if (effect.request.conversation == null) {
                journal.remove(effect.conversation.id)
            } else {
                journal.put(effect.conversation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "journal rollback failed; the pending segment reads as unknown delivery" }
        }
    }

    private suspend fun release(session: SeedSession) {
        try {
            session.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "target session handle release failed" }
        }
    }
}

/**
 * Failures proving the engine did not take the handoff, so a rollback cannot hide a delivered transcript.
 * Timeouts, protocol violations, crashes, closed handles and unclassified failures may follow a delivery.
 */
internal fun EngineFailure.isRejection(): Boolean = when (this) {
    is EngineFailure.Authentication, is EngineFailure.RateLimited, is EngineFailure.QuotaExceeded,
    is EngineFailure.ContextLimitExceeded, is EngineFailure.Access, is EngineFailure.Session,
    is EngineFailure.History,
    -> true

    is EngineFailure.Request -> reason != RequestFailureReason.OutcomeUnknown

    is EngineFailure.Engine -> reason != EngineFailureReason.Crashed

    is EngineFailure.Transport ->
        reason == TransportFailureReason.NetworkUnavailable || reason == TransportFailureReason.ServiceUnavailable

    is EngineFailure.Lifecycle, is EngineFailure.Unknown -> false
}

private fun TransferRequest.failed(failure: TransferFailure) = SessionTransferIntent.Internal.Failed(transfer, failure)
