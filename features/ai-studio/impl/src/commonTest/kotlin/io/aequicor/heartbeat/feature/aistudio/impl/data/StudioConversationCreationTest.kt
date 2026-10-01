package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeActionRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioConversationCreationTest {
    @Test
    fun `closing the screen waiter preserves chat identity and finishes the same checkout in the profile`() = runTest {
        val fixture = CreationFixture(backgroundScope)
        val caller = async { fixture.create() }
        runCurrent()
        assertEquals(listOf("chat"), fixture.saved.keys.toList())
        assertEquals("chat", fixture.saved.getValue("chat").worktreeTaskId)
        assertNull(fixture.saved.getValue("chat").executionWorkspace)
        caller.cancelAndJoin()
        fixture.machine.prepared()
        runCurrent()
        assertEquals(WorkspaceRef("checkout"), fixture.saved.getValue("chat").executionWorkspace)
        assertEquals(1, fixture.machine.preparations)
        assertEquals(1, fixture.saved.size)
    }

    @Test
    fun `failed preparation retains an accessible chat linked to its durable task`() = runTest {
        val fixture = CreationFixture(backgroundScope)
        val caller = async { assertFailsWith<IllegalStateException> { fixture.create() } }
        runCurrent()
        fixture.machine.failed()
        runCurrent()
        assertEquals("Worktree preparation failed: ProvisionFailed", caller.await().message)
        assertEquals("chat", fixture.saved.getValue("chat").worktreeTaskId)
        assertTrue(fixture.saved.getValue("chat").hasFailed)
        assertNull(fixture.saved.getValue("chat").executionWorkspace)
    }

    @Test
    fun `ordinary creation persists once and never provisions a checkout`() = runTest {
        val fixture = CreationFixture(backgroundScope)
        val caller = async { fixture.create(isWorktree = false) }
        runCurrent()
        assertEquals("chat", caller.await().id)
        assertEquals(0, fixture.machine.preparations)
        assertFalse(fixture.saved.getValue("chat").hasFailed)
        assertNull(fixture.saved.getValue("chat").worktreeTaskId)
    }

    @Test
    fun `startup resumes a chat interrupted after persistence only after the journal becomes ready`() = runTest {
        val saved = mutableMapOf<String, StudioChatRecord>()
        val previousScope = CoroutineScope(
            backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]),
        )
        val previous = CreationFixture(previousScope, saved)
        val savedBeforePrepare = CompletableDeferred<Unit>()
        previous.afterSave = { savedBeforePrepare.await() }
        val caller = async { previous.create() }
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals(0, previous.machine.preparations)
        previousScope.cancel()
        runCurrent()
        assertFailsWith<CancellationException> { caller.await() }

        val restarted = CreationFixture(backgroundScope, saved)
        restarted.machine.state.value = WorktreeState.Loading
        saved["ordinary"] = restarted.pending().copy(id = "ordinary", worktreeTaskId = null)
        saved["ready"] = restarted.pending().copy(id = "ready", executionWorkspace = WorkspaceRef("existing"))
        restarted.recoverPending()
        runCurrent()
        assertEquals(0, restarted.machine.preparations)
        restarted.machine.state.value = WorktreeState.LoadError
        runCurrent()
        assertEquals(0, restarted.machine.preparations)
        val edited = saved.getValue("chat").copy(title = "Renamed", isPinned = true, isArchived = true)
        saved["chat"] = edited
        saved["later"] = restarted.pending().copy(id = "later", worktreeTaskId = "later")
        restarted.machine.state.value = WorktreeState.Ready()
        runCurrent()
        assertEquals(1, restarted.machine.preparations)
        restarted.machine.prepared()
        runCurrent()
        assertEquals(edited.copy(executionWorkspace = WorkspaceRef("checkout")), saved.getValue("chat"))
        assertEquals(4, saved.size)
        assertNull(saved.getValue("later").executionWorkspace)
        restarted.machine.state.value = WorktreeState.Ready()
        runCurrent()
        assertEquals(1, restarted.machine.preparations)
    }

    @Test
    fun `startup does not replay preparation or actions of any existing uncertain task`() = runTest {
        for (phase in listOf(WorktreePhase.Preparing, WorktreePhase.Failed, WorktreePhase.RecoveryRequired)) {
            val fixture = CreationFixture(backgroundScope)
            fixture.saved["chat"] = fixture.pending()
            val existing = WorktreeTask(
                "chat",
                WorkspaceRef("project"),
                phase = phase,
                run = WorktreeRun(RequestId("request")),
                actionRequest = WorktreeActionRequest("action", WorktreeRunKind.CreatePr, "Create the pull request"),
            )
            val state = WorktreeState.Ready(mapOf("chat" to existing))
            fixture.machine.state.value = state
            fixture.recoverPending()
            runCurrent()
            assertEquals(0, fixture.machine.preparations)
            assertEquals(state, fixture.machine.state.value)
            assertEquals(fixture.pending(), fixture.saved.getValue("chat"))
        }
    }

    @Test
    fun `failed startup preparation marks the saved chat without throwing into the profile`() = runTest {
        val fixture = CreationFixture(backgroundScope)
        fixture.saved["chat"] = fixture.pending()
        fixture.recoverPending()
        runCurrent()
        fixture.machine.failed()
        runCurrent()
        assertTrue(fixture.saved.getValue("chat").hasFailed)
        assertNull(fixture.saved.getValue("chat").executionWorkspace)
        assertTrue(backgroundScope.coroutineContext[Job]?.isActive == true)
    }
}

