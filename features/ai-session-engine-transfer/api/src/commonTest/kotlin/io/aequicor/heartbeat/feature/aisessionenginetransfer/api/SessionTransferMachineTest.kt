package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.core.statemachine.toMermaid
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTransferMachineTest {
    private val spec = SessionTransferMachineSpec
    private val preparing = SessionTransferState.Preparing(TestRequest)
    private val seeding = SessionTransferState.Seeding(TestRequest, TestConversation.id)

    @Test
    fun `start prepares a transfer to another engine`() {
        assertEquals(SessionTransferState.Idle(), spec.initial)
        spec.assertTransition(
            SessionTransferState.Idle(),
            SessionTransferIntent.Public.Start(TestRequest),
            preparing,
            effects = listOf(SessionTransferEffect.Prepare(TestRequest)),
        )
        val previous = TransferResult.Cancelled(TransferId("previous"))
        spec.assertTransition(
            SessionTransferState.Idle(previous),
            SessionTransferIntent.Public.Start(TestRequest),
            preparing,
            effects = listOf(SessionTransferEffect.Prepare(TestRequest)),
        )
    }

    @Test
    fun `same engine transfers are left to model switching`() {
        val sameEngine = TestRequest.copy(target = TestTarget.copy(engine = TestSource.engine))
        spec.assertIgnored(SessionTransferState.Idle(), SessionTransferIntent.Public.Start(sameEngine))
    }

    @Test
    fun `only one transfer runs at a time`() {
        val other = SessionTransferIntent.Public.Start(TestRequest.copy(transfer = TransferId("other")))
        spec.assertIgnored(preparing, other)
        spec.assertIgnored(seeding, other)
    }

    @Test
    fun `prepared handoff seeds the target session`() {
        spec.assertTransition(
            preparing,
            SessionTransferIntent.Internal.Prepared(TestRequest.transfer, TestConversation, TestHandoffPrompt),
            seeding,
            effects = listOf(SessionTransferEffect.Seed(TestRequest, TestConversation, TestHandoffPrompt)),
        )
    }

    @Test
    fun `preparation results must match the request`() {
        spec.assertIgnored(
            preparing,
            SessionTransferIntent.Internal.Prepared(TransferId("stale"), TestConversation, TestHandoffPrompt),
        )
        val foreignTail = TestConversation + TestSegment
        spec.assertIgnored(
            preparing,
            SessionTransferIntent.Internal.Prepared(TestRequest.transfer, foreignTail, TestHandoffPrompt),
        )
        val existing = SessionTransferState.Preparing(TestRequest.copy(conversation = ConversationId("existing")))
        spec.assertIgnored(
            existing,
            SessionTransferIntent.Internal.Prepared(TestRequest.transfer, TestConversation, TestHandoffPrompt),
        )
    }

    @Test
    fun `seeded segment completes the transfer once`() {
        val completed = TransferResult.Completed(TestRequest.transfer, TestConversation.id, TestSegment)
        spec.assertTransition(
            seeding,
            SessionTransferIntent.Internal.Seeded(TestRequest.transfer, TestConversation.id, TestSegment),
            SessionTransferState.Idle(completed),
            outputs = listOf(SessionTransferOutput.Finished(completed)),
        )
        spec.assertIgnored(
            seeding,
            SessionTransferIntent.Internal.Seeded(TestRequest.transfer, ConversationId("other"), TestSegment),
        )
        spec.assertIgnored(
            SessionTransferState.Idle(completed),
            SessionTransferIntent.Internal.Seeded(TestRequest.transfer, TestConversation.id, TestSegment),
        )
    }

    @Test
    fun `cancellation is possible only before seeding`() {
        val cancelled = TransferResult.Cancelled(TestRequest.transfer)
        spec.assertTransition(
            preparing,
            SessionTransferIntent.Public.Cancel(TestRequest.transfer),
            SessionTransferState.Idle(cancelled),
            outputs = listOf(SessionTransferOutput.Finished(cancelled)),
        )
        spec.assertIgnored(preparing, SessionTransferIntent.Public.Cancel(TransferId("other")))
        spec.assertIgnored(seeding, SessionTransferIntent.Public.Cancel(TestRequest.transfer))
        spec.assertIgnored(SessionTransferState.Idle(), SessionTransferIntent.Public.Cancel(TestRequest.transfer))
    }

    @Test
    fun `results of another phase are ignored`() {
        val prepared = SessionTransferIntent.Internal.Prepared(
            TestRequest.transfer,
            TestConversation,
            TestHandoffPrompt,
        )
        val seeded = SessionTransferIntent.Internal.Seeded(TestRequest.transfer, TestConversation.id, TestSegment)
        val failed = SessionTransferIntent.Internal.Failed(TestRequest.transfer, TransferFailure.Unknown)
        spec.assertIgnored(preparing, seeded)
        spec.assertIgnored(seeding, prepared)
        listOf(prepared, seeded, failed).forEach { spec.assertIgnored(SessionTransferState.Idle(), it) }
    }

    @Test
    fun `failures finish the matching transfer without a segment`() {
        val failure = TransferFailure.NotLatestSegment
        val failed = TransferResult.Failed(TestRequest.transfer, failure)
        listOf(preparing, seeding).forEach { state ->
            spec.assertTransition(
                state,
                SessionTransferIntent.Internal.Failed(TestRequest.transfer, failure),
                SessionTransferState.Idle(failed),
                outputs = listOf(SessionTransferOutput.Finished(failed)),
            )
            spec.assertIgnored(state, SessionTransferIntent.Internal.Failed(TransferId("stale"), failure))
        }
    }

    @Test
    fun `effect errors keep domain failures and hide unknown causes`() {
        val engineFailure = EngineFailure.Engine(EngineFailureReason.Unavailable)
        val prepare = SessionTransferEffect.Prepare(TestRequest)
        assertEquals(
            SessionTransferIntent.Internal.Failed(TestRequest.transfer, TransferFailure.Engine(engineFailure)),
            spec.onEffectFailure(prepare, EngineException(engineFailure)),
        )
        val seed = SessionTransferEffect.Seed(TestRequest, TestConversation, TestHandoffPrompt)
        assertEquals(
            SessionTransferIntent.Internal.Failed(TestRequest.transfer, TransferFailure.Unknown),
            spec.onEffectFailure(seed, IllegalStateException("native detail")),
        )
        assertEquals(
            SessionTransferIntent.Internal.Failed(TestRequest.transfer, TransferFailure.Unknown),
            spec.onEffectFailure(seed, CancellationException("timeout inside the effect")),
        )
    }

    @Test
    fun `diagram lists every state`() {
        val diagram = spec.toMermaid()
        listOf("Idle", "Preparing", "Seeding").forEach { assertTrue(it in diagram, it) }
    }
}
