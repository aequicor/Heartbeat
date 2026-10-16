package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBundle
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSummary
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectSync
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.ReviewVerdict
import io.aequicor.heartbeat.feature.projectconfig.impl.data.PROJECT
import io.aequicor.heartbeat.feature.projectconfig.impl.data.Workspaces
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias ScreenProvider =
    Provider<ProjectConfigScreenState, ProjectConfigScreenIntent, ProjectConfigScreenAction>

class ProjectConfigModelTest {
    private val review = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val risky = ConfigProposal(
        ConfigSource.Project(PROJECT),
        review,
        ProposalKind.Create,
        null,
        ConfigDigest("d"),
        ConfigSummary("Code review"),
        review = ProposalReview.Done(ReviewVerdict.Risk, "network"),
    )
    private val safe = risky.copy(
        unit = ConfigUnitRef(ConfigSectionId.Learning, "style"),
        review = ProposalReview.Done(ReviewVerdict.Safe, "ok"),
    )
    private val ready = ProjectConfigState.Ready(
        proposals = listOf(risky, safe),
        projects = mapOf(PROJECT to ProjectSync(1, hasFolder = true)),
        bundle = ConfigBundle("b", "team.hbai"),
    )

    @Test
    fun `the screen mirrors proposals with project names and the open bundle`() = runTest {
        val fixture = Fixture(this, ready)
        val state = fixture.subscribe().states.value

        assertEquals(PhaseUi.Ready, state.phase)
        assertEquals(listOf("Code review", "Code review"), state.proposals.map { it.title })
        assertEquals(SourceUi(PROJECT.value, isBundle = false), state.proposals.first().source)
        assertEquals(listOf(PROJECT.value), state.projects.map { it.name })
        assertEquals("team.hbai", state.bundle)
    }

    @Test
    fun `a safe proposal is trusted at once and a risky one only after confirmation`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()

        screen.intent(ProjectConfigScreenIntent.Trust(safe.id))
        screen.intent(ProjectConfigScreenIntent.Trust(risky.id))
        runCurrent()
        assertEquals(listOf<ProjectConfigIntent>(Public.Trust(safe.id)), fixture.machine.sent)
        assertEquals(risky.id, screen.states.value.confirming)

        screen.intent(ProjectConfigScreenIntent.ConfirmRisk)
        runCurrent()
        assertEquals(Public.Trust(risky.id, isRiskAccepted = true), fixture.machine.sent.last())
        assertNull(screen.states.value.confirming)
    }

    @Test
    fun `an export takes chosen harnesses with their interfaces into the chosen file`() = runTest {
        val fixture = Fixture(this, ready)
        fixture.harnesses.state.value = listOf(FakeEntity("h1", "review", "x"))
        fixture.interfaces.state.value = listOf(FakeEntity("i1", "review/board", "y", parent = "h1"))
        val screen = fixture.subscribe()

        screen.intent(ProjectConfigScreenIntent.OpenPicker(null))
        screen.intent(ProjectConfigScreenIntent.Toggle("harnesses|h1"))
        screen.intent(ProjectConfigScreenIntent.ConfirmPicker)
        runCurrent()

        val export = fixture.machine.sent.single() as Public.Export
        assertEquals("/tmp/out.hbai", export.path)
        assertEquals(
            setOf(
                ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey("h1")),
                ConfigEntityRef(ConfigSectionId.Interfaces, ConfigEntityKey("i1")),
            ),
            export.entities.toSet(),
        )
        assertNull(screen.states.value.picker)
    }

    @Test
    fun `bundles are picked before they are opened and task results become notices`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()

        screen.intent(ProjectConfigScreenIntent.OpenBundle)
        screen.intent(ProjectConfigScreenIntent.ImportToProject(PROJECT.value))
        runCurrent()
        assertTrue(fixture.machine.sent[0] is Public.OpenBundle)
        assertEquals(PROJECT, (fixture.machine.sent[1] as Public.ImportToProject).project)

        fixture.machine.outputs.emit(ProjectConfigOutput.TaskFinished("r", ConfigTaskResult.Exported(2)))
        runCurrent()
        assertEquals(NoticeUi.Exported(2), screen.states.value.notice)
        screen.intent(ProjectConfigScreenIntent.DismissNotice)
        runCurrent()
        assertNull(screen.states.value.notice)
    }

    @Test
    fun `removing a unit from a project asks first`() = runTest {
        val bound = io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding(
            PROJECT,
            review,
            ConfigEntityKey("h1"),
            ConfigDigest("f"),
            ConfigDigest("e"),
        )
        val fixture = Fixture(this, ready.copy(bindings = listOf(bound)))
        val screen = fixture.subscribe()
        val unit = screen.states.value.projects.single().units.single()

        screen.intent(ProjectConfigScreenIntent.RemoveFromProject(PROJECT.value, unit))
        runCurrent()
        assertTrue(fixture.machine.sent.isEmpty())
        assertEquals(unit, screen.states.value.removing?.unit)

        screen.intent(ProjectConfigScreenIntent.ConfirmRemove)
        runCurrent()
        assertEquals(listOf<ProjectConfigIntent>(Public.RemoveFromProject(PROJECT, review)), fixture.machine.sent)
        assertNull(screen.states.value.removing)
    }

    private class Fixture(private val scope: TestScope, initial: ProjectConfigState) {
        val machine = RecordingMachine(initial)
        val harnesses = FakeSection()
        val interfaces = FakeSection(ConfigSectionId.Interfaces, parent = ConfigSectionId.Harnesses)
        private val model = ProjectConfigModel(
            machine,
            Workspaces(mapOf(PROJECT to "/work")),
            setOf(harnesses, interfaces),
            Dialogs(),
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

    private class RecordingMachine(initial: ProjectConfigState) :
        Machine<ProjectConfigState, ProjectConfigIntent, ProjectConfigOutput> {
        override val name: String = "project-config"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<ProjectConfigOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ProjectConfigIntent>()

        override suspend fun send(intent: ProjectConfigIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class Dialogs : ConfigDialogs {
        override val isAvailable: Boolean = true
        override suspend fun pickBundle(): String = "/tmp/in.hbai"
        override suspend fun pickBundleTarget(suggestedName: String): String = "/tmp/out.hbai"
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
}
