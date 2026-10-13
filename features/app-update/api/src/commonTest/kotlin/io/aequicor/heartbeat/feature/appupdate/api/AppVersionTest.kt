package io.aequicor.heartbeat.feature.appupdate.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `versions compare numerically by segment`() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 3))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertTrue(AppVersion(1, 0, 1) > AppVersion(1, 0, 0))
        assertEquals(0, AppVersion(1, 2, 3).compareTo(AppVersion(1, 2, 3)))
    }

    @Test
    fun `only plain three-part versions parse`() {
        assertEquals(AppVersion(1, 4, 0), AppVersion.parse("1.4.0"))
        assertEquals("1.4.0", AppVersion(1, 4, 0).toString())
        listOf("1.4", "1.4.0.1", "1.4.0-beta", "1.4.0+5", "01.4.0", "v1.4.0", "", "development", "9999999999.0.0")
            .forEach { assertNull(AppVersion.parse(it), it) }
    }

    @Test
    fun `only v-tags name app releases`() {
        assertEquals(AppVersion(1, 4, 0), AppVersion.fromTag("v1.4.0"))
        assertNull(AppVersion.fromTag("1.4.0"))
        assertNull(AppVersion.fromTag("152.0.6-hb2"))
        assertNull(AppVersion.fromTag("v1.4.0-rc1"))
    }
}
