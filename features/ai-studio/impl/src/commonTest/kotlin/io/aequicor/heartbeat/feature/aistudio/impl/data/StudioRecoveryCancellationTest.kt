package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioRecoveryCancellationTest {
    @Test
    fun `cold stop of original request resolves its observer without a send receipt`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        f.native.attempts.loseRecovery(f.helper, f.execution)
        f.native.stop = OwnedTurnStop.Confirmed(f.native.turn.copy(outcome = TurnOutcome.Cancelled))

        val result = assertIs<HostConversationCancellation.Terminal>(f.control.cancel(f.native.record, f.source))

        assertEquals(f.source, result.result.request)
        assertEquals(TurnOutcome.Cancelled, result.result.outcome)
        assertEquals(f.source, f.native.stopped?.get(1))
        assertEquals(StudioRecoveryPhase.Terminal, f.native.attempts.recovery(f.helper, f.execution)?.phase)
        assertNull(f.native.attempts.receipt(f.helper, f.source))
    }

    @Test
    fun `unbound candidate stop is uncertain and cannot manufacture a tombstone`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare(isBound = false)

        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(f.native.record, f.source))
        assertFailsWith<EngineException> { f.native.attempts.requestStop(f.helper, f.source) }
        assertFailsWith<EngineException> { f.native.attempts.cancelBeforeSubmission(f.helper, f.source) }

        assertNull(f.native.stopped)
        assertNull(f.native.attempts.receipt(f.helper, f.source))
        assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
    }

    @Test
    fun `original request stop after a context change remains uncertain`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        val changed = f.native.record.copy(ref = f.native.ref.copy(nativeId = "replacement"))

        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(changed, f.source))

        assertNull(f.native.stopped)
        assertNull(f.native.attempts.receipt(f.helper, f.source))
        assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
    }

    @Test
    fun `original request terminal only witness preserves request identity without inventing a turn`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare(isBound = false)
        f.native.attempts.observeRecovery(
            f.helper,
            f.execution,
            f.native.ref,
            f.source,
            null,
            StudioHelperTerminalOutcome.Completed,
        )

        val result = assertIs<HostConversationCancellation.Terminal>(f.control.cancel(f.native.record, f.source))

        assertEquals(f.source, result.result.request)
        assertEquals(TurnOutcome.Completed, result.result.outcome)
        assertFalse(result.result.isAnswerComplete)
        assertNull(f.native.attempts.recovery(f.helper, f.execution)?.turn)
        assertNull(f.native.stopped)
    }

    @Test
    fun `multiple observations of the same native request do not select an arbitrary stop target`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        f.native.attempts.observeRecovery(
            f.helper,
            f.execution,
            f.native.ref,
            f.source,
            f.native.turn.id,
            StudioHelperTerminalOutcome.Cancelled,
        )
        val next = RequestId("next-observer")
        f.native.attempts.prepareRecovery(
            f.helper,
            StudioRecoveryWitness(next, f.source, setOf(f.source), f.native.ref),
        )
        f.native.attempts.observeRecovery(f.helper, next, f.native.ref, f.source, f.native.turn.id)

        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(f.native.record, f.source))

        assertNull(f.native.stopped)
        assertNull(f.native.attempts.receipt(f.helper, f.source))
        for (execution in listOf(f.execution, next)) {
            assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, execution)).isStopRequested)
        }
    }

    @Test
    fun `unrelated request does not inherit the recovery witness`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()

        assertNull(f.control.cancel(f.native.record, RequestId("independent")))

        assertFalse(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
        assertNull(f.native.stopped)
    }

    @Test
    fun `a new observer cannot reinterpret a send as a different work root`() = runTest {
        val f = RecoveryStopFixture()
        val older = RequestId("older-native")
        f.native.attempts.prepareRecovery(
            f.helper,
            StudioRecoveryWitness(f.source, older, setOf(older), f.native.ref),
        )
        f.native.attempts.submitRecovery(f.helper, f.source)
        f.native.attempts.prepareConversation(f.helper, HelperPrompt(f.source, "original send"))

        assertFailsWith<IllegalStateException> { f.prepare() }
        assertNull(f.native.attempts.recovery(f.helper, f.execution))
        assertNull(f.native.stopped)
    }

    @Test
    fun `legacy work without a live handle or send receipt stops by exact witness`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        f.native.stop = OwnedTurnStop.Confirmed(
            f.native.turn.copy(id = TurnId("adapter"), outcome = TurnOutcome.Cancelled),
        )
        val result = assertIs<HostConversationCancellation.Terminal>(f.control.cancel(f.native.record, f.execution))
        assertEquals(TurnOutcome.Cancelled, result.result.outcome)
        assertEquals(f.execution, result.result.request)
        assertFalse(result.result.isAnswerComplete)
        assertEquals(f.source, f.native.stopped?.get(1))
        assertEquals(listOf<Pair<TurnId, RequestId?>>(f.native.turn.id to f.source), f.native.finished)
        assertEquals(0, f.native.historyReads)
        assertNull(f.native.attempts.receipt(f.helper, f.source))
        assertNull(f.native.attempts.receipt(f.helper, f.execution))
        val witness = checkNotNull(f.native.attempts.recovery(f.helper, f.execution))
        assertEquals(StudioRecoveryPhase.Terminal, witness.phase)
        assertTrue(witness.isStopRequested)
    }

    @Test
    fun `stop before binding records intent but cannot claim native cancellation`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare(isBound = false)
        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(f.native.record, f.execution))
        assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
        assertNull(f.native.stopped)
        assertNull(f.native.attempts.receipt(f.helper, f.execution))
    }

    @Test
    fun `unsupported fallback never stops an unrelated later native request`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        f.live = f.native
        f.native.supportsStop = false
        val unrelated = f.native.turn.copy(request = RequestId("later"), id = TurnId("later"), outcome = null)
        f.native.state.value = ActiveSessionState.Running(unrelated)
        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(f.native.record, f.execution))
        assertEquals(ActiveSessionState.Running(unrelated), f.native.state.value)
        assertTrue(f.native.finished.isEmpty())
        assertNull(f.native.stopped)
    }

    @Test
    fun `unknown native stop outcome retains unresolved witness and never creates receipts`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        f.native.stop = OwnedTurnStop.Confirmed(f.native.turn.copy(outcome = TurnOutcome.Unknown))
        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(f.native.record, f.execution))
        assertEquals(StudioRecoveryPhase.Unknown, f.native.attempts.recovery(f.helper, f.execution)?.phase)
        assertFalse(f.native.attempts.isContextIdle(f.helper))
        assertNull(f.native.attempts.receipt(f.helper, f.source))
        assertNull(f.native.attempts.receipt(f.helper, f.execution))
    }

    @Test
    fun `terminal only witness reports no invented turn or complete answer`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare(isBound = false)
        f.native.attempts.observeRecovery(
            f.helper,
            f.execution,
            f.native.ref,
            f.source,
            null,
            StudioHelperTerminalOutcome.Completed,
        )
        val result = assertIs<HostConversationCancellation.Terminal>(f.control.cancel(f.native.record, f.execution))
        assertEquals(TurnOutcome.Completed, result.result.outcome)
        assertFalse(result.result.isAnswerComplete)
        assertNull(f.native.stopped)
        assertNull(f.native.attempts.recovery(f.helper, f.execution)?.turn)
    }

    @Test
    fun `a changed native context is never selected for the captured observer`() = runTest {
        val f = RecoveryStopFixture()
        f.prepare()
        val changed = f.native.record.copy(ref = f.native.ref.copy(nativeId = "replacement"))
        assertIs<HostConversationCancellation.Unconfirmed>(f.control.cancel(changed, f.execution))
        assertNull(f.native.stopped)
        assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
    }

    @Test
    fun `fresh submit cancellation revokes only an existing Q preparation`() = runTest {
        for (hasReceipt in listOf(false, true)) {
            val f = RecoveryStopFixture()
            f.prepare(isBound = false)
            f.native.attempts.submitRecovery(f.helper, f.execution)
            if (hasReceipt) f.native.attempts.prepareConversation(f.helper, HelperPrompt(f.execution, "new prompt"))
            val result = f.control.cancel(f.native.record, f.execution)
            if (hasReceipt) {
                assertIs<HostConversationCancellation.NotSubmitted>(result)
            } else {
                assertIs<HostConversationCancellation.Unconfirmed>(result)
            }
            assertNull(f.native.stopped)
            assertTrue(checkNotNull(f.native.attempts.recovery(f.helper, f.execution)).isStopRequested)
        }
    }
}

private class RecoveryStopFixture {
    val native = NativeHelperFixture()
    val helper = HelperId(native.record.id)
    val source = checkNotNull(native.turn.request)
    val execution = RequestId("recovery-observer")
    var live: ActiveSession? = null
    private val access = object : StudioHelperAccess {
        override suspend fun helperRecord(helper: HelperId) = native.record
        override suspend fun openHelper(record: StudioChatRecord): ActiveSession = error(
            "Stop must not open a later turn",
        )
        override suspend fun liveHelper(helper: HelperId): ActiveSession? = live
    }
    val control = StudioRecoveryCancellation(native.attempts, lazyOf(access), native.native)

    suspend fun prepare(isBound: Boolean = true) {
        native.attempts.prepareRecovery(helper, StudioRecoveryWitness(execution, source, setOf(source), native.ref))
        if (isBound) native.attempts.observeRecovery(helper, execution, native.ref, source, native.turn.id)
    }
}
