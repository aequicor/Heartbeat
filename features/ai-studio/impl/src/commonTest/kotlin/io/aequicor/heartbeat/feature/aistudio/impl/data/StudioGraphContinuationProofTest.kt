package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioGraphContinuationProofTest {
    private val native = NativeHelperFixture()
    private val root = RequestId("R")
    private val request = runRequest("Q").copy(
        origin = StudioTurnOrigin.GraphWork(root),
        recoveryRequests = listOf(root),
    ).let { it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery())) }
    private val snapshot = StudioExecutionSnapshot(
        request.settings,
        checkNotNull(native.record.target),
        "high",
        null,
        StudioHumanAuthority(),
    )
    private val source = StudioHelperReceipt(
        root,
        StudioHelperPhase.Accepted,
        "original-assignment",
        session = native.ref,
        turn = TurnId("R-turn"),
        policy = StudioAttemptPolicy.GraphWork(root),
        executionSnapshot = snapshot,
        causes = emptySet(),
    )
    private val witness = StudioRecoveryWitness(
        request.request,
        root,
        setOf(root),
        native.ref,
        delivery = request.submission?.recoveryDelivery,
        route = StudioRecoveryRoute(snapshot.target, native.record.resolvedExecutionWorkspace()),
        inspectionTarget = root,
    ).idle(native.ref).submitting()

    @Test
    fun `completed successor never reauthorizes its old sender or permits ancestry rollback`() = runTest {
        val stores = ChecklistTestStores()
        val helper = HelperId(request.id)
        val pending = source.copy(
            phase = StudioHelperPhase.Submitting,
            session = null,
            turn = null,
            preparedSession = native.ref,
            isNativeEntryTracked = true,
            submissionClaim = "R-sender",
        )
        val ancestors = mapOf(root.value to pending)
        val child = prepareGraphContinuationReceipt(ancestors, witness, request).receipt.copy(
            phase = StudioHelperPhase.Terminal,
            session = native.ref,
            turn = TurnId("Q-turn"),
            terminal = StudioHelperTerminal(
                native.ref,
                TurnId("Q-turn"),
                StudioHelperTerminalOutcome.Completed,
                "done",
            ),
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(native.record.copy(id = request.id)))
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(helper),
            Json.encodeToString(ancestors + (child.request.value to child)),
        )
        val attempts = StudioHelperAttempts(stores)
        assertFailsWith<CancellationException> { attempts.enterNative(helper, root, "R-sender") }
        assertFalse(attempts.cancelBeforeNative(helper, root, native.ref, "R-sender"))
        assertEquals(pending, attempts.cancelBeforeSubmission(helper, root, isFinal = false))
        assertEquals(pending, attempts.receipt(helper, root))
        assertEquals(child, attempts.continuation(helper, root)?.head)
        val stopped = attempts.cancelBeforeSubmission(helper, root)
        assertEquals(StudioHelperPhase.Submitting, stopped.phase)
        assertTrue(stopped.isStopRequested)
        assertTrue(checkNotNull(attempts.receipt(helper, child.request)).isStopRequested)
    }

    @Test
    fun `stop of an unsent virtual child retires observation without fabricating a native terminal`() = runTest {
        val stores = ChecklistTestStores()
        val live = NativeHelperFixture(stores)
        val helper = HelperId(request.id)
        val record = live.record.copy(id = request.id, lastRunRequest = RequestId("C"))
        val accepted = virtualChildRecords(live.ref)
        val child = accepted.getValue("C").copy(
            phase = StudioHelperPhase.Submitting,
            session = null,
            turn = null,
            preparedSession = live.ref,
            submissionClaim = "C-sender",
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(helper),
            Json.encodeToString(accepted + ("C" to child)),
        )
        stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(helper, mapOf(request.request.value to witness))
        val successor = request.copy(request = RequestId("D")).let {
            it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
        }
        val attempts = live.attempts
        StudioRecoveryJournal(attempts).reserve(successor, record)
        attempts.stopRecoveryTargets(helper, root)
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(helper, child.request)?.phase)
        assertNull(attempts.receipt(helper, child.request)?.terminal)
        assertNull(attempts.recovery(helper, successor.request)?.outcome)
        assertEquals(request.request, attempts.continuation(helper, root)?.result?.request)
        assertFailsWith<CancellationException> {
            attempts.observeRecovery(helper, successor.request, live.ref, child.request, TurnId("late-C"))
        }
        assertTrue(attempts.isContextIdle(helper))
        assertNull(live.stopped)
    }

    @Test
    fun `cold stop calls the actual virtual child and retains capacity until native terminal proof`() = runTest {
        val stores = ChecklistTestStores()
        val live = NativeHelperFixture(stores, "virtual-context")
        val helper = HelperId(request.id)
        val record = live.record.copy(id = request.id, lastRunRequest = RequestId("C"))
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(helper),
            Json.encodeToString(virtualChildRecords(live.ref)),
        )
        stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(helper, mapOf(request.request.value to witness))
        val successor = request.copy(request = RequestId("D")).let {
            it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
        }
        val attempts = live.attempts
        StudioRecoveryJournal(attempts).reserve(successor, record)
        val access = object : StudioHelperAccess {
            override suspend fun helperRecord(helper: HelperId): StudioChatRecord = record
            override suspend fun openHelper(record: StudioChatRecord) = live
            override suspend fun liveHelper(helper: HelperId) = live
        }
        val cancellation = StudioRecoveryCancellation(attempts, lazyOf(access), live.native)
        assertEquals(
            io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation.Unconfirmed(root),
            cancellation.cancel(record, root),
        )
        assertEquals(live.ref, live.stopped?.get(0))
        assertEquals(RequestId("C"), live.stopped?.get(1))
        assertFalse(attempts.isContextIdle(helper))
        live.stop = io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop.Confirmed(
            live.turn.copy(request = RequestId("C"), id = TurnId("C-turn")),
        )
        val stopped = cancellation.cancel(record, root)
        assertTrue(stopped is io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation.Terminal)
        assertEquals(RequestId("C"), live.stopped?.get(1))
        assertTrue(attempts.isContextIdle(helper))
    }

    @Test
    fun `redelivered observer follows the later recovery sender as its native predecessor`() = runTest {
        val stores = ChecklistTestStores()
        val helper = HelperId(request.id)
        val record = native.record.copy(id = request.id, lastRunRequest = RequestId("C"))
        val original = acceptedChild()
        val q = original.getValue(request.request.value)
        val ancestors = original + (
            q.request.value to q.copy(
                phase = StudioHelperPhase.Terminal,
                terminal = StudioHelperTerminal(native.ref, q.turn, StudioHelperTerminalOutcome.Cancelled, ""),
            )
        )
        val childRequest = request.copy(request = RequestId("C")).let {
            it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
        }
        val childWitness = witness.copy(
            execution = childRequest.request,
            delivery = childRequest.submission?.recoveryDelivery,
            inspectionTarget = q.request,
            predecessorExecution = q.request,
        )
        val child = prepareGraphContinuationReceipt(ancestors, childWitness, childRequest).receipt.copy(
            phase = StudioHelperPhase.Accepted,
            session = native.ref,
            turn = TurnId("C-turn"),
        )
        val observerRequest = request.copy(request = RequestId("D"))
        val old = witness.copy(
            execution = observerRequest.request,
            delivery = observerRequest.newRecoveryDelivery(),
            phase = StudioRecoveryPhase.Inspecting,
            inspectionTarget = q.request,
            predecessorExecution = q.request,
            isNativeSendAuthorized = false,
            submittedSource = null,
        ).observed(q.request, native.ref, q.turn, StudioHelperTerminalOutcome.Cancelled)
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(helper),
            Json.encodeToString(ancestors + (child.request.value to child)),
        )
        stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(
            helper,
            mapOf(q.request.value to witness, "C" to childWitness, "D" to old),
        )
        val attempts = StudioHelperAttempts(stores)
        val replay = observerRequest.copy(
            submission = StudioRunSubmission(recoveryDelivery = observerRequest.newRecoveryDelivery()),
        )
        val resumed = StudioRecoveryJournal(attempts).reserve(replay, record)
        assertEquals(child.request, resumed.predecessorExecution)
        assertEquals(child.request, resumed.inspectionTarget)
        assertNull(resumed.outcome)
        assertEquals(resumed, attempts.recoveryWork(helper, root).observer)
    }

    @Test
    fun `closed observer delivery can inspect a later continuation without retaining its refusal`() {
        val records = virtualChildRecords(native.ref)
        val observerRequest = request.copy(request = RequestId("D"))
        val old = witness.copy(
            delivery = observerRequest.newRecoveryDelivery(),
            execution = RequestId("D"),
            phase = StudioRecoveryPhase.Inspecting,
            inspectionTarget = request.request,
            predecessorExecution = request.request,
            isNativeSendAuthorized = false,
            submittedSource = null,
        ).observed(request.request, native.ref, TurnId("Q-turn"), StudioHelperTerminalOutcome.Cancelled)
        val closed = old.noSend(
            StudioRecoveryNoSendProof(checkNotNull(old.delivery), StudioRecoveryRefusal.Deferred),
            isNewSendRevoked = false,
        )
        val incoming = closed.copy(
            phase = StudioRecoveryPhase.Inspecting,
            observed = null,
            turn = null,
            outcome = null,
            refusal = null,
            inspectionTarget = RequestId("C"),
            delivery = observerRequest.newRecoveryDelivery(),
        )
        val resumed = closed.repeatDelivery(incoming, records)
        assertEquals(StudioRecoveryPhase.Inspecting, resumed.phase)
        assertNull(resumed.refusal)
        assertNull(resumed.observed)
        assertNull(resumed.outcome)
    }

    @Test
    fun `new observer after a virtual continuation never inherits the old native observation`() = runTest {
        for (nextSession in listOf(native.ref, native.ref.copy(nativeId = "virtual-context"))) {
            val stores = ChecklistTestStores()
            val helper = HelperId(request.id)
            val record = native.record.copy(id = request.id, ref = nextSession, lastRunRequest = RequestId("C"))
            val records = virtualChildRecords(nextSession)
            val observer = witness.copy(
                execution = RequestId("D"),
                phase = StudioRecoveryPhase.Inspecting,
                delivery = null,
                inspectionTarget = request.request,
                predecessorExecution = request.request,
                isNativeSendAuthorized = false,
                submittedSource = null,
            ).observed(request.request, native.ref, TurnId("Q-turn"))
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
            stores.keyValue(HelperAttemptsSpec).set(helperReceiptKey(helper), Json.encodeToString(records))
            stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(
                helper,
                mapOf(request.request.value to witness, "D" to observer),
            )
            val attempts = StudioHelperAttempts(stores)
            val successor = request.copy(request = RequestId("E")).let {
                it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
            }
            val resumed = StudioRecoveryJournal(attempts).reserve(successor, record)
            assertEquals(RequestId("C"), resumed.inspectionTarget)
            assertEquals(StudioRecoveryPhase.Inspecting, resumed.phase)
            assertNull(resumed.observed)
            assertNull(resumed.turn)
            assertNull(resumed.outcome)
            assertFailsWith<CancellationException> {
                attempts.observeRecovery(helper, observer.execution, native.ref, request.request, TurnId("Q-turn"))
            }
            assertEquals(RequestId("C"), attempts.recoveryWork(helper, root).nativeRequest)
        }
    }

    @Test
    fun `same sender redelivery follows a virtual context without replaying its old native send`() = runTest {
        val stores = ChecklistTestStores()
        val helper = HelperId(request.id)
        val nextSession = native.ref.copy(nativeId = "virtual-context")
        val record = native.record.copy(id = request.id, ref = nextSession, lastRunRequest = RequestId("C"))
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        stores.keyValue(
            HelperAttemptsSpec,
        ).set(helperReceiptKey(helper), Json.encodeToString(virtualChildRecords(nextSession)))
        stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(helper, mapOf(request.request.value to witness))
        val attempts = StudioHelperAttempts(stores)
        val replay = request.copy(submission = StudioRunSubmission(recoveryDelivery = request.newRecoveryDelivery()))
        val resumed = StudioRecoveryJournal(attempts).reserve(replay, record)
        assertEquals(RequestId("C"), resumed.inspectionTarget)
        assertEquals(nextSession, resumed.session)
        assertNull(resumed.observed)
        assertFailsWith<IllegalStateException> { resumed.submitting() }
        assertEquals(native.ref, attempts.receipt(helper, request.request)?.session)
        assertEquals(RequestId("C"), attempts.recoveryWork(helper, root).nativeRequest)
    }

    @Test
    fun `virtual native context handoff survives restart and every stop alias fences its actual child`() = runTest {
        val nextSession = native.ref.copy(nativeId = "virtual-context")
        val childId = RequestId("C")
        val records = virtualChildRecords(nextSession)
        for (alias in listOf(root, request.request, childId, RequestId("D"))) {
            val stores = ChecklistTestStores()
            val helper = HelperId(request.id)
            val record = native.record.copy(id = request.id, ref = nextSession, lastRunRequest = childId)
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
            stores.keyValue(HelperAttemptsSpec).set(helperReceiptKey(helper), Json.encodeToString(records))
            stores.keyValue(HelperAttemptsSpec).writeRecoveryWitnesses(helper, mapOf(request.request.value to witness))
            val attempts = StudioHelperAttempts(stores)
            val successor = request.copy(request = RequestId("D")).let {
                it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
            }
            val resumed = StudioRecoveryJournal(attempts).reserve(successor, record)
            assertEquals(childId, resumed.inspectionTarget)
            assertEquals(nextSession, resumed.session)
            assertEquals(childId, attempts.recoveryWork(helper, root).nativeRequest)
            attempts.stopRecoveryTargets(helper, alias)
            assertTrue(checkNotNull(attempts.receipt(helper, childId)).isStopRequested)
            assertEquals(StudioHelperPhase.Accepted, attempts.receipt(helper, childId)?.phase)
            assertFalse(attempts.isContextIdle(helper))
        }
    }

    @Test
    fun `durable recovery resumes the submitted child and stops it through the historical root`() = runTest {
        val stores = ChecklistTestStores()
        val helper = HelperId(request.id)
        val record = native.record.copy(id = request.id, lastRunRequest = root)
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(helper),
            Json.encodeToString(mapOf(root.value to source)),
        )
        val attempts = StudioHelperAttempts(stores)
        val journal = StudioRecoveryJournal(attempts)
        val delivery = checkNotNull(request.submission?.recoveryDelivery)
        journal.reserve(request, record)
        journal.idle(delivery, native.ref)
        journal.submitting(delivery)
        val prepared = attempts.prepareGraphContinuation(helper, request)
        assertTrue(prepared.isNew)
        attempts.bindSession(helper, request.request, native.ref)
        assertTrue(attempts.begin(helper, request.request, "Q-sender"))
        assertTrue(attempts.enterNative(helper, request.request, "Q-sender"))
        attempts.accepted(helper, request.request, native.ref, TurnId("Q-turn"))

        val restarted = StudioHelperAttempts(stores)
        val successor = request.copy(request = RequestId("D")).let {
            it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery()))
        }
        val resumed = StudioRecoveryJournal(restarted).reserve(successor, record)
        assertEquals(request.request, resumed.inspectionTarget)
        assertEquals(request.request, resumed.predecessorExecution)
        assertNull(resumed.outcome)
        assertNull(restarted.receipt(helper, successor.request))
        assertEquals(request.request, restarted.recoveryWork(helper, root).nativeRequest)
        restarted.stopRecoveryTargets(helper, root)
        assertTrue(checkNotNull(restarted.receipt(helper, request.request)).isStopRequested)
        assertTrue(checkNotNull(restarted.recovery(helper, successor.request)).isStopRequested)
        assertFalse(restarted.isContextIdle(helper))
    }

    @Test
    fun `terminal only cancellation can continue an entered source without inventing its turn`() {
        val entered = source.copy(
            phase = StudioHelperPhase.Submitting,
            session = null,
            turn = null,
            preparedSession = native.ref,
            isNativeEntryTracked = true,
            submissionClaim = "R-sender",
            nativeEntryClaim = "R-sender",
        )
        val cancelled = witness.copy(
            observed = root,
            outcome = StudioHelperTerminalOutcome.Cancelled,
            isIdleConfirmed = false,
        )
        val records = mapOf(root.value to entered)
        val prepared = prepareGraphContinuationReceipt(records, cancelled, request)
        val changed = records + (request.request.value to prepared.receipt)

        assertTrue(prepared.isNew)
        assertNull(changed.getValue(root.value).turn)
        assertNull(changed.getValue(root.value).terminal)
        assertEquals(listOf(request.request), changed.unresolvedHeads().map { it.request })
    }

    @Test
    fun `exact deferred rollback rearms the same child only after fresh source inspection`() {
        val records = mapOf(root.value to source)
        val first = prepareGraphContinuationReceipt(records, witness, request).receipt
        val unsent = first.copy(phase = StudioHelperPhase.NotSubmitted)
        val stored = records + (first.request.value to unsent)
        val closed = witness.noSend(
            StudioRecoveryNoSendProof(checkNotNull(witness.delivery), StudioRecoveryRefusal.Deferred),
            isNewSendRevoked = true,
        )
        val replay = request.copy(submission = StudioRunSubmission(recoveryDelivery = request.newRecoveryDelivery()))
        val incoming = witness.copy(
            phase = StudioRecoveryPhase.Inspecting,
            delivery = replay.submission?.recoveryDelivery,
        ).withNativeWorkHead(mapOf(closed.execution.value to closed), stored)
        val rearmed = closed.repeatDelivery(incoming)

        assertFalse(closed.hasNativeSend)
        assertEquals(root, rearmed.inspectionTarget)
        assertFailsWith<IllegalStateException> { rearmed.submitting() }
        val fresh = rearmed.idle(native.ref).submitting()
        val retried = prepareGraphContinuationReceipt(stored, fresh, replay)
        assertTrue(retried.isNew)
        assertEquals(first.graphContinuation, retried.receipt.graphContinuation)
        assertEquals(StudioHelperPhase.Preparing, retried.receipt.phase)
        assertNull(retried.receipt.nativeEntryClaim)
    }

    @Test
    fun `fresh idle supersedes ownership without fabricating the original terminal result`() {
        val records = mapOf(root.value to source)
        val prepared = prepareGraphContinuationReceipt(records, witness, request)
        val changed = records + (request.request.value to prepared.receipt)

        assertTrue(prepared.isNew)
        assertEquals(StudioHelperPhase.Accepted, changed.getValue(root.value).phase)
        assertNull(changed.getValue(root.value).terminal)
        assertEquals(listOf(request.request), changed.unresolvedHeads().map { it.request })
        assertEquals(snapshot, prepared.receipt.executionSnapshot)
        assertNull(prepared.receipt.session)
        assertNull(prepared.receipt.turn)
        assertNull(prepared.receipt.nativeEntryClaim)
    }

    @Test
    fun `fresh idle proof does not weaken virtual continuation source requirements`() {
        assertFailsWith<IllegalStateException> {
            prepareContinuationReceipt(
                mapOf(root.value to source),
                root,
                io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt(RequestId("virtual"), "continue"),
            )
        }
    }

    @Test
    fun `broken graph proof cannot hide an unresolved ancestor`() {
        val records = mapOf(root.value to source)
        val child = prepareGraphContinuationReceipt(records, witness, request).receipt
        val invalid = child.copy(
            graphContinuation = checkNotNull(child.graphContinuation).copy(rootFingerprint = "other"),
        )
        assertFailsWith<IllegalStateException> { (records + (child.request.value to invalid)).unresolvedHeads() }
    }

    @Test
    fun `stop of historical root fences the actual accepted child`() {
        val records = acceptedChild()
        val (stopped, head) = stopContinuationChain(records, root, shouldStopNative = true)
        assertEquals(request.request, head.request)
        assertEquals(StudioHelperPhase.Accepted, head.phase)
        assertTrue(head.isStopRequested)
        assertTrue(stopped.getValue(root.value).isStopRequested)
        assertNull(stopped.getValue(root.value).terminal)
    }

    @Test
    fun `same execution redelivery observes its own send and retains historical source proof`() {
        val incoming = witness.copy(
            phase = StudioRecoveryPhase.Inspecting,
            delivery = request.newRecoveryDelivery(),
        ).withNativeWorkHead(mapOf(witness.execution.value to witness), acceptedChild())
        val resumed = witness.repeatDelivery(incoming)

        assertEquals(request.request, resumed.inspectionTarget)
        assertEquals(root, resumed.submittedSource?.request)
        assertNull(resumed.observed)
        assertFalse(resumed.isInspectionCurrent)
        assertFailsWith<IllegalStateException> { resumed.idle(native.ref).submitting() }
    }

    @Test
    fun `new observer inspects the sent child rather than the old native source`() {
        val incoming = witness.copy(
            execution = RequestId("D"),
            phase = StudioRecoveryPhase.Inspecting,
            delivery = null,
            isNativeSendAuthorized = false,
            submittedSource = null,
        ).withNativeWorkHead(mapOf(witness.execution.value to witness), acceptedChild())
        val records = prepareRecoveryTransfer(
            mapOf(witness.execution.value to witness),
            acceptedChild(),
            incoming,
            isLegacyStopped = false,
        )
        val observer = records.getValue("D")
        validateRecoveryWorkLineage(records, acceptedChild())
        assertEquals(request.request, observer.inspectionTarget)
        assertEquals(request.request, observer.predecessorExecution)
        assertNull(observer.observed)
        assertNull(observer.outcome)
        assertFailsWith<IllegalStateException> {
            observer.observed(root, native.ref, source.turn, StudioHelperTerminalOutcome.Cancelled)
        }
    }

    @Test
    fun `missing send receipt targets the uncertain send and never restores old source outcome`() {
        val incoming = witness.copy(
            execution = RequestId("D"),
            phase = StudioRecoveryPhase.Inspecting,
            delivery = null,
            isNativeSendAuthorized = false,
            submittedSource = null,
        ).withNativeWorkHead(mapOf(witness.execution.value to witness), mapOf(root.value to source))
        assertEquals(request.request, incoming.inspectionTarget)
        assertEquals(request.request, incoming.predecessorExecution)
    }

    private fun virtualChildRecords(
        nextSession: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef,
    ): Map<String, StudioHelperReceipt> {
        val childId = RequestId("C")
        val accepted = acceptedChild()
        val q = accepted.getValue(request.request.value)
        val finished = q.copy(
            phase = StudioHelperPhase.Terminal,
            terminal = StudioHelperTerminal(native.ref, q.turn, StudioHelperTerminalOutcome.Cancelled, ""),
        )
        val ancestors = accepted + (q.request.value to finished)
        val child = prepareContinuationReceipt(
            ancestors,
            q.request,
            io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt(childId, "continue"),
        ).receipt.copy(
            phase = StudioHelperPhase.Accepted,
            session = nextSession,
            turn = TurnId("C-turn"),
            nativeRoute = witness.route,
        )
        return ancestors + (childId.value to child)
    }

    private fun acceptedChild(): Map<String, StudioHelperReceipt> {
        val records = mapOf(root.value to source)
        val child = prepareGraphContinuationReceipt(records, witness, request).receipt.copy(
            phase = StudioHelperPhase.Accepted,
            session = native.ref,
            turn = TurnId("Q-turn"),
        )
        return records + (child.request.value to child)
    }
}
