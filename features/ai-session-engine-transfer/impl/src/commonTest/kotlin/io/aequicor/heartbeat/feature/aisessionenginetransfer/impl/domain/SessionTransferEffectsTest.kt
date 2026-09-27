package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationSegment
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.Handoff
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.HandoffStatus
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.LogicalConversation
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferEffect
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferIntent
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferFailure
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.Request
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.SourceRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.Target
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.TargetRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.text
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionTransferEffectsTest {
    private val now = Instant.fromEpochSeconds(100)
    private val sent = mutableListOf<SessionTransferIntent>()
    private val machine = object : EffectScope<SessionTransferIntent> {
        override suspend fun send(intent: SessionTransferIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
    private var enabled = true
    private val journal = FakeJournal()
    private val session = FakeSession()
    private val reads = mutableListOf<SessionRef>()
    private val effects = SessionTransferEffects(
        gate = { enabled },
        transcripts = { source, _ ->
            reads += source
            Transcript(listOf(text(0, MessageRole.User, "hello")), HistoryCoverage.Complete, isTruncated = false)
        },
        sessions = object : EngineSessions {
            override suspend fun create(target: EngineTarget, workspace: WorkspaceRef?): SeedSession {
                assertEquals(Target, target)
                return session
            }
        },
        journal = journal,
        stamps = object : TransferStamps {
            override fun conversation() = ConversationId("new-conversation")

            override fun request() = RequestId("handoff")

            override fun now(): Instant = now
        },
    )
    private val newConversation =
        LogicalConversation(ConversationId("new-conversation"), listOf(ConversationSegment(SourceRef, now)))

    @Test
    fun `disabled toggles stop the transfer before any read`() = runTest {
        enabled = false
        effects.handle(SessionTransferEffect.Prepare(Request), machine)
        assertEquals(listOf<SessionTransferIntent>(failed(TransferFailure.Disabled)), sent)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `a new conversation starts from the source session`() = runTest {
        effects.handle(SessionTransferEffect.Prepare(Request), machine)
        val prepared = assertIs<SessionTransferIntent.Internal.Prepared>(sent.single())
        assertEquals(newConversation, prepared.conversation)
        assertEquals(RequestId("handoff"), prepared.prompt.id)
        assertEquals(listOf(SourceRef), reads)
        assertTrue(journal.writes.isEmpty())
    }

    @Test
    fun `existing conversations are transferred only from their current segment`() = runTest {
        val existing = newConversation.copy(id = ConversationId("existing"))
        journal.stored[existing.id] = existing
        effects.handle(SessionTransferEffect.Prepare(Request.copy(conversation = existing.id)), machine)
        assertEquals(existing, assertIs<SessionTransferIntent.Internal.Prepared>(sent.single()).conversation)

        sent.clear()
        val moved = existing + segment(HandoffStatus.Accepted)
        journal.stored[moved.id] = moved
        effects.handle(SessionTransferEffect.Prepare(Request.copy(conversation = moved.id)), machine)
        assertEquals(listOf<SessionTransferIntent>(failed(TransferFailure.NotLatestSegment)), sent)

        sent.clear()
        effects.handle(SessionTransferEffect.Prepare(Request.copy(conversation = ConversationId("gone"))), machine)
        assertEquals(listOf<SessionTransferIntent>(failed(TransferFailure.ConversationNotFound)), sent)
    }

    @Test
    fun `accepted handoff is journaled before submission and the handle is released`() = runTest {
        session.onSend = { assertEquals(HandoffStatus.Pending, journal.stored.getValue(newConversation.id).tail) }
        effects.handle(seed(), machine)
        val accepted = segment(HandoffStatus.Accepted)
        assertEquals(listOf<SessionTransferIntent>(seeded(accepted)), sent)
        assertEquals(newConversation + accepted, journal.stored[newConversation.id])
        assertEquals(1, session.sends)
        assertTrue(session.isClosed)
    }

    @Test
    fun `ambiguous delivery is recorded as unknown and never resent`() = runTest {
        session.onSend = {
            throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("handoff")))
        }
        effects.handle(seed(), machine)
        assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Unknown))), sent)
        assertEquals(1, session.sends)
        assertTrue(session.isClosed)
    }

    @Test
    fun `uncorrelated unknown outcomes are still never treated as rejections`() = runTest {
        session.onSend = {
            throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("other")))
        }
        effects.handle(seed(), machine)
        assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Unknown))), sent)
    }

    @Test
    fun `failures that may follow a delivery are recorded as unknown and keep the segment`() = runTest {
        listOf(
            EngineFailure.Transport(TransportFailureReason.Timeout),
            EngineFailure.Transport(TransportFailureReason.ProtocolViolation),
            EngineFailure.Engine(EngineFailureReason.Crashed),
            EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed),
            EngineFailure.Unknown(),
        ).forEach { failure ->
            sent.clear()
            session.onSend = { throw EngineException(failure) }
            effects.handle(seed(), machine)
            assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Unknown))), sent, failure.code)
            assertEquals(HandoffStatus.Unknown, journal.stored.getValue(newConversation.id).tail, failure.code)
        }
    }

    @Test
    fun `only failures proving non-delivery are rejections`() {
        val scope = LimitScope.Unknown
        val rejections = listOf(
            EngineFailure.Authentication(AuthFailure(AuthFailureReason.entries.first())),
            EngineFailure.RateLimited(scope),
            EngineFailure.QuotaExceeded(scope),
            EngineFailure.ContextLimitExceeded(),
            EngineFailure.Transport(TransportFailureReason.NetworkUnavailable),
        ) + AccessFailureReason.entries.map { EngineFailure.Access(it) } +
            SessionFailureReason.entries.map { EngineFailure.Session(it) } +
            HistoryFailureReason.entries.map { EngineFailure.History(it) } +
            listOf(RequestFailureReason.Invalid, RequestFailureReason.UnsupportedContent)
                .map { EngineFailure.Request(it) } +
            (EngineFailureReason.entries - EngineFailureReason.Crashed).map { EngineFailure.Engine(it) }
        rejections.forEach { assertTrue(it.isRejection(), it.code) }
        val ambiguous = listOf(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown),
            EngineFailure.Engine(EngineFailureReason.Crashed),
            EngineFailure.Unknown(),
        ) + (TransportFailureReason.entries - TransportFailureReason.NetworkUnavailable)
            .map { EngineFailure.Transport(it) } +
            LifecycleFailureReason.entries.map { EngineFailure.Lifecycle(it) }
        ambiguous.forEach { assertFalse(it.isRejection(), it.code) }
    }

    @Test
    fun `a failed rollback keeps the pending segment and still fails`() = runTest {
        val busy = EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        session.onSend = { throw busy }
        journal.failingRemove = true
        assertEquals(busy, assertFailsWith<EngineException> { effects.handle(seed(), machine) })
        assertEquals(HandoffStatus.Pending, journal.stored.getValue(newConversation.id).tail)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed pending record releases the session without submitting`() = runTest {
        journal.failingWrite = 1
        assertFailsWith<IllegalStateException> { effects.handle(seed(), machine) }
        assertEquals(0, session.sends)
        assertTrue(session.isClosed)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a delivered handoff completes even if its status cannot be journaled`() = runTest {
        journal.failingWrite = 2
        effects.handle(seed(), machine)
        assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Accepted))), sent)
        assertEquals(HandoffStatus.Pending, journal.stored.getValue(newConversation.id).tail)
    }

    @Test
    fun `cancellation during submission keeps the pending record and releases the handle`() = runTest {
        session.onSend = { throw CancellationException("profile closed") }
        assertFailsWith<CancellationException> { effects.handle(seed(), machine) }
        assertEquals(HandoffStatus.Pending, journal.stored.getValue(newConversation.id).tail)
        assertTrue(session.isClosed)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `unexpected submit errors cannot prove non-delivery`() = runTest {
        session.onSend = { throw IllegalStateException("transport bug") }
        effects.handle(seed(), machine)
        assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Unknown))), sent)
    }

    @Test
    fun `rejected handoff rolls the journal back and fails`() = runTest {
        val busy = EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        session.onSend = { throw busy }
        assertEquals(busy, assertFailsWith<EngineException> { effects.handle(seed(), machine) })
        assertTrue(newConversation.id !in journal.stored)
        assertTrue(sent.isEmpty())
        assertTrue(session.isClosed)

        val existing = newConversation.copy(id = ConversationId("existing"))
        journal.stored[existing.id] = existing
        assertFailsWith<EngineException> {
            effects.handle(seed(existing).copy(request = Request.copy(conversation = existing.id)), machine)
        }
        assertEquals(existing, journal.stored[existing.id])
    }

    @Test
    fun `failed release does not hide the settled segment`() = runTest {
        session.onClose = { throw IllegalStateException("lease") }
        effects.handle(seed(), machine)
        assertEquals(listOf<SessionTransferIntent>(seeded(segment(HandoffStatus.Accepted))), sent)
    }

    private fun seed(conversation: LogicalConversation = newConversation) = SessionTransferEffect.Seed(
        Request,
        conversation,
        PromptRequest(RequestId("handoff"), listOf(ContentPart.Text("transcript"))),
    )

    private fun segment(status: HandoffStatus) = ConversationSegment(
        ref = TargetRef,
        startedAt = now,
        target = Target,
        handoff = Handoff(SourceRef, RequestId("handoff"), status),
    )

    private fun seeded(segment: ConversationSegment) =
        SessionTransferIntent.Internal.Seeded(Request.transfer, newConversation.id, segment)

    private fun failed(failure: TransferFailure) = SessionTransferIntent.Internal.Failed(Request.transfer, failure)

    private val LogicalConversation.tail get() = current.handoff?.status

    private class FakeJournal : ConversationJournal {
        val stored = mutableMapOf<ConversationId, LogicalConversation>()
        val writes = mutableListOf<LogicalConversation>()

        /** 1-based index of a put that fails like a broken store. */
        var failingWrite: Int? = null

        override suspend fun get(id: ConversationId) = stored[id]

        override suspend fun put(conversation: LogicalConversation) {
            writes += conversation
            check(writes.size != failingWrite) { "store unavailable" }
            stored[conversation.id] = conversation
        }

        var failingRemove = false

        override suspend fun remove(id: ConversationId) {
            check(!failingRemove) { "store unavailable" }
            stored.remove(id)
        }
    }

    private class FakeSession : SeedSession {
        override val ref = TargetRef
        var sends = 0
        var isClosed = false
        var onSend: () -> Unit = {}
        var onClose: () -> Unit = {}

        override suspend fun send(prompt: PromptRequest) {
            sends++
            onSend()
        }

        override suspend fun close() {
            isClosed = true
            onClose()
        }
    }
}
