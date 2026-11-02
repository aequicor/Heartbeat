package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioRecoveryNoSendTest {
    private val helper = HelperId("chat")
    private val execution = RequestId("Q")
    private val source = RequestId("R")
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val delivery = StudioRecoveryDelivery(helper.value, execution, "delivery-one", "payload")
    private val initial = StudioRecoveryWitness(execution, source, setOf(source), session, delivery = delivery)

    @Test
    fun `deferred idle delivery closes without native receipts and retries only after fresh inspection`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val gate = StudioRunSubmission(admission, delivery)
        attempts.prepareRecovery(helper, initial)
        gate.beginRecoveryObservation()
        attempts.confirmRecoveryIdle(helper, execution, session)
        admission.value = ScheduledWakeAdmission.Defer
        assertFailsWith<CancellationException> { gate.prepareRecoverySubmit() }
        val proof = assertNotNull(gate.sealRecoveryNoSend())

        val closed = attempts.closeRecoveryNoSend(helper, proof)

        assertEquals(StudioRecoveryPhase.NoSend, closed.phase)
        assertEquals(StudioRecoveryRefusal.Deferred, closed.refusal)
        assertTrue(attempts.isContextIdle(helper))
        assertNull(attempts.receipt(helper, execution))
        assertNull(attempts.receipt(helper, source))
        assertEquals(closed, StudioHelperAttempts(stores).closeRecoveryNoSend(helper, proof))
        assertFailsWith<CancellationException> {
            attempts.prepareConversation(
                helper,
                HelperPrompt(execution, "replay"),
            )
        }
        val next = delivery.copy(claim = "delivery-two")
        val restored = StudioHelperAttempts(stores)
        val rearmed = restored.prepareRecovery(helper, initial.copy(delivery = next))
        assertFalse(rearmed.isInspectionCurrent)
        assertFalse(rearmed.isIdleConfirmed)
        assertFailsWith<CancellationException> {
            restored.changeRecoveryDelivery(delivery) { it.idle(session) }
        }
        assertFailsWith<CancellationException> {
            restored.changeRecoveryDelivery(delivery) { it.unknown() }
        }
        assertEquals(rearmed, restored.recovery(helper, execution))
        assertFailsWith<IllegalStateException> { restored.submitRecovery(helper, execution) }
        assertFailsWith<IllegalStateException> { restored.closeRecoveryNoSend(helper, proof) }
        restored.confirmRecoveryIdle(helper, execution, session)
        restored.submitRecovery(helper, execution)
        assertTrue(restored.prepareConversation(helper, HelperPrompt(execution, "continuation")).isNew)
    }

    @Test
    fun `restart renews observation claim while retaining native binding and stop`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val old = delivery.copy(fingerprint = "immutable-payload")
        val witness = initial.copy(delivery = old)
        attempts.prepareRecovery(helper, witness)
        attempts.observeRecovery(helper, execution, session, source, TurnId("actual"))
        attempts.loseRecovery(helper, execution)
        attempts.stopRecovery(helper, execution)
        val next = old.copy(claim = "new-process")

        val resumed = attempts.prepareRecovery(
            helper,
            witness.copy(delivery = next, candidates = witness.candidates + execution),
        )

        assertEquals(setOf(source, execution), resumed.candidates)
        assertEquals(source, resumed.observed)
        assertEquals(TurnId("actual"), resumed.turn)
        assertTrue(resumed.isStopRequested)
        assertFalse(resumed.isInspectionCurrent)
        assertFailsWith<CancellationException> { attempts.changeRecoveryDelivery(old) { it.unknown() } }
        assertFailsWith<IllegalStateException> {
            attempts.prepareRecovery(
                helper,
                witness.copy(delivery = next.copy(fingerprint = "different-work"), candidates = resumed.candidates),
            )
        }
        assertEquals(resumed, attempts.recovery(helper, execution))
    }

    @Test
    fun `post admission refusal requires both the exact gate and retired native preparation`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.confirmRecoveryIdle(helper, execution, session)
        val gate = StudioRunSubmission(recoveryDelivery = delivery)
        gate.beginRecoveryObservation()
        gate.prepareRecoverySubmit()
        attempts.submitRecovery(helper, execution)
        attempts.prepareConversation(helper, HelperPrompt(execution, "continuation"))
        val proof = assertNotNull(gate.sealRecoveryNoSend())

        assertFailsWith<IllegalStateException> { attempts.closeRecoveryNoSend(helper, proof) }
        assertEquals(StudioRecoveryPhase.Submit, attempts.recovery(helper, execution)?.phase)
        attempts.cancelBeforeSubmission(helper, execution, isFinal = false)
        val closed = attempts.closeRecoveryNoSend(helper, proof)

        assertEquals(StudioRecoveryPhase.NoSend, closed.phase)
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(helper, execution)?.phase)
        assertTrue(attempts.isContextIdle(helper))
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.requestStop(helper, execution).phase)
        assertTrue(checkNotNull(attempts.recovery(helper, execution)).isStopRequested)
    }

    @Test
    fun `begun recovery needs exact durable sender rejection before closing NoSend`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.confirmRecoveryIdle(helper, execution, session)
        attempts.submitRecovery(helper, execution)
        attempts.prepareConversation(helper, HelperPrompt(execution, "continuation"))
        attempts.bindSession(helper, execution, session)
        val gate = StudioRunSubmission(recoveryDelivery = delivery)
        gate.begin()
        assertTrue(attempts.begin(helper, execution, "sender"))
        assertTrue(attempts.enterNative(helper, execution, "sender"))
        assertNull(gate.sealRecoveryNoSend())
        assertNull(attempts.retiredRecoverySender(delivery, "sender"))

        assertTrue(attempts.cancelBeforeNative(helper, execution, session, "sender"))
        assertNull(attempts.retiredRecoverySender(delivery, "different-sender"))
        val proof = assertNotNull(attempts.retiredRecoverySender(delivery, "sender"))
        val closed = attempts.closeRecoveryNoSend(helper, proof)

        assertEquals(StudioRecoveryPhase.NoSend, closed.phase)
        assertEquals("sender", attempts.receipt(helper, execution)?.nativeEntryClaim)
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(helper, execution)?.phase)
    }

    @Test
    fun `real sender only provides NoSend after definitive native rejection`() = runTest {
        for (isRejected in listOf(false, true)) {
            val attempts = StudioHelperAttempts(ChecklistTestStores())
            attempts.prepareRecovery(helper, initial)
            attempts.confirmRecoveryIdle(helper, execution, session)
            attempts.submitRecovery(helper, execution)
            attempts.prepareConversation(helper, HelperPrompt(execution, "continuation"))
            val gate = StudioRunSubmission(recoveryDelivery = delivery)
            val sender = StudioHelperSubmission(attempts, emptyHelperAdmissions(), helper, execution, gate)
            sender.begin()
            val failure = if (isRejected) EngineFailure.Session(SessionFailureReason.Busy) else EngineFailure.Unknown()

            assertFailsWith<EngineException> { sender.send { throw EngineException(failure) } }

            assertNull(gate.sealRecoveryNoSend())
            val proof = sender.sealRecoveryNoSend()
            if (isRejected) {
                assertEquals(
                    StudioRecoveryPhase.NoSend,
                    attempts.closeRecoveryNoSend(helper, assertNotNull(proof)).phase,
                )
            } else {
                assertNull(proof)
                assertEquals(StudioHelperPhase.Submitting, attempts.receipt(helper, execution)?.phase)
            }
        }
    }

    @Test
    fun `exact sealed gate closes Submit before any new receipt was admitted`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.confirmRecoveryIdle(helper, execution, session)
        val gate = StudioRunSubmission(recoveryDelivery = delivery)
        gate.beginRecoveryObservation()
        gate.prepareRecoverySubmit()
        attempts.submitRecovery(helper, execution)

        val closed = attempts.closeRecoveryNoSend(helper, assertNotNull(gate.sealRecoveryNoSend()))

        assertEquals(StudioRecoveryPhase.NoSend, closed.phase)
        assertNull(attempts.receipt(helper, execution))
        assertTrue(attempts.isContextIdle(helper))
    }

    @Test
    fun `cancelled native source equal to execution is never rewritten as NotSubmitted`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val same = delivery.copy(execution = source)
        attempts.prepareRecovery(helper, initial.copy(execution = source, delivery = same))
        val gate = StudioRunSubmission(recoveryDelivery = same)
        gate.beginRecoveryObservation()
        attempts.observeRecovery(
            helper,
            source,
            session,
            source,
            TurnId("actual"),
            StudioHelperTerminalOutcome.Cancelled,
        )
        gate.cancel()
        assertFailsWith<CancellationException> { gate.prepareRecoverySubmit() }

        val closed = attempts.closeRecoveryNoSend(helper, assertNotNull(gate.sealRecoveryNoSend()))

        assertEquals(StudioRecoveryPhase.NoSend, closed.phase)
        assertEquals(source, closed.observed)
        assertEquals(StudioHelperTerminalOutcome.Cancelled, closed.outcome)
        assertNull(attempts.receipt(helper, source))
        assertFailsWith<EngineException> { attempts.requestStop(helper, source) }
        assertNull(attempts.receipt(helper, source))
    }

    @Test
    fun `deferred delivery equal to native source cannot replay even without a native receipt`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val same = delivery.copy(execution = source)
        val witness = initial.copy(execution = source, delivery = same)
        attempts.prepareRecovery(helper, witness)
        attempts.observeRecovery(helper, source, session, source, null, StudioHelperTerminalOutcome.Cancelled)
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val gate = StudioRunSubmission(admission, same)
        gate.beginRecoveryObservation()
        admission.value = ScheduledWakeAdmission.Defer
        assertFailsWith<CancellationException> { gate.prepareRecoverySubmit() }
        attempts.closeRecoveryNoSend(helper, assertNotNull(gate.sealRecoveryNoSend()))
        attempts.prepareRecovery(helper, witness.copy(delivery = same.copy(claim = "retry")))
        attempts.observeRecovery(helper, source, session, source, null, StudioHelperTerminalOutcome.Cancelled)

        assertFailsWith<IllegalStateException> { attempts.submitRecovery(helper, source) }

        assertNull(attempts.receipt(helper, source))
    }

    @Test
    fun `unknown native ownership cannot be discarded by an exact closed gate`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, TurnId("actual"))
        attempts.loseRecovery(helper, execution)
        val gate = StudioRunSubmission(recoveryDelivery = delivery)

        assertFailsWith<IllegalStateException> {
            attempts.closeRecoveryNoSend(helper, assertNotNull(gate.sealRecoveryNoSend()))
        }

        assertEquals(StudioRecoveryPhase.Unknown, attempts.recovery(helper, execution)?.phase)
        assertFalse(attempts.isContextIdle(helper))
        assertNull(attempts.receipt(helper, execution))
        assertFailsWith<IllegalStateException> { attempts.confirmRecoveryIdle(helper, execution, session) }
    }

    @Test
    fun `accepted and retained gates never provide NoSend proof`() = runTest {
        val submitted = StudioRunSubmission(recoveryDelivery = delivery)
        submitted.begin()
        assertNull(submitted.sealRecoveryNoSend())
        val retained = StudioRunSubmission(recoveryDelivery = delivery)
        retained.beginRecoveryObservation()
        retained.retainNative()
        assertNull(retained.sealRecoveryNoSend())
    }

    @Test
    fun `missing inspection or another delivery cannot close recovery`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        val proof = assertNotNull(StudioRunSubmission(recoveryDelivery = delivery).sealRecoveryNoSend())
        assertFailsWith<IllegalStateException> { attempts.closeRecoveryNoSend(helper, proof) }
        attempts.confirmRecoveryIdle(helper, execution, session)
        val other = proof.copy(delivery = delivery.copy(claim = "another"))
        assertFailsWith<IllegalStateException> { attempts.closeRecoveryNoSend(helper, other) }
        assertFalse(attempts.isContextIdle(helper))
        assertNull(attempts.receipt(helper, execution))
    }

    @Test
    fun `drop and a late root stop forbid rearm without blocking an independent request`() = runTest {
        for (drop in listOf(false, true)) {
            val attempts = StudioHelperAttempts(ChecklistTestStores())
            attempts.prepareRecovery(helper, initial)
            attempts.confirmRecoveryIdle(helper, execution, session)
            val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
            val gate = StudioRunSubmission(admission, delivery)
            gate.beginRecoveryObservation()
            admission.value = if (drop) ScheduledWakeAdmission.Drop else ScheduledWakeAdmission.Defer
            assertFailsWith<CancellationException> { gate.prepareRecoverySubmit() }
            attempts.closeRecoveryNoSend(helper, assertNotNull(gate.sealRecoveryNoSend()))
            attempts.stopRecovery(helper, execution)

            assertFailsWith<IllegalStateException> {
                attempts.prepareRecovery(helper, initial.copy(delivery = delivery.copy(claim = "later")))
            }
            assertTrue(
                attempts.prepareConversation(helper, HelperPrompt(RequestId("independent"), "human input")).isNew,
            )
            assertNull(attempts.receipt(helper, execution))
        }
    }
}
