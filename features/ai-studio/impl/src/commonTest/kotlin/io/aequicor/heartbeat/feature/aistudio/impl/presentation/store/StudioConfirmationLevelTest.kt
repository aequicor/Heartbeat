package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioConfirmationLevelTest {
    @Test
    fun `profile confirmation changes reach the open studio without changing local approvals`() = runTest {
        val ready = AiStudioState.Ready(listOf(StudioPane(0)), 0, DefaultRunSettings)
        val levels = MutableStateFlow(StudioSecurityLevel.Automatic)
        val screen = Fixture(this, ready, securityLevels = levels).subscribe()
        val initialApproval = screen.states.value.settings.approval
        assertEquals(SecurityLevelUi.Automatic, screen.states.value.securityLevel)

        levels.value = StudioSecurityLevel.Ask
        runCurrent()
        assertEquals(SecurityLevelUi.Ask, screen.states.value.securityLevel)
        levels.value = StudioSecurityLevel.Trust
        runCurrent()
        assertEquals(SecurityLevelUi.Trust, screen.states.value.securityLevel)
        assertEquals(initialApproval, screen.states.value.settings.approval)
    }
}
