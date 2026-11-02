package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioRecoveryJournalTest {
    private val source = RequestId("source")
    private val engine = EngineId("test")
    private val record = StudioChatRecord(
        "chat",
        "Chat",
        Instant.DISTANT_PAST,
        ref = SessionRef(engine, SessionSourceId("local"), "native"),
        target = EngineTarget(engine, EngineBindingId("saved"), ModelId("native-model")),
        executionWorkspace = WorkspaceRef("saved-workspace"),
    )
    private val request = StudioTurnRequest(
        record.id,
        "assignment",
        RunSettings("future-model", ReasoningEffort.Medium, ApprovalMode.Ask),
        WorktreeRunKind.Coding,
        RequestId("execution"),
        recoveryRequests = listOf(source),
        origin = StudioTurnOrigin.GraphWork(source),
    ).let { it.copy(submission = StudioRunSubmission(recoveryDelivery = it.newRecoveryDelivery())) }

    @Test
    fun `reservation freezes native connection and workspace before publication and relays early Stop`() = runTest {
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        val attempts = StudioHelperAttempts(stores)
        val journal = StudioRecoveryJournal(attempts)

        val witness = journal.reserve(request, record)
        journal.stop(assertNotNull(request.submission?.recoveryDelivery))
        journal.requireReserved(request, record)

        assertEquals(record.ref, witness.session)
        assertEquals(record.target, witness.route?.target)
        assertEquals(record.executionWorkspace, witness.route?.workspace)
        assertTrue(journal.isStopRequested(assertNotNull(request.submission?.recoveryDelivery)))
        assertNull(attempts.receipt(HelperId(record.id), request.request))
        assertFailsWith<IllegalStateException> {
            journal.requireReserved(request.copy(prompt = "another assignment"), record)
        }
        assertFailsWith<IllegalStateException> {
            journal.requireReserved(
                request.copy(recoveryRequests = request.recoveryRequests + RequestId("other")),
                record,
            )
        }
    }

    @Test
    fun `canonical route mutation between host read and durable reserve is refused`() = runTest {
        val stores = ChecklistTestStores()
        val changed = record.copy(target = record.target?.copy(binding = EngineBindingId("replacement")))
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(changed))
        val attempts = StudioHelperAttempts(stores)
        val journal = StudioRecoveryJournal(attempts)

        assertFailsWith<IllegalStateException> { journal.reserve(request, record) }

        assertNull(attempts.recovery(HelperId(record.id), request.request))
    }

    @Test
    fun `workspace credential or resume model replacement invalidates a reservation`() = runTest {
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        val journal = StudioRecoveryJournal(StudioHelperAttempts(stores))
        journal.reserve(request, record)
        assertFailsWith<IllegalStateException> {
            journal.requireReserved(request, record.copy(target = record.target?.copy(model = ModelId("future"))))
        }

        assertFailsWith<IllegalStateException> {
            journal.requireReserved(request, record.copy(executionWorkspace = WorkspaceRef("another-workspace")))
        }
        assertFailsWith<IllegalStateException> {
            journal.requireReserved(
                request,
                record.copy(target = record.target?.copy(binding = EngineBindingId("other"))),
            )
        }
    }
}
