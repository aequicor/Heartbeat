package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessDetailModelTest {
    private val session = SessionRef(EngineId("claude"), SessionSourceId("local"), "chat-1")
    private val first = LocalWorkspace(WorkspaceRef("p1"), "heartbeat")
    private val second = LocalWorkspace(WorkspaceRef("p2"), "site")

    private fun ready(scope: HarnessScope = HarnessScope.Attached) = HarnessState.Ready(
        harnesses = listOf(HarnessEntry(harness.copy(scope = scope, revision = 3))),
        attachments = mapOf(session to setOf(harness.id)),
    )

    @Test
    fun `deleting the harness closes the screen`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        val closed = backgroundScope.async { screen.actions.first() }
        runCurrent()

        library.outputs.emit(HarnessOutput.Deleted(request(), harness.id))
        runCurrent()
        assertEquals(HarnessDetailAction.Close, closed.await())
    }

    @Test
    fun `a projects scope starts with a saved project and never becomes empty`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library, listOf(first, second)).store)

        screen.intent(HarnessDetailIntent.SelectScope(ScopeUi.Projects))
        runCurrent()
        assertEquals(HarnessScope.Projects(setOf(first.ref)), library.scopeChange())
        assertEquals(3L, (library.sent.last() as HarnessIntent.Internal.Update).expectedRevision)

        library.state.value = ready(HarnessScope.Projects(setOf(first.ref)))
        runCurrent()
        assertEquals(listOf(true, false), screen.states.value.projects.map { it.isSelected })
        library.sent.clear()
        screen.intent(HarnessDetailIntent.ToggleProject(first.ref.value))
        runCurrent()
        assertTrue(library.sent.isEmpty(), "the last project cannot be removed")
        screen.intent(HarnessDetailIntent.ToggleProject(second.ref.value))
        runCurrent()
        assertEquals(HarnessScope.Projects(setOf(first.ref, second.ref)), library.scopeChange())

        screen.intent(HarnessDetailIntent.SelectScope(ScopeUi.Profile))
        runCurrent()
        assertEquals(HarnessScope.Profile, library.scopeChange())
    }

    @Test
    fun `without saved projects the projects scope is not selected`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)

        screen.intent(HarnessDetailIntent.SelectScope(ScopeUi.Projects))
        runCurrent()
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `metadata saves trimmed valid values and cancelling drops the edit`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)

        screen.intent(HarnessDetailIntent.EditMeta)
        screen.intent(HarnessDetailIntent.ChangeMeta(" ", "Anything"))
        screen.intent(HarnessDetailIntent.SaveMeta)
        runCurrent()
        assertTrue(library.sent.isEmpty(), "a blank title is not saved")
        assertEquals(MetaDraftUi(" ", "Anything"), screen.states.value.meta)

        screen.intent(HarnessDetailIntent.ChangeMeta("  Compose UI ", " Native screens "))
        screen.intent(HarnessDetailIntent.SaveMeta)
        runCurrent()
        val update = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(HarnessChange.Meta("Compose UI", "Native screens"), update.change)
        assertNull(screen.states.value.meta)

        screen.intent(HarnessDetailIntent.EditMeta)
        screen.intent(HarnessDetailIntent.ChangeMeta("Other", ""))
        screen.intent(HarnessDetailIntent.CancelMeta)
        runCurrent()
        assertNull(screen.states.value.meta)
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `detaching a connected chat sends its session`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        val connected = screen.states.value.attachments.single()

        screen.intent(HarnessDetailIntent.Detach(connected.key))
        runCurrent()
        val detach = assertIs<HarnessIntent.Public.Detach>(library.sent.single())
        assertEquals(harness.id to session, detach.id to detach.session)
    }

    @Test
    fun `only own requests show errors and the rejection reason picks the message`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        screen.intent(HarnessDetailIntent.SetEnabled(false))
        runCurrent()
        val own = assertIs<HarnessIntent.Internal.SetEnabled>(library.sent.single()).requestId

        library.outputs.emit(HarnessOutput.Rejected(request(), HarnessRejection.Conflict))
        library.outputs.emit(HarnessOutput.StorageFailed(request()))
        runCurrent()
        assertNull(screen.states.value.error, "agent writes report to the agent")

        val expected = mapOf(
            HarnessRejection.Busy to DetailErrorUi.Busy,
            HarnessRejection.Conflict to DetailErrorUi.Conflict,
            HarnessRejection.Limit to DetailErrorUi.Limit,
            HarnessRejection.Invalid to DetailErrorUi.SaveFailed,
        )
        expected.forEach { (reason, error) ->
            library.outputs.emit(HarnessOutput.Rejected(own, reason))
            runCurrent()
            assertEquals(error, screen.states.value.error, reason.name)
            screen.intent(HarnessDetailIntent.DismissError)
            runCurrent()
        }
        library.outputs.emit(HarnessOutput.StorageFailed(own))
        runCurrent()
        assertEquals(DetailErrorUi.SaveFailed, screen.states.value.error)
    }

    private fun TestScope.detail(library: RecordingLibrary, projects: List<LocalWorkspace> = emptyList()) =
        HarnessDetailModel(
            harness.id.value,
            library,
            IdleRuns(),
            SavedProjects(projects),
            storeFactory(),
            backgroundScope,
        )

    private fun RecordingLibrary.scopeChange(): HarnessScope =
        assertIs<HarnessChange.Scope>(assertIs<HarnessIntent.Internal.Update>(sent.last()).change).scope
}

private class IdleRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Idle())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult = SendResult.Accepted
}

private class SavedProjects(private val projects: List<LocalWorkspace>) : LocalWorkspaces {
    override val isAvailable = true

    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(projects)

    override suspend fun register(directory: String): LocalWorkspace = error("unused")

    override suspend fun resolve(ref: WorkspaceRef): String? = null
}
