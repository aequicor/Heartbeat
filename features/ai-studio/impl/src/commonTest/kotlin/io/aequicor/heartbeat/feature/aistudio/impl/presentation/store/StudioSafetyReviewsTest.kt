package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewLoad
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewMonitor
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SafetyReviewsUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioSafetyReviewsTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0)),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `the sidebar mirrors the reviewer load while it is busy`() = runTest {
        val load = MutableStateFlow(AgentSafetyReviewLoad(capacity = 3))
        val monitor = object : AgentSafetyReviewMonitor {
            override val load = load
        }
        val screen = Fixture(this, ready, safetyReviews = monitor, showsSafetyReviews = flowOf(true)).subscribe()
        assertNull(screen.states.value.safetyReviews)

        load.value = AgentSafetyReviewLoad(capacity = 3, running = 3, queued = 2)
        runCurrent()
        assertEquals(SafetyReviewsUi(capacity = 3, running = 3, queued = 2), screen.states.value.safetyReviews)

        load.value = AgentSafetyReviewLoad(capacity = 3, abandoned = 1)
        runCurrent()
        assertNull(screen.states.value.safetyReviews)
    }

    @Test
    fun `the indicator follows its toggle`() = runTest {
        val monitor = object : AgentSafetyReviewMonitor {
            override val load = MutableStateFlow(AgentSafetyReviewLoad(capacity = 3, running = 1))
        }
        val isShown = MutableStateFlow(false)
        val screen = Fixture(this, ready, safetyReviews = monitor, showsSafetyReviews = isShown).subscribe()
        runCurrent()
        assertNull(screen.states.value.safetyReviews)

        isShown.value = true
        runCurrent()
        assertEquals(SafetyReviewsUi(capacity = 3, running = 1), screen.states.value.safetyReviews)

        isShown.value = false
        runCurrent()
        assertNull(screen.states.value.safetyReviews)
    }

    @Test
    fun `an idle reviewer is hidden even after abandoned reviews`() {
        assertNull(AgentSafetyReviewLoad(capacity = 3).toUi())
        assertNull(AgentSafetyReviewLoad(capacity = 3, abandoned = 2).toUi())
    }

    @Test
    fun `running and queued and finishing reviews are shown with their details`() {
        assertEquals(
            SafetyReviewsUi(capacity = 3, running = 1, queued = 2, finishing = 1, abandoned = 1),
            AgentSafetyReviewLoad(capacity = 3, running = 1, queued = 2, finishing = 1, abandoned = 1).toUi(),
        )
        assertEquals(SafetyReviewsUi(capacity = 3, queued = 1), AgentSafetyReviewLoad(3, queued = 1).toUi())
    }
}
