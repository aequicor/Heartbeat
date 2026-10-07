package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioHelperPromptAdmissionsTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val helper = HelperId("helper")
    private val request = RequestId("request")
    private val metadata = HelperMetadata(helper, ActionId("action"), null, session, request)
    private val handoff = HelperHandoff(RequestInitiator(session, RequestId("source")), "harness", "private")
    private val receipt = StudioHelperReceipt(
        request,
        StudioHelperPhase.Preparing,
        "hash",
        handoff = handoff,
        preparedSession = session,
    )

    @Test
    fun `exact metadata and handoff reach owner once with all initiating observers intersected`() = runTest {
        val seen = mutableListOf<HelperPromptAttempt>()
        val owner = owner("harness", observes = true) {
            seen += it
            flowOf(true)
        }
        val observer = owner("observer", observes = true) {
            seen += it
            flowOf(false)
        }
        val unused = owner("unused") { error("must not resolve") }
        val workspace = WorkspaceRef("actual-checkout")
        assertFalse(admissions(setOf(owner, observer, unused)).decisions(helper, session, receipt, workspace).first())
        assertTrue(seen.all { it.workspace == workspace })
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.helper == metadata && it.handoff == handoff && it.request == request })
    }

    @Test
    fun `missing ambiguous or mismatched durable identity refuses context admission`() = runTest {
        assertFalse(admissions(emptySet()).decisions(helper, session, receipt).first())
        val owners = setOf(owner("harness") { flowOf(true) }, owner("harness") { flowOf(true) })
        assertFalse(admissions(owners).decisions(helper, session, receipt).first())
        val unique = setOf(owner("harness") { error("wrong metadata must not reach owner") })
        val wrong = metadata.copy(lastRequest = RequestId("other"))
        assertFalse(admissions(unique, wrong).decisions(helper, session, receipt).first())
    }

    @Test
    fun `durable initial admission precedes preparation and is repeated after submitting journal`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepare(helper, HelperPrompt(request, "prompt", handoff = handoff))
        val release = CompletableDeferred<Unit>()
        val phases = mutableListOf<StudioHelperPhase>()
        val owner = owner("harness") {
            flow {
                phases += checkNotNull(attempts.receipt(helper, request)).phase
                release.await()
                emit(true)
            }
        }
        val gate = StudioHelperSubmission(attempts, helper, request, admissions = admissions(setOf(owner)))
        var native = false
        val result = async {
            gate.withPreparation(session) {
                gate.begin()
                native = true
            }
        }
        runCurrent()
        assertFalse(native)
        assertEquals(listOf(StudioHelperPhase.Preparing), phases)
        release.complete(Unit)
        result.await()
        assertTrue(native)
        assertEquals(listOf(StudioHelperPhase.Preparing, StudioHelperPhase.Submitting), phases)
    }

    @Test
    fun `caller cancellation while waiting initial context retires preparation`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepare(helper, HelperPrompt(request, "prompt", handoff = handoff))
        val owner = owner("harness") { flow { awaitCancellation() } }
        val gate = StudioHelperSubmission(attempts, helper, request, admissions = admissions(setOf(owner)))
        val result = async { gate.withPreparation(session) { error("must not prepare") } }
        runCurrent()
        result.cancel()
        assertFailsWith<CancellationException> { result.await() }
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(helper, request)?.phase)
        assertTrue(gate.isCancelled)
    }

    @Test
    fun `trust lowered during fresh admission reaches native handoff instead of captured Full`() = runTest {
        val stores = ChecklistTestStores()
        val parent = StudioChatRecord(
            "parent",
            "Parent",
            Instant.DISTANT_PAST,
            ref = session,
            configuration = StudioSessionSettings("model", approval = ApprovalMode.AutoApprove),
        )
        val record = StudioChatRecord(
            helper.value,
            "Helper",
            Instant.DISTANT_PAST,
            helper = StudioHelperIdentity(metadata.owner, parent.id, session, TrustLevel.Full),
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(parent, record))
        val policy = StudioHelperPolicy(stores)
        var configurations = emptyMap<String, StudioSessionConfiguration>()
        val captured = policy.trust(record, TrustLevel.Full) { configurations }
        assertEquals(TrustLevel.Full, captured)
        val attempts = StudioHelperAttempts(stores)
        attempts.prepare(helper, HelperPrompt(request, "prompt", handoff = handoff))
        val fresh = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var collections = 0
        val owner = owner("harness") {
            flow {
                if (++collections == 2) {
                    fresh.complete(Unit)
                    release.await()
                }
                emit(true)
            }
        }
        val gate = StudioHelperSubmission(attempts, helper, request, admissions = admissions(setOf(owner)))
        val nativeTrust = async {
            gate.withPreparation(session) {
                gate.begin { policy.trust(record, captured) { configurations } }
            }
        }
        fresh.await()
        configurations = mapOf(parent.id to StudioSessionConfiguration(StudioSessionSettings("model")))
        release.complete(Unit)
        assertEquals(TrustLevel.Ask, nativeTrust.await())
    }

    @Test
    fun `cancelling final configuration refresh still retires local submitting claim`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepare(helper, HelperPrompt(request, "prompt"))
        val gate = StudioHelperSubmission(attempts, helper, request, admissions = emptyHelperAdmissions())
        val refreshing = CompletableDeferred<Unit>()
        val result = async {
            gate.withPreparation(session) {
                gate.begin {
                    refreshing.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        refreshing.await()
        result.cancel()
        assertFailsWith<CancellationException> { result.await() }
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(helper, request)?.phase)
    }

    private fun admissions(owners: Set<ScheduledHelperPromptOwner>, record: HelperMetadata = metadata) =
        StudioHelperPromptAdmissions(
            lazyOf(object : StudioHelperChatRecords {
                override suspend fun helperMetadata(helper: HelperId): HelperMetadata = record
                override suspend fun helperMetadata(session: SessionRef): HelperMetadata = error("unused")
                override suspend fun createHelper(request: HelperCreateRequest): HelperId = error("unused")
                override suspend fun isHelper(session: SessionRef): Boolean = error("unused")
                override suspend fun ownedHelpers(owner: ActionId, after: HelperId?, limit: Int): List<HelperMetadata> =
                    error("unused")
            }),
            lazyOf(owners),
        )

    private fun owner(
        feature: String,
        observes: Boolean = false,
        decisions: (HelperPromptAttempt) -> Flow<Boolean>,
    ): ScheduledHelperPromptOwner = object : ScheduledHelperPromptOwner {
        override val feature = feature
        override val isObservingInitiators = observes
        override fun admission(attempt: HelperPromptAttempt): Flow<Boolean> = decisions(attempt)
    }
}
