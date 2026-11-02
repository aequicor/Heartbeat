package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioHelperRecoveryObservationTest {
    @Test
    fun `adoption never creates missing evidence or accepts the recovery execution`() = runTest {
        val f = NativeHelperFixture()
        val execution = recovery(f)
        val helper = HelperId(f.record.id)
        val saved = f.attempts.prepareDelivery(
            helper,
            HelperPrompt(execution.request, execution.prompt),
            execution.origin.deliveryPolicy(),
        ).receipt
        val observer = observer(f)
        val native = StudioNativeTurn(checkNotNull(f.turn.request), f.turn.id)
        observer.adoptedTurn(execution, f, native)
        observer.terminalAdoptedTurn(execution, f, native, TurnOutcome.Completed, isHistoryCurrent = true)
        assertNull(f.attempts.receipt(helper, native.request))
        assertEquals(saved, f.attempts.receipt(helper, execution.request))
        assertEquals(0, f.historyReads)
    }

    @Test
    fun `adopted terminal updates only the exact existing native receipt`() = runTest {
        val f = NativeHelperFixture()
        f.prepare()
        f.stop = OwnedTurnStop.Confirmed(f.turn)
        val execution = recovery(f)
        val helper = HelperId(f.record.id)
        val native = StudioNativeTurn(checkNotNull(f.turn.request), f.turn.id)
        val observer = observer(f)
        val saved = f.attempts.receipt(helper, native.request)
        assertFailsWith<IllegalStateException> {
            observer.terminalAdoptedTurn(
                execution,
                f,
                native.copy(turn = TurnId("unrelated")),
                TurnOutcome.Completed,
                isHistoryCurrent = true,
            )
        }
        assertEquals(saved, f.attempts.receipt(helper, native.request))
        assertEquals(0, f.historyReads)
        observer.adoptedTurn(execution, f, native)
        observer.terminalAdoptedTurn(execution, f, native, TurnOutcome.Completed, isHistoryCurrent = true)
        val terminal = f.attempts.receipt(helper, native.request)
        assertEquals(StudioHelperPhase.Terminal, terminal?.phase)
        assertEquals(native.turn, terminal?.turn)
        assertNull(f.attempts.receipt(helper, execution.request))
    }

    @Test
    fun `adopted marked history enriches a terminal witness without losing its outcome`() = runTest {
        val f = NativeHelperFixture()
        f.prepare()
        f.stop = OwnedTurnStop.Confirmed(f.turn)
        val helper = HelperId(f.record.id)
        val request = checkNotNull(f.turn.request)
        f.attempts.terminal(
            helper,
            request,
            StudioHelperTerminal(f.ref, f.turn.id, StudioHelperTerminalOutcome.Completed, ""),
        )
        assertFalse(
            checkNotNull(f.attempts.receipt(helper, request)?.terminal).conversationResult(request).isAnswerComplete,
        )
        f.messages = listOf(f.message("own", "Recovered answer", f.turn.id))
        observer(f).terminalAdoptedTurn(
            recovery(f),
            f,
            StudioNativeTurn(request, f.turn.id),
            TurnOutcome.Completed,
            isHistoryCurrent = true,
        )
        val terminal = checkNotNull(f.attempts.receipt(helper, request)?.terminal)
        assertEquals("Recovered answer", terminal.answer)
        assertTrue(terminal.conversationResult(request).isAnswerComplete)
        assertEquals(StudioHelperTerminalOutcome.Completed, terminal.outcome)
    }

    @Test
    fun `terminal only evidence has no complete output`() {
        val f = NativeHelperFixture()
        val terminal = StudioHelperTerminal(f.ref, null, StudioHelperTerminalOutcome.Completed, "")
        assertFalse(terminal.conversationResult(checkNotNull(f.turn.request)).isAnswerComplete)
    }

    private fun recovery(f: NativeHelperFixture): StudioTurnRequest = runRequest("recovery").copy(
        id = f.record.id,
        origin = StudioTurnOrigin.GraphWork(checkNotNull(f.turn.request)),
        recoveryRequests = listOf(checkNotNull(f.turn.request)),
    )

    private fun observer(f: NativeHelperFixture): StudioHelperTurnObserver {
        val access = object : StudioHelperAccess {
            override suspend fun helperRecord(helper: HelperId): StudioChatRecord = f.record
            override suspend fun openHelper(record: StudioChatRecord): ActiveSession = f
            override suspend fun liveHelper(helper: HelperId): ActiveSession = f
        }
        return StudioHelperTurnObserver(lazyOf(access), f.attempts, f.native)
    }
}
