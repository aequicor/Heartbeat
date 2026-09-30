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
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
}

private class CreationFixture(scope: CoroutineScope) {
    val saved = mutableMapOf<String, StudioChatRecord>()
    val machine = CreationMachine { id -> assertTrue(id in saved, "Chat must precede Prepare") }
    private val profile = object : ScopeHandle {
        override val name = "creation-profile"
        override val coroutineScope = scope
        override val savedState: ScopeSavedState get() = error("Not used")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }
    private val creation = StudioConversationCreation(profile, StudioWorktrees(creationRegistry(machine), NoAgentTools))

    suspend fun create(isWorktree: Boolean = true): StudioChatRecord = creation.create(
        StudioChatRecord(
            "chat",
            "Task",
            Instant.DISTANT_PAST,
            projectId = "project",
            worktreeTaskId = "chat".takeIf { isWorktree },
        ),
    ) { saved[it.id] = it }
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
