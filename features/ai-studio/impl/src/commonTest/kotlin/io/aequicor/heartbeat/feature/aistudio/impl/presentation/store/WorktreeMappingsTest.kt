package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.SandboxPhaseUi
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeMappingsTest {
    @Test
    fun `every sandbox phase maps to the screen phase of the same meaning`() {
        assertEquals(
            SandboxPhaseUi.entries.toList(),
            VirtualSandboxPhase.entries.map { it.toUi() },
        )
    }
}
