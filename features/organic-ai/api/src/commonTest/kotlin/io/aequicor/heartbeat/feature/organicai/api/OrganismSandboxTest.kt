package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganismSandboxTest {
    private val spec = OrganicAiMachineSpec
    private val project = WorkspaceRef("project")
    private val sandbox = WorkspaceRef("sandbox-zygote")

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))

    private fun sandboxed(vararg others: Cell, zygote: Cell = zygoteCell().copy(session = null)): Organism =
        organism(*others, zygote = zygote).copy(workspace = project, isolation = OrganismIsolation.Sandbox)

    private val bind = OrganicAiIntent.Internal.WorkspaceBound(ORGANISM, ZYGOTE, request(ZYGOTE), sandbox)

    @Test
    fun `a sandbox organism is conceived with its isolation and needs a project`() {
        assertFailsWith<IllegalArgumentException> {
            Conception(ORGANISM, GOAL, target = TARGET, isolation = OrganismIsolation.Sandbox)
        }
        val conception =
            Conception(ORGANISM, GOAL, target = TARGET, workspace = project, isolation = OrganismIsolation.Sandbox)
        val zygote = Cell(ZYGOTE, "zygote", null, GOAL, working(ZYGOTE), turns = 1)
        val conceived = Organism(
            ORGANISM, GOAL, TARGET, null, project, null, GrowthLimits(), listOf(zygote),
            version = 1,
            isolation = OrganismIsolation.Sandbox,
        )
        spec.assertTransition(
            OrganicAiState.Living(),
            OrganicAiIntent.Public.Conceive(conception),
            living(conceived),
            effects = listOf(
                OrganicAiEffect.Persist(conceived),
                OrganicAiEffect.Drive(conceived, ZYGOTE, request(ZYGOTE)),
            ),
        )
        assertTrue(conceived.needsSandbox(zygote))
        assertTrue(conceived.isSandboxed)
    }

    @Test
    fun `a shared organism never asks for a sandbox`() {
        val shared = organism().copy(workspace = project)
        assertFalse(shared.needsSandbox(shared.zygote))
        assertFalse(shared.isSandboxed)
        assertEquals(project, shared.workspaceOf(shared.zygote))
    }

    @Test
    fun `the bound sandbox is saved once for the working cell before its session exists`() {
        val germinating = sandboxed()
        val bound = germinating.copy(
            cells = listOf(germinating.zygote.copy(workspace = sandbox)),
            version = 2,
        )
        spec.assertTransition(
            living(germinating),
            bind,
            living(bound),
            effects = listOf(OrganicAiEffect.Persist(bound)),
        )
        spec.assertIgnored(living(bound), bind)
        spec.assertIgnored(living(bound), bind.copy(workspace = WorkspaceRef("other")))
        assertFalse(bound.needsSandbox(bound.zygote))
        assertEquals(sandbox, bound.workspaceOf(bound.zygote))
    }

    @Test
    fun `a stale or foreign binding changes nothing`() {
        val germinating = sandboxed()
        spec.assertIgnored(living(germinating), bind.copy(request = request(ZYGOTE, 2)))
        spec.assertIgnored(living(germinating), bind.copy(cell = C1))
        spec.assertIgnored(living(germinating), bind.copy(organism = OrganismId("other")))
        val resting = sandboxed(zygote = zygoteCell(CellPhase.Resting).copy(session = null))
        spec.assertIgnored(living(resting), bind)
        val shared = organism(zygote = zygoteCell().copy(session = null)).copy(workspace = project)
        spec.assertIgnored(living(shared), bind)
        val withoutProject = sandboxed().copy(workspace = null)
        spec.assertIgnored(living(withoutProject), bind)
    }

    @Test
    fun `a divided child waits for a sandbox of its own and keeps the parents apart`() {
        val parent = sandboxed(zygote = zygoteCell().copy(workspace = sandbox))
        val divide = OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "writer", "write the module")
        val divided = parent.copy(
            cells = parent.cells + Cell(C1, "writer", ZYGOTE, "write the module", working(C1), turns = 1),
            version = 2,
        )
        spec.assertTransition(
            living(parent),
            divide,
            living(divided),
            effects = listOf(OrganicAiEffect.Persist(divided), OrganicAiEffect.Drive(divided, C1, request(C1))),
        )
        val child = divided.cell(C1)!!
        assertTrue(divided.needsSandbox(child))
        assertNull(child.workspace)
        assertFalse(divided.needsSandbox(divided.zygote))
        assertEquals(sandbox, divided.workspaceOf(divided.zygote))
    }

    @Test
    fun `each cell reopens in its own checkout`() {
        val other = WorkspaceRef("sandbox-c1")
        val grown = sandboxed(
            cell(C1).copy(workspace = other),
            zygote = zygoteCell().copy(workspace = sandbox),
        )
        assertEquals(
            ResumeSessionRequest(TARGET, other, areDetachedToolsEnabled = true),
            grown.sessionOf("c1")?.reopening,
        )
        assertEquals(
            ResumeSessionRequest(TARGET, sandbox, areDetachedToolsEnabled = true),
            grown.sessionOf("zygote")?.reopening,
        )
    }

    @Test
    fun `sandbox tasks are derived from the organism and its cell`() {
        val organism = sandboxed()
        assertEquals("organism.o1.zygote", organism.sandboxTask(ZYGOTE))
        assertEquals("organism.o1.c2", organism.sandboxTask(C2))
        assertEquals(
            3,
            setOf(
                organism.sandboxTask(ZYGOTE),
                organism.sandboxTask(C1),
                organism.copy(id = OrganismId("o2")).sandboxTask(C1),
            ).size,
        )
    }
}
