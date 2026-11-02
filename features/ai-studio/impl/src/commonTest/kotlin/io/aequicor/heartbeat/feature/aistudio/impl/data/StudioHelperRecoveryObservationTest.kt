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
import kotlin.test.assertNull

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
        observer.terminalAdoptedTurn(
            execution,
            f,
            native.copy(turn = TurnId("unrelated")),
            TurnOutcome.Completed,
            isHistoryCurrent = true,
        )
        assertEquals(saved, f.attempts.receipt(helper, native.request))
        assertEquals(0, f.historyReads)
        observer.adoptedTurn(execution, f, native)
        observer.terminalAdoptedTurn(execution, f, native, TurnOutcome.Completed, isHistoryCurrent = true)
        val terminal = f.attempts.receipt(helper, native.request)
        assertEquals(StudioHelperPhase.Terminal, terminal?.phase)
        assertEquals(native.turn, terminal?.turn)
        assertNull(f.attempts.receipt(helper, execution.request))
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
