package io.aequicor.heartbeat.feature.agentlearning.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.impl.data.PROJECT
import io.aequicor.heartbeat.feature.agentlearning.impl.data.SavedProjects
import io.aequicor.heartbeat.feature.agentlearning.impl.data.SpecMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias ScreenProvider =
    Provider<AgentLearningScreenState, AgentLearningScreenIntent, AgentLearningScreenAction>

class AgentLearningModelTest {
    private val project = lesson("1", "Project rule")
    private val chat = lesson("2", "Chat rule").copy(project = null)

    @Test
    fun `screen mirrors the registry and saved projects`() = runTest {
        val fixture = Fixture(this, AgentLearningState.Ready(listOf(project, chat), LearningApproval.Ask))
        val screen = fixture.subscribe()
        val state = screen.states.value
        assertEquals(true, state.isLoaded)
        assertEquals(ApprovalUi.Ask, state.approval)
        assertEquals(listOf("1", "2"), state.instructions.map { it.id })
        assertEquals(listOf(PROJECT.value), state.projects.map { it.key })

        screen.intent(AgentLearningScreenIntent.SelectFilter(ProjectFilterUi.WithoutProject))
        runCurrent()
        assertEquals(listOf("2"), screen.states.value.visible.map { it.id })
    }

    @Test
    fun `approval, switches and removal go to the registry`() = runTest {
        val fixture = Fixture(this, AgentLearningState.Ready(listOf(project, chat)))
        val screen = fixture.subscribe()
        screen.intent(AgentLearningScreenIntent.SelectApproval(ApprovalUi.AcceptAll))
        screen.intent(AgentLearningScreenIntent.SetEnabled("1", false))
        screen.intent(AgentLearningScreenIntent.Delete("2"))
        runCurrent()
        assertEquals("2", screen.states.value.deleting)
        screen.intent(AgentLearningScreenIntent.ConfirmDelete)
        runCurrent()
        val registry = fixture.machine.state.value as AgentLearningState.Ready
        assertEquals(LearningApproval.AcceptAll, registry.approval)
        assertEquals(listOf(false), registry.instructions.map { it.isEnabled })
        assertEquals(ApprovalUi.AcceptAll, screen.states.value.approval)
        assertNull(screen.states.value.deleting)
    }

    @Test
    fun `an edit is saved through the registry and a refused edit stays open`() = runTest {
        val fixture = Fixture(this, AgentLearningState.Ready(listOf(project)))
        val screen = fixture.subscribe()
        screen.intent(AgentLearningScreenIntent.Edit("1"))
        screen.intent(AgentLearningScreenIntent.ChangeDraft("Project rule", "", "x".repeat(5_000)))
        screen.intent(AgentLearningScreenIntent.SaveDraft)
        runCurrent()
        assertEquals(LearningErrorUi.EditRejected, screen.states.value.error)
        assertEquals("1", screen.states.value.draft?.id)

        screen.intent(AgentLearningScreenIntent.ChangeDraft("Renamed", "", "Use LF endings"))
        runCurrent()
        assertNull(screen.states.value.error)
        screen.intent(AgentLearningScreenIntent.SaveDraft)
        runCurrent()
        assertNull(screen.states.value.draft)
        val stored = (fixture.machine.state.value as AgentLearningState.Ready).instructions.single()
        assertEquals("Renamed" to "Use LF endings", stored.title to stored.content)
        assertEquals("Renamed", screen.states.value.instructions.single().title)
    }

    @Test
    fun `saving an unchanged draft closes the editor without an error or a write`() = runTest {
        val fixture = Fixture(this, AgentLearningState.Ready(listOf(project), revision = 2))
        val screen = fixture.subscribe()
        screen.intent(AgentLearningScreenIntent.Edit("1"))
        runCurrent()
        screen.intent(AgentLearningScreenIntent.ChangeDraft("Project rule ", "", " Content of Project rule"))
        screen.intent(AgentLearningScreenIntent.SaveDraft)
        runCurrent()
        assertNull(screen.states.value.draft)
        assertNull(screen.states.value.error)
        assertEquals(AgentLearningState.Ready(listOf(project), revision = 2), fixture.machine.state.value)
        assertEquals(emptyList(), fixture.machine.effects)
    }

    @Test
    fun `a draft is measured trimmed and a skill keeps its description`() {
        val draft = DraftUi("1", KindUi.General, " Title ", "", "  Text  ", 5, 300, 4)
        assertTrue(draft.isValid)
        assertFalse(draft.copy(content = "  Texts ").isValid)
        assertFalse(draft.copy(title = "   ").isValid)
        val skill = draft.copy(kind = KindUi.Skill, description = "When needed")
        assertTrue(skill.isValid)
        assertFalse(skill.copy(description = "  ").isValid)
    }

    private fun lesson(id: String, title: String) =
        LearnedInstruction(InstructionId(id), InstructionKind.General, PROJECT, title, "Content of $title")

    private class Fixture(private val scope: TestScope, initial: AgentLearningState) {
        val machine = SpecMachine(initial)
        private val model = AgentLearningModel(
            machine,
            SavedProjects(),
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): ScreenProvider {
            val provider = CompletableDeferred<ScreenProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
}
