package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StudioHelperRecoveryTest {
    @Test
    fun `failed native recovery backs off without settling the receipt then recovers`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.prepare()
        val clock = RecoveryClock()
        val recovery = StudioHelperRecovery(clock)
        var opens = 0
        val open: suspend () -> ActiveSession = {
            opens++
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        assertNull(
            fixture.native.result(fixture.record, fixture.turn.request!!) {
                recovery.open(fixture.record, open)
            },
        )
        repeat(20) { assertNull(recovery.open(fixture.record, open)) }
        assertEquals(1, opens)
        assertEquals(
            StudioHelperPhase.Accepted,
            fixture.attempts.receipt(HelperId(fixture.record.id), fixture.turn.request!!)?.phase,
        )
        clock.time += 1.minutes
        fixture.stop = OwnedTurnStop.Confirmed(fixture.turn)
        val terminal = fixture.native.result(fixture.record, fixture.turn.request!!) {
            recovery.open(fixture.record) { fixture }
        }
        assertEquals(fixture.turn.id, terminal?.turn)
        assertSame(fixture, recovery.open(fixture.record) { fixture })
    }

    @Test
    fun `simultaneous recovery polls share the opening boundary while another helper may open`() = runTest {
        val fixture = NativeHelperFixture()
        val recovery = StudioHelperRecovery(RecoveryClock())
        val opened = CompletableDeferred<ActiveSession>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.open(fixture.record) { opened.await() }
        }
        assertNull(recovery.open(fixture.record) { error("Duplicate open") })
        assertSame(fixture, recovery.open(fixture.record.copy(id = "other")) { fixture })
        first.cancel()
        first.join()
        assertSame(fixture, recovery.open(fixture.record) { fixture })
    }

    @Test
    fun `changed native session retries immediately after failed recovery`() = runTest {
        val fixture = NativeHelperFixture()
        val recovery = StudioHelperRecovery(RecoveryClock())
        assertFailsWith<EngineException> {
            recovery.open(fixture.record) {
                throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
            }
        }
        val changed = fixture.record.copy(ref = fixture.ref.copy(nativeId = "replacement"))
        assertSame(fixture, recovery.open(changed) { fixture })
    }
}

private class RecoveryClock : Clock {
    var time = Instant.fromEpochMilliseconds(0)
    override fun now(): Instant = time
}
