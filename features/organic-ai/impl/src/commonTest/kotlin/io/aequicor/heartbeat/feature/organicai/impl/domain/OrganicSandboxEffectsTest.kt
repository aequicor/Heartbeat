package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.Settlement
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.request
import io.aequicor.heartbeat.feature.organicai.impl.working
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class OrganicSandboxEffectsTest : OrganicEffectsFixture() {
    private val project = WorkspaceRef("project")

    private fun sandboxed(vararg others: Cell): Organism =
        organism(*others).copy(workspace = project, isolation = OrganismIsolation.Sandbox)

    @Test
    fun `a sandbox cell is bound to its own checkout before its session is created`() = runTest {
        val germinating = sandboxed(cell(C1).copy(session = null))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        val sandbox = WorkspaceRef("sandbox-c1")
        assertEquals(
            listOf<OrganicAiIntent>(
                OrganicAiIntent.Internal.WorkspaceBound(ORGANISM, C1, request(C1), sandbox),
                OrganicAiIntent.Internal.SessionBound(ORGANISM, C1, request(C1), cells.handle.session),
                OrganicAiIntent.Internal.TurnAccepted(ORGANISM, C1, request(C1), TurnId("t1")),
                OrganicAiIntent.Internal.TurnSettled(ORGANISM, C1, request(C1), Settlement.Answered("the answer")),
            ),
            machine.sent,
        )
        assertEquals(listOf(germinating to C1), workspaces.prepared)
        assertEquals(listOf(sandbox), cells.routes.map { it.workspace })
    }

    @Test
    fun `a cell with a bound sandbox reuses it and a shared cell works in the project`() = runTest {
        val bound = sandboxed(cell(C1).copy(workspace = WorkspaceRef("sandbox-kept")))
        effects.handle(OrganicAiEffect.Drive(bound, C1, request(C1)), machine)
        assertEquals(emptyList(), workspaces.prepared)
        assertEquals(listOf(WorkspaceRef("sandbox-kept")), cells.routes.map { it.workspace })
        assertTrue(machine.sent.none { it is OrganicAiIntent.Internal.WorkspaceBound })

        val shared = organism(cell(C1)).copy(workspace = project)
        effects.handle(OrganicAiEffect.Drive(shared, C1, request(C1)), machine)
        assertEquals(emptyList(), workspaces.prepared)
        assertEquals(project, cells.routes.last().workspace)
    }

    @Test
    fun `a cell whose sandbox cannot be prepared never opens a session in the project`() = runTest {
        val failure = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        workspaces.failure = failure
        val thrown = assertFailsWith<EngineException> {
            effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        }
        assertEquals(failure, thrown)
        assertEquals(emptyList(), cells.opened)
        assertEquals(emptyList(), machine.sent)
        assertEquals(emptyList(), cells.handle.submitted)
    }

    @Test
    fun `a cell that ended before its sandbox was bound opens no session`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.WorkspaceBound }
        effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        assertEquals(emptyList(), cells.opened)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(emptyList(), cells.released)
    }

    @Test
    fun `a completed sandbox cell and the zygote have their results captured before their sessions are released`() =
        runTest {
            val done = sandboxed(cell(C1, phase = CellPhase.Completed("done")))
            effects.handle(OrganicAiEffect.Release(done, listOf(C1, ZYGOTE), ReleaseMode.Retire), machine)
            assertEquals(listOf(C1, ZYGOTE), workspaces.captured)
            assertEquals(listOf(C1, ZYGOTE), cells.released.map { it.first.cell })
        }

    @Test
    fun `a killed cell or a shared organism captures nothing and a failed capture never keeps a cell alive`() =
        runTest {
            val ended = sandboxed(cell(C1, phase = CellPhase.Completed("done")))
            effects.handle(OrganicAiEffect.Release(ended, listOf(C1), ReleaseMode.Lyse), machine)
            effects.handle(
                OrganicAiEffect.Release(organism(cell(C1)).copy(workspace = project), listOf(C1), ReleaseMode.Retire),
                machine,
            )
            assertEquals(emptyList(), workspaces.captured)
            workspaces.captureFailure = IllegalStateException("git failed")
            effects.handle(OrganicAiEffect.Release(ended, listOf(C1), ReleaseMode.Retire), machine)
            assertEquals(3, cells.released.size)
        }

    @Test
    fun `a sandbox turn is registered before it is submitted and reported to its end`() = runTest {
        cells.handle.onSubmit = { assertEquals(listOf("begin c1 ${request(C1).value}"), workspaces.events) }
        effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        assertEquals(
            listOf("begin c1 ${request(C1).value}", "accepted t1", "settled t1 ${TurnOutcome.Completed}"),
            workspaces.events,
        )
        assertEquals(1, cells.handle.submitted.size)
    }

    @Test
    fun `a shared cell has no sandbox run to register`() = runTest {
        effects.handle(OrganicAiEffect.Drive(organism(cell(C1)).copy(workspace = project), C1, request(C1)), machine)
        assertEquals(emptyList(), workspaces.events)
    }

    @Test
    fun `a failed or cancelled sandbox turn is reported with the outcome the engine gave`() = runTest {
        cells.handle.outcome = TurnOutcome.Cancelled
        effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        assertEquals("settled t1 ${TurnOutcome.Cancelled}", workspaces.events.last())
        assertEquals(
            Settlement.Broke(Breakdown.Interrupted),
            (machine.sent.last() as OrganicAiIntent.Internal.TurnSettled).settlement,
        )
    }

    @Test
    fun `a sandbox that cannot take the run never receives a submission`() = runTest {
        val failure = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        workspaces.beginFailure = failure
        val thrown = assertFailsWith<EngineException> {
            effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        }
        assertEquals(failure, thrown)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(emptyList(), workspaces.events)
    }

    @Test
    fun `a turn that cannot be submitted or followed keeps its run as lost`() = runTest {
        cells.handle.onSubmit = { error("engine refused") }
        assertFailsWith<IllegalStateException> {
            effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        }
        assertEquals("lost new -", workspaces.events.last())
        workspaces.events.clear()
        cells.handle.onSubmit = {}
        cells.handle.onAwait = { error("handle closed") }
        assertFailsWith<IllegalStateException> {
            effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        }
        assertEquals(listOf("accepted t1", "lost new t1"), workspaces.events.drop(1))
    }

    @Test
    fun `cancelling the effect leaves the engine turn and its run alone`() = runTest {
        cells.handle.onAwait = { throw CancellationException("the profile sleeps") }
        assertFailsWith<CancellationException> {
            effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        }
        assertEquals(listOf("begin c1 ${request(C1).value}", "accepted t1"), workspaces.events)
    }

    @Test
    fun `a turn the machine no longer takes is cancelled and its run settled as cancelled`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.TurnAccepted }
        effects.handle(OrganicAiEffect.Drive(sandboxed(cell(C1)), C1, request(C1)), machine)
        assertEquals("settled t1 ${TurnOutcome.Cancelled}", workspaces.events.last())
        assertEquals(listOf(TurnId("t1")), cells.handle.cancelled)
    }

    @Test
    fun `a recovery adopts the live turn with the run that survived the restart`() = runTest {
        val recovering = sandboxed(cell(C1, phase = working(C1, isRecovery = true)))
        cells.handle.active = TurnId("live")
        workspaces.adoptable = TurnId("live")
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1)), machine)
        assertEquals(
            listOf("adopt c1 live", "accepted live", "settled live ${TurnOutcome.Completed}"),
            workspaces.events,
        )
        assertEquals(emptyList(), cells.handle.submitted)
    }

    @Test
    fun `an unmatched live turn keeps its sandbox recovery barrier without a replacement run`() = runTest {
        val recovering = sandboxed(cell(C1, phase = working(C1, isRecovery = true)))
        cells.handle.active = TurnId("live")
        val failure = assertFailsWith<EngineException> {
            effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1)), machine)
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
        assertEquals(emptyList(), workspaces.events)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(emptyList(), cells.handle.cancelled)
        assertTrue(machine.sent.none { it is OrganicAiIntent.Internal.TurnAccepted })
    }

    @Test
    fun `a recovery without a live turn starts a new sandbox run and submits`() = runTest {
        val recovering = sandboxed(cell(C1, phase = working(C1, isRecovery = true)))
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1)), machine)
        assertEquals("begin c1 ${request(C1).value}", workspaces.events.first())
        assertEquals(1, cells.handle.submitted.size)
    }
}
