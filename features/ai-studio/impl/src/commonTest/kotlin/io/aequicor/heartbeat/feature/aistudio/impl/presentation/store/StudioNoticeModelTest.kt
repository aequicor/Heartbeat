package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.dismissedNotices
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioNoticeModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
        projectErrorPane = 0,
        stopFailures = setOf("s-facade"),
    )

    @Test
    fun `dismissal remains local through runtime reflection and reappears for the next failure`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        val sentBefore = fixture.machine.sent.toList()
        val dismiss = AiStudioScreenIntent.DismissNotice(0, StudioNoticeUi.ProjectFailed)
        assertTrue(isAllowedRemotely(dismiss))
        fixture.model.store.intent(dismiss)
        fixture.model.store.intent(AiStudioScreenIntent.DismissNotice(0, StudioNoticeUi.StopFailed))
        runCurrent()
        assertEquals(sentBefore, fixture.machine.sent)
        assertEquals(ready, fixture.machine.state.value)
        assertTrue(StudioNoticeUi.ProjectFailed in screen.states.value.dismissedNotices(0))
        assertTrue(StudioNoticeUi.StopFailed in screen.states.value.dismissedNotices(0))

        fixture.machine.state.value = ready.copy(running = setOf("s-facade"))
        runCurrent()
        assertTrue(StudioNoticeUi.ProjectFailed in screen.states.value.dismissedNotices(0))
        assertTrue(StudioNoticeUi.StopFailed in screen.states.value.dismissedNotices(0))

        fixture.machine.state.value = ready.copy(projectErrorPane = null, stopFailures = emptySet())
        runCurrent()
        fixture.machine.state.value = ready
        runCurrent()
        assertTrue(screen.states.value.dismissedNotices(0).isEmpty())
    }

    @Test
    fun `explicit retries reveal errors even when no intermediate machine snapshot arrives`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.DismissNotice(0, StudioNoticeUi.ProjectFailed))
        fixture.model.store.intent(AiStudioScreenIntent.DismissNotice(0, StudioNoticeUi.StopFailed))
        runCurrent()
        fixture.model.store.intent(AiStudioScreenIntent.AddProject(0))
        fixture.model.store.intent(AiStudioScreenIntent.Stop("s-facade"))
        runCurrent()
        assertFalse(StudioNoticeUi.ProjectFailed in screen.states.value.dismissedNotices(0))
        assertFalse(StudioNoticeUi.StopFailed in screen.states.value.dismissedNotices(0))
        assertTrue(AiStudioIntent.Public.AddProject(0) in fixture.machine.sent)
        assertTrue(AiStudioIntent.Public.Stop("s-facade") in fixture.machine.sent)
    }
}
