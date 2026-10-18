package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrganismResultNoticeTest {
    private val workspace = WorkspaceRef("sandbox")
    private val session = SessionRef(EngineId("pi"), SessionSourceId("local"), "root")
    private val organism = Organism(
        id = OrganismId("chat"),
        goal = "Change a file",
        target = null,
        immunityTarget = null,
        workspace = WorkspaceRef("project"),
        trust = null,
        limits = GrowthLimits(),
        cells = listOf(
            Cell(
                CellId.ZYGOTE,
                "root",
                null,
                "Change a file",
                CellPhase.Completed("Done"),
                session = session,
                workspace = workspace,
                turns = 2,
            ),
        ),
        status = OrganismStatus.Completed("Done"),
        isolation = OrganismIsolation.Sandbox,
    )
    private val key = organism.sandboxTask(CellId.ZYGOTE)
    private val task = WorktreeTask(
        key,
        WorkspaceRef("project"),
        executionWorkspace = workspace,
        branch = "heartbeat/worktree-root",
        baseCommit = "base-commit",
        // A recovered native run keeps its original request even when the organism's generation advanced.
        run = WorktreeRun(
            RequestId("old-request"),
            session = session,
            turn = TurnId("turn"),
            outcome = TurnOutcome.Completed,
        ),
        seal = WorktreeSeal("capture", "commit-with-dirty-file"),
    )

    @Test
    fun `restored completion publishes the captured commit rather than the unchanged branch`() = runTest {
        val result = organismResultNotices(flowOf(mapOf("chat" to organism)), flowOf(mapOf(key to task)))
            .first().getValue("chat")
        assertEquals("commit-with-dirty-file", result.commit)
        assertEquals("heartbeat/worktree-root", result.branch)
        assertEquals("base-commit", result.base)
        assertFalse(result.isTimestampKnown)
    }

    @Test
    fun `a delayed seal appears and continuation removes the previous result before its next capture`() = runTest {
        val organisms = MutableStateFlow(mapOf("chat" to organism))
        val tasks = MutableStateFlow(mapOf(key to task.copy(seal = null)))
        var notices = emptyMap<String, MessageUi.OrganismResult>()
        backgroundScope.launch { organismResultNotices(organisms, tasks).collect { notices = it } }
        runCurrent()
        assertTrue(notices.isEmpty())
        tasks.value = mapOf(key to task)
        runCurrent()
        assertEquals("commit-with-dirty-file", notices.getValue("chat").commit)
        organisms.value = mapOf("chat" to organism.copy(status = OrganismStatus.Developing))
        runCurrent()
        assertTrue(notices.isEmpty())
        tasks.value = mapOf(key to task.copy(seal = null))
        organisms.value = mapOf("chat" to organism)
        runCurrent()
        assertTrue(notices.isEmpty())
        tasks.value = mapOf(key to task.copy(seal = WorktreeSeal("second", "new-commit")))
        runCurrent()
        assertEquals("new-commit", notices.getValue("chat").commit)
    }

    @Test
    fun `live or foreign runs and unavailable captures are never advertised as the result`() = runTest {
        val variants = listOf(
            task.copy(run = task.run?.copy(outcome = null)),
            task.copy(run = task.run?.copy(session = session.copy(nativeId = "foreign"))),
            task.copy(executionWorkspace = WorkspaceRef("other")),
            task.copy(seal = WorktreeSeal("failed", failure = "TurnActive")),
        )
        for (unavailable in variants) {
            val result = organismResultNotices(
                flowOf(mapOf("chat" to organism)),
                flowOf(mapOf(key to unavailable)),
            ).first()
            assertTrue(result.isEmpty())
        }
    }
}