private class CreationFixture(
    scope: CoroutineScope,
    val saved: MutableMap<String, StudioChatRecord> = mutableMapOf(),
) {
    val machine = CreationMachine { id -> assertTrue(id in saved, "Chat must precede Prepare") }
    var afterSave: suspend () -> Unit = {}
    private val profile = object : ScopeHandle {
        override val name = "creation-profile"
        override val coroutineScope = scope
        override val savedState: ScopeSavedState get() = error("Not used")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }
    private val creation = StudioConversationCreation(profile, StudioWorktrees(creationRegistry(machine), NoAgentTools))

    suspend fun create(isWorktree: Boolean = true): StudioChatRecord = creation.create(pending(isWorktree), ::save)

    fun pending(isWorktree: Boolean = true) = StudioChatRecord(
        "chat",
        "Task",
        Instant.DISTANT_PAST,
        projectId = "project",
        worktreeTaskId = "chat".takeIf { isWorktree },
    )

    fun recoverPending() = creation.recoverPending({ saved.values.toList() }, ::save)

    private suspend fun save(changed: StudioChatRecord) {
        val records = creation.updated(saved.values.toList(), changed)
        saved.clear()
        records.forEach { saved[it.id] = it }
        afterSave()
    }
}

private class CreationMachine(private val beforePrepare: (String) -> Unit) :
    MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = "creation-worktree"
    private var task = WorktreeTask("chat", WorkspaceRef("project"))
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready())
    override val outputs = emptyFlow<WorktreeOutput>()
    var preparations = 0
        private set

    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        val prepare = intent as WorktreeIntent.Public.Prepare
        beforePrepare(prepare.chatId)
        preparations++
        state.value = WorktreeState.Ready(mapOf(task.chatId to task))
        return SendResult.Accepted
    }

    fun prepared() {
        task = task.copy(executionWorkspace = WorkspaceRef("checkout"), phase = WorktreePhase.Idle)
        state.value = WorktreeState.Ready(mapOf(task.chatId to task))
    }

    fun failed() {
        task = task.copy(phase = WorktreePhase.Failed, failure = "ProvisionFailed")
        state.value = WorktreeState.Ready(mapOf(task.chatId to task))
    }
}

private fun creationRegistry(machine: CreationMachine) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // Only the checked worktree key is served by this fixture.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> {
        check(key == WorktreeMachineKey)
        return machine as MachineRef<S, P, O>
    }
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))
    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key).send(intent)
}
