package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultComputerUsePermissionGuideTest {
    @Test
    fun `an older profile lease cannot hide a replacement for the same permission`() {
        val guide = DefaultComputerUsePermissionGuide()
        val older = guide.show(ComputerUsePermission.Accessibility)
        val newer = guide.show(ComputerUsePermission.Accessibility)
        older.close()
        assertEquals(ComputerUsePermission.Accessibility, guide.guide.value)
        newer.close()
        assertNull(guide.guide.value)
    }

    @Test
    fun `a dismissed lease cannot hide a later guide when it eventually closes`() {
        val guide = DefaultComputerUsePermissionGuide()
        val dismissed = guide.show(ComputerUsePermission.ScreenRecording)
        guide.dismiss()
        assertNull(guide.guide.value)
        val newer = guide.show(ComputerUsePermission.Accessibility)
        dismissed.close()
        assertEquals(ComputerUsePermission.Accessibility, guide.guide.value)
        newer.close()
        newer.close()
        assertNull(guide.guide.value)
    }
}
