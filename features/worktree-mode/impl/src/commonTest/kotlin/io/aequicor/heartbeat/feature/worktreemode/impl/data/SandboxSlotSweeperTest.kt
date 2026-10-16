package io.aequicor.heartbeat.feature.worktreemode.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxSlotSweeperTest {
    @Test
    fun `a repository keeps the slots its budget allows while sandboxes are on`() {
        assertEquals(3, slotsToKeep(isSandboxEnabled = true, hasWorkingSandbox = false, isProjectKnown = true, 3))
        assertEquals(1, BuildBudget.parallel(1).slotsPerRepository)
        assertEquals(MAX_SLOTS_PER_REPOSITORY, BuildBudget.Unlimited.slotsPerRepository)
    }

    @Test
    fun `disabled sandboxes keep slots only for sandbox tasks that still work`() {
        assertEquals(0, slotsToKeep(isSandboxEnabled = false, hasWorkingSandbox = false, isProjectKnown = true, 3))
        assertEquals(3, slotsToKeep(isSandboxEnabled = false, hasWorkingSandbox = true, isProjectKnown = true, 3))
    }

    @Test
    fun `a project removed from the profile keeps no slots`() {
        assertEquals(0, slotsToKeep(isSandboxEnabled = true, hasWorkingSandbox = true, isProjectKnown = false, 3))
    }
}
