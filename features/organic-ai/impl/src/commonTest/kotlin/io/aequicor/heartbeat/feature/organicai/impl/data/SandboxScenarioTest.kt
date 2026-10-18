package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.C2
import io.aequicor.heartbeat.feature.organicai.impl.GOAL
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellDriver
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHandle
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.ImmunityCourt
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiEffects
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.request
import io.aequicor.heartbeat.feature.organicai.impl.session
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sandbox organism end to end: the real machine, effects, driver and worktree adapter run against a worktree machine
 * that applies the real task transitions and engine handles whose native turns outlive a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SandboxScenarioTest {
    private val project = WorkspaceRef("project")
    private val worktree = WorktreeFake(isCutting = true)
    private val journal = MemoryJournal()
    private val natives = Natives()
    private val conception =
        Conception(ORGANISM, GOAL, TARGET, workspace = project, isolation = OrganismIsolation.Sandbox)

    private fun key(cell: CellId): String = "organism.${ORGANISM.value}.${cell.value}"

    private fun TestScope.process(initial: OrganicAiState): Process {
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
        )
        return Process(scope, initial, journal, natives, WorktreeCellWorkspaces(registry(worktree)))
    }

    private suspend fun Process.grow(): Process {
        send(OrganicAiIntent.Public.Conceive(conception))
        send(OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "writer", "write the module"))
        send(OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C2, "tester", "write the tests"))
        return this
    }

    @Test
    fun `parallel cells work in checkouts of their own and never in the project`() = runTest {
        val process = process(OrganicAiState.Living()).grow()
        runCurrent()

        assertEquals(setOf(key(ZYGOTE), key(C1), key(C2)), worktree.tasks().keys)
        assertEquals(3, worktree.tasks().values.map { it.executionWorkspace }.distinct().size)
        val routes = natives.routes.values.flatten()
        assertEquals(3, routes.size)
        assertTrue(routes.none { it.workspace == project }, "a cell opened a session in the project")
        worktree.tasks().values.forEach { task ->
            assertEquals(WorktreePhase.Working, task.phase)
            assertNotNull(task.run?.turn, "the run is bound to the native turn")
            assertNull(task.run?.outcome)
        }
        val organism = process.organism()
        organism.cells.forEach { cell ->
            assertEquals(worktree.task(key(cell.id)).executionWorkspace, cell.workspace)
        }
        assertEquals(3, worktree.prepares.size)
    }

    @Test
    fun `finished cells settle their runs and leave sealed results while the zygote completes the organism`() =
        runTest {
            val process = process(OrganicAiState.Living()).grow()
            runCurrent()

            natives.finish(C1)
            natives.finish(C2)
            runCurrent()
            process.send(OrganicAiIntent.Internal.ReceiveLetters(ORGANISM, ZYGOTE, request(ZYGOTE), 2))
            natives.finish(ZYGOTE)
            runCurrent()

            assertTrue(process.organism().status is OrganismStatus.Completed)
            for (cell in listOf(ZYGOTE, C1, C2)) {
                val task = worktree.task(key(cell))
                assertEquals(TurnOutcome.Completed, task.run?.outcome, cell.value)
                assertNotNull(task.seal?.commit, "the result of ${cell.value} is captured")
                assertTrue(task.phase != WorktreePhase.Retained, "${cell.value} was cleaned up under its parent")
            }
            assertTrue(natives.routes.values.flatten().none { it.workspace == project })
        }

    @Test
    fun `a completed zygote seals a fresh result after follow up and resume`() = runTest {
        val process = process(OrganicAiState.Living())
        process.send(OrganicAiIntent.Public.Conceive(conception))
        runCurrent()
        natives.finish(ZYGOTE)
        runCurrent()
        val first = assertNotNull(worktree.task(key(ZYGOTE)).seal?.commit)

        process.send(OrganicAiIntent.Public.FollowUp(ORGANISM, "add the missing feature"))
        runCurrent()
        assertNull(worktree.task(key(ZYGOTE)).seal)
        natives.finish(ZYGOTE)
        runCurrent()
        val second = assertNotNull(worktree.task(key(ZYGOTE)).seal?.commit)
        assertNotEquals(first, second)

        process.send(OrganicAiIntent.Public.Resume(ORGANISM))
        runCurrent()
        assertNull(worktree.task(key(ZYGOTE)).seal)
        natives.finish(ZYGOTE)
        runCurrent()
        assertNotEquals(second, assertNotNull(worktree.task(key(ZYGOTE)).seal?.commit))
        assertTrue(process.organism().status is OrganismStatus.Completed)
        assertEquals(1, worktree.prepares.size)
        assertEquals(3, natives.handle(ZYGOTE).submitted.size)
    }

    @Test
    fun `aborting the organism cancels every run and keeps the checkouts`() = runTest {
        val process = process(OrganicAiState.Living()).grow()
        runCurrent()

        process.send(OrganicAiIntent.Public.Abort(ORGANISM))
        runCurrent()

        assertEquals(OrganismStatus.Aborted, process.organism().status)
        for (cell in listOf(ZYGOTE, C1, C2)) {
            val task = worktree.task(key(cell))
            assertEquals(TurnOutcome.Cancelled, task.run?.outcome, cell.value)
            assertEquals(WorktreePhase.Idle, task.phase)
            assertNotNull(task.executionWorkspace)
        }
    }

    @Test
    fun `after a restart the same sandboxes and live turns are taken over and a lost turn gets a new run`() = runTest {
        val first = process(OrganicAiState.Living()).grow()
        runCurrent()
        val sandboxes = worktree.tasks().mapValues { it.value.executionWorkspace }
        val identities = worktree.tasks().mapValues { it.value.run }

        // The application dies: its effects stop, the native turns keep running except the one of c2.
        first.stop()
        natives.lose(C2)
        worktree.restart()
        worktree.tasks().values.forEach { assertEquals(WorktreePhase.RecoveryRequired, it.phase) }
        worktree.tasks().values.forEach { assertNull(it.run?.outcome) }

        val second = process(OrganicAiState.Awakening)
        second.send(OrganicAiIntent.Internal.Restored(journal.saved.values.toList()))
        runCurrent()

        // Sandboxes are found again, never cut twice, and no session opened in the project.
        assertEquals(3, worktree.prepares.size)
        assertEquals(sandboxes, worktree.tasks().mapValues { it.value.executionWorkspace })
        natives.routes.forEach { (cell, routes) ->
            assertEquals(1, routes.map { it.workspace }.distinct().size, "${cell.cell.value} moved")
            assertTrue(routes.none { it.workspace == project })
        }
        // The live turns keep their exact identity; nothing was submitted again.
        for (cell in listOf(ZYGOTE, C1)) {
            val task = worktree.task(key(cell))
            assertEquals(WorktreePhase.Working, task.phase, cell.value)
            assertEquals(identities.getValue(key(cell)), task.run, cell.value)
            assertEquals(1, natives.handle(cell).submitted.size)
        }
        // The lost turn's old run was closed as unknown and a new run follows the recovery turn.
        val old = checkNotNull(identities.getValue(key(C2)))
        assertTrue(
            worktree.sent.any {
                it is WorktreeIntent.Public.RunSettled && it.chatId == key(C2) &&
                    it.request == old.request && it.outcome == TurnOutcome.Unknown
            },
        )
        val renewed = worktree.task(key(C2))
        assertEquals(WorktreePhase.Working, renewed.phase)
        assertEquals(request(C2, 2), renewed.run?.request)
        assertEquals(2, natives.handle(C2).submitted.size)

        natives.finish(ZYGOTE)
        natives.finish(C1)
        natives.finish(C2)
        runCurrent()
        assertEquals(TurnOutcome.Completed, worktree.task(key(C1)).run?.outcome)
        assertEquals(identities.getValue(key(C1))?.request, worktree.task(key(C1)).run?.request)
    }

    private class Process(
        private val scope: CoroutineScope,
        initial: OrganicAiState,
        private val journal: MemoryJournal,
        natives: Natives,
        workspaces: WorktreeCellWorkspaces,
    ) : OrganicAiMachine {
        private val sessions = Sessions(natives)
        private val effects = OrganicAiEffects(
            journal,
            { TARGET },
            sessions,
            CellDriver(sessions, journal, workspaces),
            ImmunityCourt({ _, _, _ -> error("no judges in this scenario") }, { emptyList() }),
            workspaces,
        )
        private val log = Log.tag("SandboxScenario")
        override val name: String = "scenario"
        override val state = MutableStateFlow(initial)
        override val outputs: Flow<OrganicAiOutput> = emptyFlow()

        fun organism(): Organism = (state.value as OrganicAiState.Living).organisms.getValue(ORGANISM)

        fun stop() = scope.cancel()

        override suspend fun send(intent: OrganicAiIntent): SendResult {
            val resolution = OrganicAiMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolution.to
            resolution.effects.forEach { effect -> scope.launch { run(effect) } }
            return SendResult.Accepted
        }

        private suspend fun run(effect: OrganicAiEffect) {
            val feedback = object : EffectScope<OrganicAiIntent> {
                override suspend fun send(intent: OrganicAiIntent): SendResult = this@Process.send(intent)
            }
            try {
                effects.handle(effect, feedback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "scenario effect failed" }
                OrganicAiMachineSpec.onEffectFailure(effect, e)?.let { send(it) }
            }
        }
    }

    private class MemoryJournal : OrganismJournal {
        val saved = mutableMapOf<OrganismId, Organism>()

        override suspend fun load(): List<Organism> = saved.values.toList()

        override suspend fun save(organism: Organism) {
            if (organism.version >= (saved[organism.id]?.version ?: -1)) saved[organism.id] = organism
        }
    }

    /** The engine side that survives a restart of the application. */
    private class Natives {
        val handles = mutableMapOf<CellKey, Native>()
        val routes = mutableMapOf<CellKey, MutableList<CellRoute>>()

        fun handle(cell: CellId): Native = handles.getValue(CellKey(ORGANISM, cell))

        fun finish(cell: CellId) = handle(cell).complete(TurnOutcome.Completed)

        fun lose(cell: CellId) = handle(cell).lose()
    }

    private class Sessions(private val natives: Natives) : CellSessions {
        override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int): CellHandle {
            natives.routes.getOrPut(key) { mutableListOf() } += route
            return natives.handles.getOrPut(key) { Native(session(key.cell.value)) }
        }

        override suspend fun respond(key: CellKey, decision: PermissionDecision) = Unit

        override suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode, generation: Int) {
            if (mode == ReleaseMode.Lyse) natives.handles[key]?.complete(TurnOutcome.Cancelled)
        }

        override suspend fun releaseAll() = Unit
    }

    private class Native(override val session: SessionRef) : CellHandle {
        val submitted = mutableListOf<RequestId>()
        private var turn: TurnId? = null
        private var isRunning = false
        private var outcome = CompletableDeferred<TurnOutcome>()

        override fun activeTurn(): TurnId? = turn.takeIf { isRunning }

        override suspend fun submit(
            request: RequestId,
            text: String,
            trust: TrustLevel?,
            attachments: List<ResourceRef>,
        ): TurnId {
            submitted += request
            outcome = CompletableDeferred()
            isRunning = true
            return TurnId("native-${session.nativeId}-${submitted.size}").also { turn = it }
        }

        override suspend fun cancel(turn: TurnId) = complete(TurnOutcome.Cancelled)

        override suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome =
            outcome.await().also { isRunning = false }

        override suspend fun answer(turn: TurnId, isUnconfirmed: Boolean): String = "answer of ${session.nativeId}"

        fun complete(result: TurnOutcome) {
            outcome.complete(result)
        }

        /** The engine no longer runs the turn: a restarted host finds nothing active. */
        fun lose() {
            isRunning = false
        }
    }
}
