package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioHarnessRequestsTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0)),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = null,
    )

    @Test
    fun `native studio reflects pending requests and clears the counter when they disappear`() = runTest {
        val count = MutableStateFlow(2)
        val harnesses = object : StudioHarnesses by StudioHarnesses.None {
            override val awaitingPermissions = count
        }
        val screen = Fixture(this, ready, harnesses = harnesses).subscribe()
        assertEquals(2, screen.states.value.harnessAwaitingPermissions)
        count.value = 0
        runCurrent()
        assertEquals(0, screen.states.value.harnessAwaitingPermissions)
    }

    @Test
    fun `remote studio does not advertise a native harness navigation action`() = runTest {
        val harnesses = object : StudioHarnesses by StudioHarnesses.None {
            override val awaitingPermissions = MutableStateFlow(2)
        }
        val screen = Fixture(this, ready, harnesses = harnesses, host = StudioScreenHost.Remote).subscribe()
        assertEquals(0, screen.states.value.harnessAwaitingPermissions)
    }
}
