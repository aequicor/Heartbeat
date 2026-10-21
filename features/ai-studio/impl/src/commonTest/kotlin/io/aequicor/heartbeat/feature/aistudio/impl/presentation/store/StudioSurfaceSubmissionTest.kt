package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioSurfaceSubmissionTest {
    private val mutations: List<(AiStudioScreenState) -> AiStudioScreenState> = listOf(
        { it },
        { it.withDraft(0, "User replacement") },
        { it.copy(panes = persistentListOf(PaneUi(0, projectId = "other"))) },
        { it.copy(attachmentRequests = persistentMapOf("import" to it.draftKey(0))) },
        {
            it.copy(
                draftAttachments = persistentMapOf(
                    it.draftKey(0) to persistentListOf(AttachmentUi("file", "User.md", "text/markdown", 1)),
                ),
            )
        },
    )

    @Test
    fun `delayed auto submit revalidates draft destination and attachments under the submission lock`() = runTest {
        for ((index, mutate) in mutations.withIndex()) {
            val initial = AiStudioScreenState(phase = StudioPhase.Ready, panes = persistentListOf(PaneUi(0)))
            val repository = InMemoryStudioRepository(TestClock(this))
            val backend = object : StudioBackend {
                override suspend fun repository() = repository
                override suspend fun effects() = EffectHandler.None
            }
            val prompts = MutableSharedFlow<StudioSurfacePrompt>()
            val surfaces = object : StudioSurfaces by StudioSurfaces.None {
                override val prompts = prompts
                override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) = places.collect { }
            }
            val binding = StudioSurfaceBinding(surfaces, backend, TestScopeHandle(backgroundScope))
            binding.onState(initial)
            val machine = FakeMachine(
                AiStudioState.Ready(panes = listOf(StudioPane(0)), focusedPaneId = 0, settings = DefaultRunSettings),
            )
            val release = CompletableDeferred<Unit>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = HeartbeatStoreFactory(object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            })
            val store = factory.create<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>(
                "SurfaceGuard$index",
                initial,
                onError = { throw it },
            ) {
                init {
                    launch {
                        binding.observe(this@init) { pane, guard ->
                            release.await()
                            submitStudioDraft(this@init, machine, StudioHarnesses.None, pane, isCurrent = guard)
                        }
                    }
                }
                reduce { updateState { mutate(this) } }
            }
            store.subscribeAndTest {
                runCurrent()
                prompts.emit(StudioSurfacePrompt(0, "Surface prompt", true))
                runCurrent()
                assertEquals("Surface prompt", states.value.draft(0))
                AiStudioScreenIntent.Retry resultsIn { runCurrent() }
                release.complete(Unit)
                runCurrent()
                val submitted = machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>()
                assertEquals(if (index == 0) listOf("Surface prompt") else emptyList(), submitted.map { it.prompt })
                submitted.forEach { assertNull(it.userInstruction) }
            }
        }
    }
}
