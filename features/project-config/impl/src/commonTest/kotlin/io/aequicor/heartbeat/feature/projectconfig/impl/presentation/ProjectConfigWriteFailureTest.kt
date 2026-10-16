package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigWriteBack
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectSync
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.ReviewVerdict
import io.aequicor.heartbeat.feature.projectconfig.impl.data.PROJECT
import io.aequicor.heartbeat.feature.projectconfig.impl.data.Workspaces
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectConfigWriteFailureTest {
    private val unit = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val binding = ConfigBinding(PROJECT, unit, ConfigEntityKey("h"), ConfigDigest("f1"), ConfigDigest("e1"))
    private val proposal = ConfigProposal(
        ConfigSource.Project(PROJECT),
        unit,
        ProposalKind.Conflict,
        binding.key,
        ConfigDigest("f2"),
        review = ProposalReview.Done(ReviewVerdict.Safe, "safe"),
    )
    private val ready = ProjectConfigState.Ready(
        bindings = listOf(binding),
        projects = mapOf(PROJECT to ProjectSync(1)),
        proposals = listOf(proposal),
    )

    @Test
    fun `a writing proposal stays visible but its actions and the bound unit removal are unavailable`() {
        val writing = ready.copy(writing = setOf(ConfigWriteBack(PROJECT, unit, binding.key, proposal.digest)))
        val screen = ProjectConfigScreenState().reflecting(writing, isSupported = true)
        assertEquals(proposal.id, screen.proposals.single().id)
        assertTrue(screen.proposals.single().isWriting)
        assertFalse(screen.proposals.single().isTrustOffered)
        assertTrue(screen.projects.single().units.single().isWriting)

        val retriable = screen.reflecting(ready, isSupported = true)
        assertFalse(retriable.proposals.single().isWriting)
        assertTrue(retriable.proposals.single().isTrustOffered)
        assertFalse(retriable.projects.single().units.single().isWriting)
    }

    @Test
    fun `a write failure becomes a dismissible notice without removing the proposal`() = runTest {
        val machine = RecordingMachine(ready)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = ProjectConfigModel(
            machine,
            Workspaces(mapOf(PROJECT to "/work")),
            emptySet(),
            object : ConfigDialogs {
                override val isAvailable = true
                override suspend fun pickBundle(): String? = null
                override suspend fun pickBundleTarget(suggestedName: String): String? = null
            },
            HeartbeatStoreFactory(object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            }),
            backgroundScope,
        )
        val provider = CompletableDeferred<WriteFailureProvider>()
        backgroundScope.launch {
            model.store.collect {
                provider.complete(this)
                awaitCancellation()
            }
        }
        runCurrent()
        val screen = provider.await()
        machine.outputs.emit(ProjectConfigOutput.WriteBackFailed)
        runCurrent()
        assertEquals(NoticeUi.WriteBackFailed, screen.states.value.notice)
        assertEquals(proposal.id, screen.states.value.proposals.single().id)

        screen.intent(ProjectConfigScreenIntent.DismissNotice)
        runCurrent()
        assertNull(screen.states.value.notice)
        assertEquals(proposal.id, screen.states.value.proposals.single().id)
    }

    private class RecordingMachine(initial: ProjectConfigState) :
        Machine<ProjectConfigState, ProjectConfigIntent, ProjectConfigOutput> {
        override val name = "project-config"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<ProjectConfigOutput>(extraBufferCapacity = 1)

        override suspend fun send(intent: ProjectConfigIntent): SendResult = SendResult.Accepted
    }
}

private typealias WriteFailureProvider = Provider<
    ProjectConfigScreenState,
    ProjectConfigScreenIntent,
    ProjectConfigScreenAction,
>
