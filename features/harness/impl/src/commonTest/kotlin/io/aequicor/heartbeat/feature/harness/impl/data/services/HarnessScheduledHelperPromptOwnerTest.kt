package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.data.events.EmptyHarnessEventRuns
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.runtimeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperAttachments
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessScheduledHelperPromptOwnerTest {
    private val harness = HarnessId("harness")
    private val helper = HelperId("helper")
    private val action = ActionId("opaque-action")
    private val request = RequestId("R")
    private val origin = HarnessCallOrigin(true, mapOf(harness to 2))
    private val storage = MemoryHarnessRequestAncestry()
    private val bindings = HelperBindingMemory(HarnessHelperBinding(helper, action, harness, RequestId("attach")))
    private val publisher = MutableStateFlow<HarnessAccess>(permitted { true })
    private val target = MutableStateFlow<HarnessAccess>(permitted { true })
    private val workspace = WorkspaceRef("actual-checkout")
    private val attempt = HelperPromptAttempt(
        HelperMetadata(helper, action, null, dispatchSession, request),
        dispatchSession,
        request,
        HelperHandoff(ownerFeature = "harness", ownerContext = HarnessOwnedContext().encode(harness, origin)),
        workspace,
    )
    private var attachments = 0
    private var attach: suspend () -> Boolean = { true }
    private val access = object : HarnessSchedulerAccess {
        override fun permits(harness: HarnessId, target: HarnessTarget?): Flow<HarnessAccess> {
            assertEquals(this@HarnessScheduledHelperPromptOwnerTest.harness, harness)
            return if (target == null) {
                publisher
            } else {
                assertEquals(dispatchSession, target.session)
                assertEquals(workspace, target.workspace)
                this@HarnessScheduledHelperPromptOwnerTest.target
            }
        }

        override fun snapshot() = error("Helper admission never reads a bulk snapshot")
    }
    private val owner = HarnessScheduledHelperPromptOwner(
        lazyOf(bindings),
        lazyOf(
            HarnessHelperAttachments { bound ->
                assertEquals(dispatchSession, bound.session)
                assertEquals(origin, storage.lookup(dispatchSession, request))
                attachments++
                attach()
            },
        ),
        lazyOf(access),
        HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
        lazyOf(storage),
    )

    @Test
    fun `public attachment state changes restart admission without duplicating its durable write`() = runTest {
        val ready = HarnessState.Ready(
            listOf(HarnessEntry(runtimeRequest(1).harness.copy(id = harness))),
            isRuntimeAvailable = true,
        )
        val machine = SchedulerAccessMachine(ready)
        val registry = SchedulerAccessRegistry().apply { library.value = machine }
        val toggles = SchedulerAccessToggles()
        val gate = HarnessEventGate().apply { open() }
        val attached = mapOf(dispatchSession to setOf(harness))
        val release = CompletableDeferred<Unit>()
        machine.onSend = {
            machine.state.value = ready.copy(
                attachmentWrite = HarnessAttachmentWrite(
                    RequestId("attach"),
                    harness,
                    dispatchSession,
                    true,
                    attached,
                ),
            )
            backgroundScope.launch {
                release.await()
                machine.state.value = ready.copy(attachments = attached)
            }
            SendResult.Accepted
        }
        val realOwner = HarnessScheduledHelperPromptOwner(
            lazyOf(bindings),
            lazyOf(MachineHarnessHelperAttachments(registry, toggles)),
            lazyOf(MachineHarnessSchedulerAccess(registry, toggles, HarnessProjectSnapshots(), gate)),
            HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
            lazyOf(storage),
        )
        val result = async { realOwner.admission(attempt.copy(workspace = null)).first() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(1, machine.sent.size)
        release.complete(Unit)
        assertTrue(result.await())
        assertEquals(1, machine.sent.size)
        assertEquals(origin, storage.lookup(dispatchSession, request))
    }

    @Test
    fun `first native prompt waits for ownership ancestry attachment and target activation`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeRestrict = {
            entered.complete(Unit)
            release.await()
        }
        val result = async { owner.admission(attempt).first() }
        entered.await()
        assertFalse(result.isCompleted)
        assertEquals(0, attachments)
        release.complete(Unit)
        assertTrue(result.await())
        assertEquals(dispatchSession, bindings.record?.session)
        assertTrue(owner.admission(attempt).first())
        assertEquals(2, attachments)
    }

    @Test
    fun `attachment revision invalidates old publisher snapshot but fresh target still authorizes`() = runTest {
        var isPublisherCurrent = true
        publisher.value = permitted { isPublisherCurrent }
        attach = {
            isPublisherCurrent = false
            true
        }
        assertTrue(owner.admission(attempt).first())
    }

    @Test
    fun `unknown forged or rebound helpers never attach even with valid owned context`() = runTest {
        val initial = checkNotNull(bindings.record)
        for (invalid in listOf(
            null,
            initial.copy(owner = ActionId("foreign")),
            initial.copy(harness = HarnessId("foreign")),
            initial.copy(session = dispatchSession.copy(nativeId = "foreign")),
        )) {
            bindings.record = invalid
            assertFalse(owner.admission(attempt).first())
        }
        bindings.record = initial
        assertFalse(owner.admission(attempt.copy(handoff = attempt.handoff?.copy(ownerContext = "invalid"))).first())
        assertEquals(0, attachments)
    }

    @Test
    fun `revocation during durable ancestry cancels pending preparation and emits refusal`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeRestrict = {
            entered.complete(Unit)
            release.await()
        }
        val decisions = mutableListOf<Boolean>()
        backgroundScope.launch { owner.admission(attempt).collect { decisions += it } }
        entered.await()
        publisher.value = HarnessAccess.Denied
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(false), decisions)
        assertEquals(0, attachments)
    }

    @Test
    fun `attachment refusal target overflow and storage failure never emit allow`() = runTest {
        attach = { false }
        assertFalse(owner.admission(attempt).first())
        attach = { true }
        target.value = HarnessAccess.Denied
        assertFalse(owner.admission(attempt).first())
        // A superseded target permit is not a refusal: nothing is emitted until the next decision arrives.
        target.value = permitted { false }
        assertNull(withTimeoutOrNull(10.seconds) { owner.admission(attempt).first() })
        target.value = permitted { true }
        storage.failure = IllegalStateException("private failure")
        assertFalse(owner.admission(attempt).first())
    }

    @Test
    fun `unknown provenance and a stale publisher wait for the next decision instead of refusing`() = runTest {
        publisher.value = HarnessAccess.Unknown
        val decisions = mutableListOf<Boolean>()
        backgroundScope.launch { owner.admission(attempt).collect { decisions += it } }
        runCurrent()
        assertTrue(decisions.isEmpty())
        publisher.value = permitted { false }
        runCurrent()
        assertTrue(decisions.isEmpty())
        assertEquals(0, attachments)
        publisher.value = permitted { true }
        runCurrent()
        assertEquals(listOf(true), decisions)
        assertEquals(1, attachments)
    }

    @Test
    fun `initiator relay without any recorded ancestry allows without opening the database`() = runTest {
        val stored = StoredHarnessRequestAncestry(HarnessTestStores())
        val relay = HarnessScheduledHelperPromptOwner(
            lazy { error("must not resolve helper binding") },
            lazy { error("must not attach") },
            lazy { error("must not start feature authority") },
            HarnessEventAncestry(lazyOf(stored), lazyOf(EmptyHarnessEventRuns)),
            lazyOf(stored),
        )
        val source = RequestInitiator(dispatchSession.copy(nativeId = "source"), RequestId("source-R"))
        // HarnessTestStores refuses every database open, so reaching Room would refuse the helper.
        assertTrue(relay.admission(attempt.copy(handoff = HelperHandoff(initiator = source))).first())
    }

    @Test
    fun `initiator relay works while owned feature is off and preserves prior exact target restrictions`() = runTest {
        val source = RequestInitiator(dispatchSession.copy(nativeId = "source"), RequestId("source-R"))
        val prior = HarnessCallOrigin(sendChain = mapOf(harness to 3))
        storage.restrict(source.session, source.request, origin)
        storage.restrict(dispatchSession, request, prior)
        val relay = HarnessScheduledHelperPromptOwner(
            lazy { error("must not resolve helper binding") },
            lazy { error("must not attach") },
            lazy { error("must not start feature authority") },
            HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
            lazyOf(storage),
        )
        assertTrue(relay.admission(attempt.copy(handoff = HelperHandoff(initiator = source))).first())
        assertEquals(origin.merge(prior), storage.lookup(dispatchSession, request))
    }
}

private class HelperBindingMemory(var record: HarnessHelperBinding?) : HarnessHelperBindings {
    override suspend fun bind(binding: HarnessHelperBinding) = error("unused")
    override suspend fun lookup(helper: HelperId): HarnessHelperBinding? = record
    override suspend fun bindSession(helper: HelperId, owner: ActionId, session: SessionRef): HarnessHelperBinding =
        checkNotNull(record).copy(session = session).also { record = it }
}

private fun permitted(isCurrent: suspend () -> Boolean): HarnessAccess =
    HarnessAccess.Permitted(HarnessDeliveryPermit(isCurrent))
