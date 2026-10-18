package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorktreeClosedSubmissionTest {
    private val request = RequestId("request")
    private val session = SessionRef(EngineId("engine"), SessionSourceId("source"), "session")
    private val task = WorktreeTask(
        "cell",
        WorkspaceRef("project"),
        WorkspaceRef("sandbox"),
        phase = WorktreePhase.Idle,
    )
    private val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request))

    @Test
    fun `proven rejection persists a receipt without inventing a native outcome`() {
        val rejected = pending.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "Rejected"))
        assertNull(rejected.run)
        assertEquals(request, rejected.closedSubmission)
        assertEquals(rejected, Json.decodeFromString<WorktreeTask>(Json.encodeToString(rejected)))
        assertEquals(rejected, rejected.reconciled(true, rejected.executionWorkspace, restart = true))
        val legacy = Json.encodeToString(task)
        assertNull(Json.decodeFromString<WorktreeTask>(legacy).closedSubmission)
        val stale = WorktreeIntent.Public.RunRejected(task.chatId, RequestId("stale"), "Rejected")
        assertEquals(pending, pending.transition(stale))
        val accepted = pending.transition(
            WorktreeIntent.Public.RunAccepted(task.chatId, request, session, TurnId("turn")),
        )
        assertEquals(accepted, accepted.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "late")))
    }

    @Test
    fun `idle reconciliation proves closure only for its exact unaccepted recovery request`() {
        val lost = pending.transition(
            WorktreeIntent.Public.RunSubmissionObservationLost(task.chatId, request, session, "Lost"),
        )
        assertNull(lost.closedSubmission)
        assertEquals(lost, lost.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "NotProven")))
        val command = WorktreeIntent.Public.RunSubmissionReconciled(task.chatId, request, session)
        assertEquals(lost, lost.transition(command.copy(request = RequestId("stale"))))
        assertEquals(lost, lost.transition(command.copy(session = SessionRef(session.engine, session.source, "other"))))
        val reconciled = lost.transition(command)
        assertNull(reconciled.run)
        assertEquals(request, reconciled.closedSubmission)
        assertEquals(reconciled, Json.decodeFromString<WorktreeTask>(Json.encodeToString(reconciled)))
    }

    @Test
    fun `a new coding or tracked native run invalidates the old closure receipt`() {
        val rejected = pending.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "Rejected"))
        val next = rejected.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("next")))
        assertNull(next.closedSubmission)
        val tracked = rejected.trackingMain(
            WorktreeIntent.Public.TrackMainSession(task.project, session, request, TurnId("turn")),
        )
        assertNull(tracked.closedSubmission)
    }
}
